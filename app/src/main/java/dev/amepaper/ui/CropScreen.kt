package dev.amepaper.ui

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import dev.amepaper.data.Changer
import dev.amepaper.data.Screen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import dev.amepaper.render.AiHandoff
import dev.amepaper.render.FillRenderer
import kotlinx.coroutines.launch
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
import androidx.compose.material3.Switch
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
import dev.amepaper.data.K
import dev.amepaper.data.Library
import dev.amepaper.data.Prefs
import dev.amepaper.data.Screens
import dev.amepaper.data.Slot
import dev.amepaper.data.WallImage
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
    var abmp by mutableStateOf<Bitmap?>(null)
    var area by mutableStateOf(IntSize.Zero)
    var slot by mutableStateOf(startSlot)
    val work = mutableStateMapOf(Slot.TALL to img.tall, Slot.WIDE to img.wide)
    val labels = mutableStateMapOf(
        Slot.TALL to (img.tall?.aspect ?: "screen"),
        Slot.WIDE to (img.wide?.aspect ?: "screen"),
    )
    val fills = mutableStateMapOf(
        Slot.TALL to (img.tall?.fill ?: "extend"),
        Slot.WIDE to (img.wide?.fill ?: "extend"),
    )
    val fillBlurs = mutableStateMapOf(Slot.TALL to (img.tall?.fillBlur ?: 0.5f), Slot.WIDE to (img.wide?.fillBlur ?: 0.5f))
    val fillDims = mutableStateMapOf(Slot.TALL to (img.tall?.fillDim ?: 0.2f), Slot.WIDE to (img.wide?.fillDim ?: 0.2f))
    val fillSofts = mutableStateMapOf(Slot.TALL to (img.tall?.fillSoft ?: 0.3f), Slot.WIDE to (img.wide?.fillSoft ?: 0.3f))
    var freeFrame by mutableStateOf<Rect?>(null)

    /** Copy this framing to the other screen (adapted to its shape) when saving. */
    var bothScreens by mutableStateOf(
        if (startSlot == Slot.TALL) img.wide == null else img.tall == null,
    )
    var scale by mutableFloatStateOf(1f)
    var ox by mutableFloatStateOf(0f)
    var oy by mutableFloatStateOf(0f)

    val ready: Boolean get() = bmp != null && area.width > 0 && area.height > 0
    val bw: Float get() = (bmp?.width ?: 1).toFloat()
    val bh: Float get() = (bmp?.height ?: 1).toFloat()
    val label: String get() = labels[slot] ?: "screen"
    val fill: String get() = fills[slot] ?: "extend"
    val fillBlur: Float get() = fillBlurs[slot] ?: 0.5f
    val fillDim: Float get() = fillDims[slot] ?: 0.2f
    val fillSoft: Float get() = fillSofts[slot] ?: 0.3f

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
            fill, fillBlur, fillDim, fillSoft,
        )
    }

    fun commit() {
        if (ready) work[slot] = crop()
    }

    /**
     * The current framing re-made for the other screen: same centre and the same zoom level,
     * in that screen's shape.
     */
    fun adaptedForOtherSlot(): Crop? {
        val c = work[slot] ?: return null
        if (!ready) return null
        val other = if (slot == Slot.TALL) Slot.WIDE else Slot.TALL
        val label2 = if (c.aspect == "screen" || c.aspect == "free") "screen" else c.aspect
        val a1 = ((c.r - c.l) * bw) / max(1e-4f, (c.b - c.t) * bh)
        val a2 = when (label2) {
            "screen" -> Screens.aspect(sp, other)
            "original" -> bw / bh
            else -> {
                val parts = label2.split(":")
                (parts.getOrNull(0)?.toFloatOrNull() ?: 9f) / (parts.getOrNull(1)?.toFloatOrNull() ?: 16f)
            }
        }.coerceIn(0.2f, 5f)
        val ia = bw / bh
        val ch1 = (c.b - c.t) * bh
        val coverH1 = if (ia > a1) bh else bw / a1
        val zoom = coverH1 / max(1f, ch1)
        val coverH2 = if (ia > a2) bh else bw / a2
        val ch2 = coverH2 / zoom
        val cw2 = ch2 * a2
        val cx = (c.l + c.r) / 2f * bw
        val cy = (c.t + c.b) / 2f * bh
        fun place(center: Float, size: Float, full: Float): Float {
            val lo = min(0f, full - size)
            val hi = max(0f, full - size)
            return (center - size / 2f).coerceIn(lo, hi)
        }
        val left = place(cx, cw2, bw)
        val top = place(cy, ch2, bh)
        return Crop(label2, left / bw, top / bh, (left + cw2) / bw, (top + ch2) / bh, c.fill, c.fillBlur, c.fillDim, c.fillSoft)
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

    fun setFillBlur(v: Float) { fillBlurs[slot] = v; commit() }
    fun setFillDim(v: Float) { fillDims[slot] = v; commit() }
    fun setFillSoft(v: Float) { fillSofts[slot] = v; commit() }

    /** The picture's rectangle on the canvas. */
    fun picRect(): Rect = Rect(ox, oy, ox + bw * scale, oy + bh * scale)

    /** True when the picture doesn't fill the frame (so the fill shows). */
    fun hasEmptySpace(): Boolean {
        if (!ready) return false
        val fr = frame()
        val p = picRect()
        return p.left > fr.left + 0.5f || p.top > fr.top + 0.5f || p.right < fr.right - 0.5f || p.bottom < fr.bottom - 0.5f
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
        fills[slot] = "extend"
        fillBlurs[slot] = 0.5f
        fillDims[slot] = 0.2f
        fillSofts[slot] = 0.3f
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
    val model = remember(img.id, img.file) {
        CropModel(sp, img, if (conf.screenHeightDp >= conf.screenWidthDp * 1.5f) Slot.TALL else Slot.WIDE)
    }

    LaunchedEffect(model) {
        val loaded = withContext(Dispatchers.IO) { Importer.decodeForEditor(Library.file(img), 2048) }
        if (loaded != null) {
            model.abmp = loaded
            model.bmp = loaded.asImageBitmap()
        }
    }
    val scope = rememberCoroutineScope()
    var tool by remember { mutableIntStateOf(0) }
    var aiBusy by remember { mutableStateOf(false) }
    val replacePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val newImg = withContext(Dispatchers.IO) { Importer.import(ctx, uri) }
                if (newImg != null) {
                    Library.replaceImage(albumId, imageId, newImg)
                    Toast.makeText(ctx, "Picture replaced. Adjust it and tap Save.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(ctx, "Couldn't open that picture", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    LaunchedEffect(model.slot, model.area, model.bmp) {
        model.freeFrame = null
        model.init()
    }

    var applying by remember { mutableStateOf(false) }

    fun store() {
        model.commit()
        if (model.bothScreens) {
            val other = if (model.slot == Slot.TALL) Slot.WIDE else Slot.TALL
            model.adaptedForOtherSlot()?.let { model.work[other] = it }
        }
        Library.setCrops(albumId, imageId, model.work[Slot.TALL], model.work[Slot.WIDE])
        // make the live wallpaper reload right away
        sp.edit().putLong(K.REFRESH.name, System.currentTimeMillis()).apply()
    }

    fun save() {
        store()
        val showing = Screen.entries.filter { Changer.current(sp, it)?.id == imageId }
        val msg = when {
            !isWallpaperActive(ctx) -> "Saved. Tap Set wallpaper to use it."
            showing.size == 2 -> "Saved and updated on your home & lock screens"
            showing.size == 1 -> "Saved and updated on your ${if (showing[0] == Screen.HOME) "home" else "lock"} screen"
            else -> "Saved. It's not on screen right now; tap Set wallpaper to show it."
        }
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        pop()
    }

    fun applyTo(screens: List<Screen>) {
        applying = false
        store()
        applyWallpaper(ctx, albumId, imageId, screens)
        pop()
    }

    if (applying) {
        AlertDialog(
            onDismissRequest = { applying = false },
            title = { Text("Set as wallpaper") },
            text = {
                Column {
                    Text(
                        "Saves your edits and shows this picture now. Scheduled changes carry on from here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    ApplyOption("Home screen") { applyTo(listOf(Screen.HOME)) }
                    ApplyOption("Lock screen") { applyTo(listOf(Screen.LOCK)) }
                    ApplyOption("Home & lock screens") { applyTo(listOf(Screen.HOME, Screen.LOCK)) }
                }
            },
            confirmButton = { TextButton(onClick = { applying = false }) { Text("Cancel") } },
        )
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

            // ---- tool switcher
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                val tools = listOf("Zoom", "Shape", "Fill")
                tools.forEachIndexed { i, name ->
                    SegmentedButton(
                        selected = tool == i,
                        onClick = { tool = i },
                        shape = SegmentedButtonDefaults.itemShape(i, tools.size),
                    ) { Text(name) }
                }
            }
            when (tool) {
                0 -> ZoomPanel(model)
                1 -> ShapePanel(model)
                else -> FillPanel(
                    model,
                    aiBusy = aiBusy,
                    onAi = {
                        aiBusy = true
                        scope.launch {
                            model.commit()
                            val c = model.work[model.slot] ?: model.crop()
                            val uri = withContext(Dispatchers.IO) { AiHandoff.export(ctx, img, c) }
                            aiBusy = false
                            if (uri != null) {
                                AiHandoff.openEditor(ctx, uri)
                            } else {
                                Toast.makeText(ctx, "Couldn't save the picture for editing", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onReplace = {
                        replacePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { model.bothScreens = !model.bothScreens }
                    .padding(horizontal = 20.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Same framing on cover & main screens",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = model.bothScreens, onCheckedChange = { model.bothScreens = it })
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 2.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { model.reset() }) { Text("Reset") }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { save() }) { Text("Save") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { applying = true }, enabled = model.ready) { Text("Set wallpaper") }
            }
        }
    }
}

@Composable
private fun ApplyOption(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ZoomPanel(model: CropModel) {
    Column {
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
    }
}

@Composable
private fun ShapePanel(model: CropModel) {
    Column {
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
        Text(
            "Screen matches this display exactly. Free lets you drag the frame's corners and edges.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun FillPanel(model: CropModel, aiBusy: Boolean, onAi: () -> Unit, onReplace: () -> Unit) {
    Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
        if (!model.hasEmptySpace()) {
            Text(
                "Zoom out below 100% (or tap Fit whole picture) to make room around the picture. These settings fill that space.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FillRenderer.MODES.forEach { m ->
                FilterChip(
                    selected = model.fill == m,
                    onClick = { model.setFill(m) },
                    label = { Text(FillRenderer.label(m)) },
                )
            }
        }
        if (FillRenderer.usesBlur(model.fill)) {
            MiniSlider("Blur", model.fillBlur) { model.setFillBlur(it) }
        }
        if (model.fill != "black") {
            MiniSlider("Darken", model.fillDim) { model.setFillDim(it) }
        }
        MiniSlider("Blend edges", model.fillSoft) { model.setFillSoft(it) }

        if (AiHandoff.supported()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text("AI generative fill", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Saves this framing (empty space in black) to Pictures/Amepaper and opens it in your photo editor, " +
                        "e.g. Galaxy AI Generative edit or Google Photos, to fill it with new content. " +
                        "Then tap Use edited picture and pick the result.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onAi, enabled = !aiBusy && model.ready) {
                        Text(if (aiBusy) "Saving…" else "Open in AI editor")
                    }
                    OutlinedButton(onClick = onReplace) { Text("Use edited picture") }
                }
            }
        }
    }
}

@Composable
private fun MiniSlider(title: String, value: Float, onChange: (Float) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
        Slider(value = value, onValueChange = onChange, modifier = Modifier.weight(1f))
        Text(
            "${(value * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(44.dp).padding(start = 6.dp),
        )
    }
}

@Composable
private fun EditorCanvas(m: CropModel) {
    if (m.bmp == null) return
    val abmp = m.abmp ?: return

    // 1) the fill, drawn across the whole editor and blurred on the GPU (Android 12+)
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val fr = m.frame()
                val r = if (FillRenderer.usesBlur(m.fill)) {
                    FillRenderer.blurRadius(m.fillBlur, min(fr.width, fr.height)) * 1.73f
                } else {
                    0f
                }
                renderEffect = if (r > 0.5f) BlurEffect(r, r, TileMode.Clamp) else null
            },
    ) {
        drawRect(Color.Black)
        if (m.fill == "black") return@Canvas
        val fr = m.frame()
        val p = m.picRect()
        // the part of the picture inside the frame (what the wallpaper will contain)
        val sx0 = ((max(fr.left, p.left) - p.left) / m.scale).coerceIn(0f, m.bw - 1f)
        val sy0 = ((max(fr.top, p.top) - p.top) / m.scale).coerceIn(0f, m.bh - 1f)
        val sx1 = ((min(fr.right, p.right) - p.left) / m.scale).coerceIn(sx0 + 1f, m.bw)
        val sy1 = ((min(fr.bottom, p.bottom) - p.top) / m.scale).coerceIn(sy0 + 1f, m.bh)
        val src = android.graphics.Rect(sx0.roundToInt(), sy0.roundToInt(), sx1.roundToInt(), sy1.roundToInt())
        val visible = android.graphics.RectF(
            p.left + src.left * m.scale, p.top + src.top * m.scale,
            p.left + src.right * m.scale, p.top + src.bottom * m.scale,
        )
        val r = FillRenderer.blurRadius(m.fillBlur, min(fr.width, fr.height)) * 3f + 4f
        val area = android.graphics.RectF(fr.left - r, fr.top - r, fr.right + r, fr.bottom + r)
        drawIntoCanvas { c -> FillRenderer.drawFillBase(c.nativeCanvas, area, abmp, src, visible, m.fill) }
    }

    // 2) darkening, the picture (with blended edges), the frame and handles
    Canvas(Modifier.fillMaxSize()) {
        val fr = m.frame()
        if (m.fill != "black" && m.fillDim > 0.01f) {
            drawRect(Color.Black.copy(alpha = m.fillDim), fr.topLeft, fr.size)
        }
        val p = m.picRect()
        drawIntoCanvas { c ->
            FillRenderer.drawPicture(
                c.nativeCanvas, abmp, null,
                android.graphics.RectF(p.left, p.top, p.right, p.bottom),
                android.graphics.RectF(fr.left, fr.top, fr.right, fr.bottom),
                m.fillSoft,
            )
        }

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
            points.forEach { pt ->
                drawCircle(Color.White, r, pt)
                drawCircle(Color(0xFF7E2148), r * 0.55f, pt)
            }
        }
    }
}
