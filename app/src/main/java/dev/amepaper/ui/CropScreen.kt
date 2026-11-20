package dev.amepaper.ui

import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.amepaper.R
import dev.amepaper.data.Crop
import dev.amepaper.data.Importer
import dev.amepaper.data.Library
import dev.amepaper.data.Prefs
import dev.amepaper.data.Screens
import dev.amepaper.data.Slot
import dev.amepaper.data.WallImage
import dev.amepaper.render.Blur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val RATIOS = listOf(
    "screen", "free", "original", "9:21", "9:20", "9:19.5", "9:16", "2:3", "3:4", "4:5", "1:1", "5:4", "4:3", "16:9",
)

private fun ratioLabel(r: String): String = when (r) {
    "screen" -> "Screen"
    "free" -> "Free"
    "original" -> "Original"
    else -> r
}

private enum class Handle { TL, TR, BL, BR, L, R, T, B }

private fun frameRect(area: IntSize, aspect: Float): Rect {
    val m = 40f
    val aw = max(1f, area.width - 2 * m)
    val ah = max(1f, area.height - 2 * m)
    var fw = aw
    var fh = fw / aspect
    if (fh > ah) {
        fh = ah
        fw = fh * aspect
    }
    val left = (area.width - fw) / 2f
    val top = (area.height - fh) / 2f
    return Rect(left, top, left + fw, top + fh)
}

/**
 * State of the crop editor: a frame (fixed aspect, or free-form with draggable corners) and the
 * picture under it, which can be panned and zoomed in or out. Zooming out below "fill" leaves
 * empty space inside the frame, filled with a blurred copy of the picture or black.
 */
private class CropModel(private val sp: SharedPreferences, img: WallImage, startSlot: Slot) {
    var bmp by mutableStateOf<ImageBitmap?>(null)
    var fillBmp by mutableStateOf<ImageBitmap?>(null)
    var area by mutableStateOf(IntSize.Zero)
    var slot by mutableStateOf(startSlot)
    val work = mutableStateMapOf(Slot.TALL to img.tall, Slot.WIDE to img.wide)
    val labels = mutableStateMapOf(
        Slot.TALL to (img.tall?.aspect ?: "screen"),
        Slot.WIDE to (img.wide?.aspect ?: "screen"),
    )
    val fills = mutableStateMapOf(
        Slot.TALL to (img.tall?.fill ?: "blur"),
        Slot.WIDE to (img.wide?.fill ?: "blur"),
    )
    var freeFrame by mutableStateOf<Rect?>(null)
    var scale by mutableFloatStateOf(1f)
    var ox by mutableFloatStateOf(0f)
    var oy by mutableFloatStateOf(0f)

    val ready: Boolean get() = bmp != null && area.width > 0 && area.height > 0
    val bw: Float get() = (bmp?.width ?: 1).toFloat()
    val bh: Float get() = (bmp?.height ?: 1).toFloat()
    val label: String get() = labels[slot] ?: "screen"
    val fill: String get() = fills[slot] ?: "blur"

    private fun aspectOf(l: String): Float {
        val a = when (l) {
            "screen" -> Screens.aspect(sp, slot)
            "original" -> bw / bh
            "free" -> {
                val c = work[slot]
                if (c != null) ((c.r - c.l) * bw) / max(0.0001f, (c.b - c.t) * bh) else Screens.aspect(sp, slot)
            }
            else -> {
                val parts = l.split(":")
                val x = parts.getOrNull(0)?.toFloatOrNull() ?: 9f
                val y = parts.getOrNull(1)?.toFloatOrNull() ?: 16f
                x / y
            }
        }
        return a.coerceIn(0.2f, 5f)
    }

    fun frame(): Rect =
        if (label == "free") freeFrame ?: frameRect(area, aspectOf("free")) else frameRect(area, aspectOf(label))

    private fun coverScale(fr: Rect) = max(fr.width / bw, fr.height / bh)
    private fun fitScale(fr: Rect) = min(fr.width / bw, fr.height / bh)
    private fun lo(fr: Rect) = fitScale(fr) * 0.5f
    private fun hi(fr: Rect) = coverScale(fr) * 8f

    /** Picture larger than the frame must cover it; smaller must stay inside it (per axis). */
    private fun clamp(fr: Rect) {
        scale = scale.coerceIn(lo(fr), hi(fr))
        val iw = bw * scale
        val ih = bh * scale
        val ax = fr.left
        val bx = fr.right - iw
        ox = ox.coerceIn(min(ax, bx), max(ax, bx))
        val ay = fr.top
        val by = fr.bottom - ih
        oy = oy.coerceIn(min(ay, by), max(ay, by))
    }

