package dev.amepaper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.amepaper.data.Crop
import dev.amepaper.data.Importer
import dev.amepaper.data.Library
import dev.amepaper.data.Prefs
import dev.amepaper.data.Screens
import dev.amepaper.data.Slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val RATIOS = listOf(
    "screen", "original", "9:21", "9:20", "9:19.5", "9:16", "2:3", "3:4", "4:5", "1:1", "5:4", "4:3", "16:9",
)

private fun ratioLabel(r: String): String = when (r) {
    "screen" -> "Screen"
    "original" -> "Original"
    else -> r
}

/** Pan/zoom state of the image under a fixed crop frame (all in canvas pixels). */
private class CropState {
    var scale by mutableFloatStateOf(1f)
    var ox by mutableFloatStateOf(0f)
    var oy by mutableFloatStateOf(0f)

    fun clamp(fr: Rect, bw: Float, bh: Float) {
        val sMin = max(fr.width / bw, fr.height / bh)
        scale = scale.coerceIn(sMin, sMin * 10f)
        ox = min(fr.left, max(fr.right - bw * scale, ox))
        oy = min(fr.top, max(fr.bottom - bh * scale, oy))
    }

    fun initFrom(c: Crop?, fr: Rect, bw: Float, bh: Float) {
        val sMin = max(fr.width / bw, fr.height / bh)
        if (c == null) {
            scale = sMin
            ox = fr.center.x - bw * scale / 2f
            oy = fr.center.y - bh * scale / 2f
        } else {
            val cw = max(1f, (c.r - c.l) * bw)
            val ch = max(1f, (c.b - c.t) * bh)
            val cx = (c.l + c.r) / 2f * bw
            val cy = (c.t + c.b) / 2f * bh
            scale = max(sMin, min(fr.width / cw, fr.height / ch))
            ox = fr.center.x - cx * scale
            oy = fr.center.y - cy * scale
        }
        clamp(fr, bw, bh)
    }

    fun toCrop(label: String, fr: Rect, bw: Float, bh: Float): Crop {
        val iw = bw * scale
        val ih = bh * scale
        return Crop(
            label,
            ((fr.left - ox) / iw).coerceIn(0f, 1f),
            ((fr.top - oy) / ih).coerceIn(0f, 1f),
            ((fr.right - ox) / iw).coerceIn(0f, 1f),
            ((fr.bottom - oy) / ih).coerceIn(0f, 1f),
        )
    }
}

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
    var slot by remember {
        mutableStateOf(if (conf.screenHeightDp >= conf.screenWidthDp * 1.5f) Slot.TALL else Slot.WIDE)
    }
    val work = remember { mutableStateMapOf(Slot.TALL to img.tall, Slot.WIDE to img.wide) }
    val labels = remember {
        mutableStateMapOf(Slot.TALL to (img.tall?.aspect ?: "screen"), Slot.WIDE to (img.wide?.aspect ?: "screen"))
    }
    val touched = remember { mutableStateMapOf(Slot.TALL to false, Slot.WIDE to false) }
    var resetTick by remember { mutableIntStateOf(0) }

    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(null, img.id) {
        value = withContext(Dispatchers.IO) { Importer.decodeForEditor(Library.file(img), 2048)?.asImageBitmap() }
    }

    val st = remember { CropState() }
    var area by remember { mutableStateOf(IntSize.Zero) }
    val label = labels[slot] ?: "screen"
    val bmp = bitmap
    val bw = (bmp?.width ?: 1).toFloat()
    val bh = (bmp?.height ?: 1).toFloat()
    val aspect = when (label) {
        "screen" -> Screens.aspect(sp, slot)
        "original" -> bw / bh
        else -> {
            val parts = label.split(":")
            val a = parts.getOrNull(0)?.toFloatOrNull() ?: 9f
            val b = parts.getOrNull(1)?.toFloatOrNull() ?: 16f
            a / b
        }
    }.coerceIn(0.2f, 5f)

    LaunchedEffect(slot, aspect, area, bmp, resetTick) {
        if (bmp == null || area.width == 0 || area.height == 0) return@LaunchedEffect
        val fr = frameRect(area, aspect)
        st.initFrom(work[slot], fr, bw, bh)
        if (touched[slot] == true) work[slot] = st.toCrop(label, fr, bw, bh)
    }

    fun save() {
        if (bmp != null && area.width > 0) {
            work[slot] = st.toCrop(label, frameRect(area, aspect), bw, bh)
        }
        Library.setCrops(albumId, imageId, work[Slot.TALL], work[Slot.WIDE])
        pop()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Crop & aspect ratio") },
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
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                SegmentedButton(
                    selected = slot == Slot.TALL,
                    onClick = { slot = Slot.TALL },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Cover screen") }
                SegmentedButton(
                    selected = slot == Slot.WIDE,
                    onClick = { slot = Slot.WIDE },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Main screen") }
            }
            Text(
                if (slot == Slot.TALL) "Used on tall screens: folded cover screen (and regular phones)."
                else "Used on wide screens: the unfolded main screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(Color(0xFF0C0809))
                    .onSizeChanged { area = it }
                    .pointerInput(slot, aspect, area, bmp) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            if (bmp == null) return@detectTransformGestures
                            val fr = frameRect(area, aspect)
                            val sMin = max(fr.width / bw, fr.height / bh)
                            val newScale = (st.scale * zoom).coerceIn(sMin, sMin * 10f)
                            val z = newScale / st.scale
                            st.ox = centroid.x - (centroid.x - st.ox) * z + pan.x
                            st.oy = centroid.y - (centroid.y - st.oy) * z + pan.y
                            st.scale = newScale
                            st.clamp(fr, bw, bh)
                            touched[slot] = true
                            work[slot] = st.toCrop(label, fr, bw, bh)
                        }
                    }
                    .pointerInput(slot, aspect, area, bmp) {
                        detectTapGestures(onDoubleTap = {
                            if (bmp != null) {
                                val fr = frameRect(area, aspect)
                                st.initFrom(null, fr, bw, bh)
                                touched[slot] = true
                                work[slot] = st.toCrop(label, fr, bw, bh)
                            }
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (bmp == null) {
                    CircularProgressIndicator()
                } else {
                    Canvas(Modifier.fillMaxSize()) {
                        drawImage(
                            image = bmp,
                            dstOffset = IntOffset(st.ox.roundToInt(), st.oy.roundToInt()),
                            dstSize = IntSize((bw * st.scale).roundToInt(), (bh * st.scale).roundToInt()),
                            filterQuality = FilterQuality.Medium,
                        )
                        val fr = frameRect(area, aspect)
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
                    }
                }
            }

            Text(
                "Pinch to zoom, drag to move. Double-tap to fit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 10.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RATIOS.forEach { r ->
                    FilterChip(
                        selected = label == r,
                        onClick = {
                            labels[slot] = r
                            touched[slot] = true
                        },
                        label = { Text(ratioLabel(r)) },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(onClick = {
                    work[slot] = null
                    labels[slot] = "screen"
                    touched[slot] = false
                    resetTick += 1
                }) { Text("No crop") }
                Spacer(Modifier.width(12.dp))
                Button(onClick = { save() }) { Text("Save") }
            }
        }
    }
}