    private fun center(fr: Rect) {
        ox = fr.center.x - bw * scale / 2f
        oy = fr.center.y - bh * scale / 2f
    }

    /** Place the picture according to the saved crop for the current slot. */
    fun init() {
        if (!ready) return
        val fr = frame()
        val c = work[slot]
        if (c == null) {
            scale = coverScale(fr)
            center(fr)
        } else {
            val cw = max(1f, (c.r - c.l) * bw)
            val ch = max(1f, (c.b - c.t) * bh)
            scale = min(fr.width / cw, fr.height / ch)
            ox = fr.center.x - (c.l + c.r) / 2f * bw * scale
            oy = fr.center.y - (c.t + c.b) / 2f * bh * scale
        }
        clamp(fr)
    }

    fun crop(): Crop {
        val fr = frame()
        val iw = bw * scale
        val ih = bh * scale
        return Crop(
            label,
            ((fr.left - ox) / iw).coerceIn(-4f, 5f),
            ((fr.top - oy) / ih).coerceIn(-4f, 5f),
            ((fr.right - ox) / iw).coerceIn(-4f, 5f),
            ((fr.bottom - oy) / ih).coerceIn(-4f, 5f),
            fill,
        )
    }

    fun commit() {
        if (ready) work[slot] = crop()
    }

    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        if (!ready) return
        val fr = frame()
        val s = (scale * zoom).coerceIn(lo(fr), hi(fr))
        val z = s / scale
        ox = centroid.x - (centroid.x - ox) * z + pan.x
        oy = centroid.y - (centroid.y - oy) * z + pan.y
        scale = s
        clamp(fr)
        commit()
    }

    fun zoomBy(f: Float) {
        if (ready) transform(frame().center, Offset.Zero, f)
    }

    /** Slider position 0..1 (log scale from "half of fit" to "8× fill"). */
    fun zoomPos(): Float {
        if (!ready) return 0.5f
        val fr = frame()
        val a = lo(fr)
        val b = hi(fr)
        return (ln(scale / a) / ln(b / a)).coerceIn(0f, 1f)
    }

    fun setZoomPos(t: Float) {
        if (!ready) return
        val fr = frame()
        val a = lo(fr)
        val b = hi(fr)
        val target = a * exp(ln(b / a) * t.coerceIn(0f, 1f))
        transform(fr.center, Offset.Zero, target / scale)
    }

    /** 100% = the picture exactly fills the frame; below 100% leaves empty space. */
    fun zoomPercent(): Int = if (!ready) 100 else (scale / coverScale(frame()) * 100f).roundToInt()

    fun fitWhole() {
        if (!ready) return
        val fr = frame()
        scale = fitScale(fr)
        center(fr)
        clamp(fr)
        commit()
    }

    fun fillFrame() {
        if (!ready) return
        val fr = frame()
        scale = coverScale(fr)
        center(fr)
        clamp(fr)
        commit()
    }

    fun centerImage() {
        if (!ready) return
        val fr = frame()
        center(fr)
        clamp(fr)
        commit()
    }

    fun toggleFit() {
        if (!ready) return
        if (abs(scale - coverScale(frame())) <= scale * 0.01f) fitWhole() else fillFrame()
    }

    fun setLabel(l: String) {
        if (!ready) {
            labels[slot] = l
            return
        }
        commit()
        freeFrame = if (l == "free") frame() else null
        labels[slot] = l
        init()
        commit()
    }

    fun setFill(f: String) {
        fills[slot] = f
        commit()
    }

    fun changeSlot(s: Slot) {
        if (s == slot) return
        slot = s
        freeFrame = null
        init()
    }

    fun reset() {
        work[slot] = null
        labels[slot] = "screen"
        fills[slot] = "blur"
        freeFrame = null
        init()
    }

    fun hit(p: Offset, touch: Float): Handle? {
        if (label != "free" || !ready) return null
        val fr = frame()
        fun near(x: Float, y: Float) = abs(p.x - x) <= touch && abs(p.y - y) <= touch
        val inY = p.y in fr.top..fr.bottom
        val inX = p.x in fr.left..fr.right
        return when {
            near(fr.left, fr.top) -> Handle.TL
            near(fr.right, fr.top) -> Handle.TR
            near(fr.left, fr.bottom) -> Handle.BL
            near(fr.right, fr.bottom) -> Handle.BR
            abs(p.x - fr.left) <= touch && inY -> Handle.L
            abs(p.x - fr.right) <= touch && inY -> Handle.R
            abs(p.y - fr.top) <= touch && inX -> Handle.T
            abs(p.y - fr.bottom) <= touch && inX -> Handle.B
            else -> null
        }
    }

    fun resize(h: Handle, d: Offset, minSize: Float, margin: Float) {
        if (!ready) return
        val fr = frame()
        var l = fr.left
        var t = fr.top
        var r = fr.right
        var b = fr.bottom
        val moveL = h == Handle.TL || h == Handle.BL || h == Handle.L
        val moveR = h == Handle.TR || h == Handle.BR || h == Handle.R
        val moveT = h == Handle.TL || h == Handle.TR || h == Handle.T
        val moveB = h == Handle.BL || h == Handle.BR || h == Handle.B
        if (moveL) l = min(max(l + d.x, margin), fr.right - minSize)
        if (moveR) r = max(min(r + d.x, area.width - margin), fr.left + minSize)
        if (moveT) t = min(max(t + d.y, margin), fr.bottom - minSize)
        if (moveB) b = max(min(b + d.y, area.height - margin), fr.top + minSize)
        val nf = Rect(l, t, r, b)
        freeFrame = nf
        clamp(nf)
        commit()
    }
}

@Composable
fun CropScreen(albumId: String, imageId: String, pop: () -> Unit) {
    val ctx = LocalContext.current
    val sp = remember { Prefs.sp(ctx) }
    val albums by Library.albums.collectAsState()
    val img = albums.firstOrNull { it.id == albumId }?.images?.firstOrNull { it.id == imageId }
    if (img == null) {
        LaunchedEffect(Unit) { pop() }
        return
    }

    val conf = LocalConfiguration.current
    val model = remember(img.id) {
        CropModel(sp, img, if (conf.screenHeightDp >= conf.screenWidthDp * 1.5f) Slot.TALL else Slot.WIDE)
    }

    LaunchedEffect(img.id) {
        val loaded = withContext(Dispatchers.IO) {
            val b = Importer.decodeForEditor(Library.file(img), 2048) ?: return@withContext null
            val small = Bitmap.createScaledBitmap(b, max(8, b.width / 8), max(8, b.height / 8), true)
            Pair(b.asImageBitmap(), Blur.box(small, 3, 3).asImageBitmap())
        }
        if (loaded != null) {
            model.fillBmp = loaded.second
            model.bmp = loaded.first
        }
    }
    LaunchedEffect(model.slot, model.area, model.bmp) {
        model.freeFrame = null
        model.init()
    }

    fun save() {
        model.commit()
        Library.setCrops(albumId, imageId, model.work[Slot.TALL], model.work[Slot.WIDE])
        pop()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Zoom & crop") },
                navigationIcon = {
                    IconButton(onClick = pop) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { save() }) { Icon(Icons.Filled.Check, contentDescription = "Save") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                SegmentedButton(
                    selected = model.slot == Slot.TALL,
                    onClick = { model.changeSlot(Slot.TALL) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Cover screen") }
                SegmentedButton(
                    selected = model.slot == Slot.WIDE,
                    onClick = { model.changeSlot(Slot.WIDE) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Main screen") }
            }
            Text(
                if (model.label == "free") "Drag the corners or edges to reshape. Pinch or drag the picture to zoom and move it."
                else "Pinch or use the slider to zoom in or out. Drag to move. Double-tap to switch fit / fill.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .background(Color(0xFF0C0809))
                    .onSizeChanged { model.area = it }
                    .pointerInput(model) {
                        val touch = 28.dp.toPx()
                        val minSize = 72.dp.toPx()
                        val margin = 12.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val handle = model.hit(down.position, touch)
                            if (handle != null) {
                                down.consume()
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) break
                                    val d = ch.positionChange()
                                    if (d != Offset.Zero) {
                                        model.resize(handle, d, minSize, margin)
                                        ch.consume()
                                    }
                                }
                            } else {
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    if (ev.changes.none { it.pressed }) break
                                    val zoom = ev.calculateZoom()
                                    val pan = ev.calculatePan()
                                    val centroid = ev.calculateCentroid(useCurrent = false)
                                    if ((zoom != 1f || pan != Offset.Zero) && centroid.isSpecified) {
                                        model.transform(centroid, pan, zoom)
                                        ev.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                }
                            }
                        }
                    }
                    .pointerInput(model) {
                        detectTapGestures(onDoubleTap = { model.toggleFit() })
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (model.bmp == null) CircularProgressIndicator() else EditorCanvas(model)
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { model.zoomBy(1f / 1.15f) }) {
                    Icon(painterResource(R.drawable.ic_zoom_out), contentDescription = "Zoom out")
                }
                Slider(
                    value = model.zoomPos(),
                    onValueChange = { model.setZoomPos(it) },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { model.zoomBy(1.15f) }) {
                    Icon(painterResource(R.drawable.ic_zoom_in), contentDescription = "Zoom in")
                }
                Text(
                    "${model.zoomPercent()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(52.dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(onClick = { model.fitWhole() }, label = { Text("Fit whole picture") })
                AssistChip(onClick = { model.fillFrame() }, label = { Text("Fill frame") })
                AssistChip(onClick = { model.centerImage() }, label = { Text("Center") })
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RATIOS.forEach { r ->
                    FilterChip(
                        selected = model.label == r,
                        onClick = { model.setLabel(r) },
                        label = { Text(ratioLabel(r)) },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Empty space", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 12.dp))
                val opts = listOf("blur" to "Blurred", "black" to "Black")
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    opts.forEachIndexed { i, (k, name) ->
                        SegmentedButton(
                            selected = model.fill == k,
                            onClick = { model.setFill(k) },
                            shape = SegmentedButtonDefaults.itemShape(i, opts.size),
                        ) { Text(name) }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(onClick = { model.reset() }) { Text("No crop") }
                Spacer(Modifier.width(12.dp))
                Button(onClick = { save() }) { Text("Save") }
            }
        }
    }
}

@Composable
private fun EditorCanvas(m: CropModel) {
    val bmp = m.bmp ?: return
    Canvas(Modifier.fillMaxSize()) {
        val fr = m.frame()

        // what fills the frame where the picture doesn't reach (zoomed out)
        clipRect(fr.left, fr.top, fr.right, fr.bottom) {
            drawRect(Color.Black, fr.topLeft, fr.size)
            val fb = m.fillBmp
            if (m.fill == "blur" && fb != null) {
                val s = max(fr.width / fb.width, fr.height / fb.height)
                val w = fb.width * s
                val h = fb.height * s
                drawImage(
                    image = fb,
                    dstOffset = IntOffset((fr.center.x - w / 2f).roundToInt(), (fr.center.y - h / 2f).roundToInt()),
                    dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                    alpha = 0.75f,
                    filterQuality = FilterQuality.Low,
                )
            }
        }

        drawImage(
            image = bmp,
            dstOffset = IntOffset(m.ox.roundToInt(), m.oy.roundToInt()),
            dstSize = IntSize((m.bw * m.scale).roundToInt(), (m.bh * m.scale).roundToInt()),
            filterQuality = FilterQuality.Medium,
        )

        val dim = Color.Black.copy(alpha = 0.62f)
        drawRect(dim, Offset.Zero, Size(size.width, fr.top))
        drawRect(dim, Offset(0f, fr.bottom), Size(size.width, size.height - fr.bottom))
        drawRect(dim, Offset(0f, fr.top), Size(fr.left, fr.height))
        drawRect(dim, Offset(fr.right, fr.top), Size(size.width - fr.right, fr.height))

        val thin = 1.dp.toPx()
        for (i in 1..2) {
            val x = fr.left + fr.width * i / 3f
            val y = fr.top + fr.height * i / 3f
            drawLine(Color.White.copy(alpha = 0.35f), Offset(x, fr.top), Offset(x, fr.bottom), thin)
            drawLine(Color.White.copy(alpha = 0.35f), Offset(fr.left, y), Offset(fr.right, y), thin)
        }
        drawRect(Color.White, fr.topLeft, fr.size, style = Stroke(2.dp.toPx()))

        if (m.label == "free") {
            val r = 7.dp.toPx()
            val points = listOf(
                fr.topLeft, Offset(fr.right, fr.top), Offset(fr.left, fr.bottom), Offset(fr.right, fr.bottom),
                Offset(fr.left, fr.center.y), Offset(fr.right, fr.center.y),
                Offset(fr.center.x, fr.top), Offset(fr.center.x, fr.bottom),
            )
            points.forEach { p ->
                drawCircle(Color.White, r, p)
                drawCircle(Color(0xFF7E2148), r * 0.55f, p)
            }
        }
    }
}
