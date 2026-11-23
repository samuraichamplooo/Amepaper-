package dev.amepaper.ui

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.material3.Checkbox
import androidx.compose.ui.text.style.TextAlign
import dev.amepaper.data.Disp
import dev.amepaper.data.Orient
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.activity.compose.BackHandler
import androidx.compose.ui.text.style.TextOverflow
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
import dev.amepaper.ai.AiFill
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
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
import dev.amepaper.data.CropMath
import dev.amepaper.data.Placement
import dev.amepaper.data.Resolved
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
    val m = 24f
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
private class CropModel(private val sp: SharedPreferences, private val img: WallImage, start: Placement) {
    var bmp by mutableStateOf<ImageBitmap?>(null)
    var abmp by mutableStateOf<Bitmap?>(null)
    var area by mutableStateOf(IntSize.Zero)
    var placement by mutableStateOf(start)
    val pk: String get() = placement.key

    /** Framings this picture has of its own (saved, plus edits made here), by placement key. */
    val work = mutableStateMapOf<String, Crop>().apply { putAll(img.crops) }
    private val labels = mutableStateMapOf<String, String>()
    private val fills = mutableStateMapOf<String, String>()
    private val fillBlurs = mutableStateMapOf<String, Float>()
    private val fillDims = mutableStateMapOf<String, Float>()
    private val fillSofts = mutableStateMapOf<String, Float>()
    /** AI fills made here, by placement key (a null value means removed). */
    private val ais = mutableStateMapOf<String, AiFill.Result?>()
    /** Decoded AI fills for the preview, by file name. */
    val aiCache = mutableStateMapOf<String, Bitmap>()
    var freeFrame by mutableStateOf<Rect?>(null)

    private val devicePlacement = start

    /** The screens this edit applies to (any of the 8). The frame uses [placement]'s shape. */
    var selection by mutableStateOf(if (img.crops.size <= 1) Placement.ALL.toSet() else setOf(start))
        private set

    val allSelected: Boolean get() = selection.size == Placement.ALL.size

    fun isCustom(p: Placement): Boolean = work.containsKey(p.key)

    /** Which selected screen's shape to edit in: the one you're holding if possible. */
    private fun primaryOf(sel: Set<Placement>): Placement = when {
        devicePlacement in sel -> devicePlacement
        devicePlacement.otherScreen in sel -> devicePlacement.otherScreen
        else -> Placement.ALL.first { it in sel }
    }

    fun select(sel: Set<Placement>) {
        if (sel.isEmpty()) return
        selection = sel
        changePlacement(primaryOf(sel))
    }

    val selectionLabel: String
        get() {
            val sel = selection
            val first = sel.first()
            return when {
                allSelected -> "All screens"
                sel.size == 1 -> first.label
                sel.size == 2 && first.otherScreen in sel -> "Home & lock · ${first.disp.label} · ${first.orient.label}"
                else -> "${sel.size} screens"
            }
        }

    fun hintText(): String = when {
        allSelected -> "One framing, fitted to every screen"
        selection.size == 1 -> statusText()
        else -> "Editing in ${placement.disp.label} · ${placement.orient.label} shape, fitted to the others"
    }

    /** What to store: the edited framing on every selected screen (re-shaped where needed). */
    fun cropsToSave(): Map<String, Crop> {
        val mine = work[pk] ?: return work.toMap()
        if (allSelected) return mapOf(pk to mine)
        val out = LinkedHashMap<String, Crop>(work)
        for (q in selection) {
            out[q.key] = if (q.disp == placement.disp && q.orient == placement.orient) {
                mine
            } else {
                CropMath.adapt(mine, img.w.toFloat(), img.h.toFloat(), aspectFor(q))
            }
        }
        return out
    }

    /** Something was edited since opening (or the last save). */
    var dirty by mutableStateOf(false)

    var scale by mutableFloatStateOf(1f)
    var ox by mutableFloatStateOf(0f)
    var oy by mutableFloatStateOf(0f)

    val ready: Boolean get() = bmp != null && area.width > 0 && area.height > 0
    val bw: Float get() = (bmp?.width ?: 1).toFloat()
    val bh: Float get() = (bmp?.height ?: 1).toFloat()
    fun aspectFor(p: Placement): Float = Screens.aspect(sp, p)

    /** What a placement shows now: its own framing, or the one it follows. */
    fun resolved(p: Placement = placement): Resolved = CropMath.resolve(img, work, p, aspectFor(p))

    private val shown: Crop? get() = work[pk] ?: resolved().crop

    val label: String get() = labels[pk] ?: (work[pk]?.aspect ?: "screen")
    val fill: String get() = fills[pk] ?: (shown?.fill ?: "mirror")
    val fillBlur: Float get() = fillBlurs[pk] ?: (shown?.fillBlur ?: 0.65f)
    val fillDim: Float get() = fillDims[pk] ?: (shown?.fillDim ?: 0.2f)
    val fillSoft: Float get() = fillSofts[pk] ?: (shown?.fillSoft ?: 0.4f)
    val ai: AiFill.Result?
        get() = if (ais.containsKey(pk)) {
            ais[pk]
        } else {
            shown?.let { c -> c.aiFile?.let { AiFill.Result(it, c.aiL, c.aiT, c.aiR, c.aiB) } }
        }

    /** Use a finished AI fill on the screen it was made for. */
    fun setAi(r: AiFill.Result, key: String) {
        ais[key] = r
        fills[key] = "ai"
        if (key == pk) {
            commit()
        } else {
            work[key]?.let { work[key] = it.copy(fill = "ai", aiFile = r.file, aiL = r.l, aiT = r.t, aiR = r.r, aiB = r.b) }
            dirty = true
        }
    }

    /** The frame now reaches past what the AI filled (the rest falls back to Mirror). */
    fun aiShort(): Boolean {
        val a = ai ?: return false
        if (!ready) return false
        val c = crop()
        val e = 0.003f
        return c.l < a.l - e || c.t < a.t - e || c.r > a.r + e || c.b > a.b + e
    }

    /** CPU-rendered fill for the preview (matches the real wallpaper exactly). */
    var fillPreview by mutableStateOf<ImageBitmap?>(null)

    /** The part of the picture inside the frame (editor pixels) and where it sits on screen. */
    fun visible(fr: Rect): Pair<android.graphics.Rect, android.graphics.RectF> {
        val p = picRect()
        val sx0 = ((max(fr.left, p.left) - p.left) / scale).coerceIn(0f, bw - 1f)
        val sy0 = ((max(fr.top, p.top) - p.top) / scale).coerceIn(0f, bh - 1f)
        val sx1 = ((min(fr.right, p.right) - p.left) / scale).coerceIn(sx0 + 1f, bw)
        val sy1 = ((min(fr.bottom, p.bottom) - p.top) / scale).coerceIn(sy0 + 1f, bh)
        val src = android.graphics.Rect(sx0.roundToInt(), sy0.roundToInt(), sx1.roundToInt(), sy1.roundToInt())
        val vis = android.graphics.RectF(
            p.left + src.left * scale, p.top + src.top * scale,
            p.left + src.right * scale, p.top + src.bottom * scale,
        )
        return Pair(src, vis)
    }

    data class FillSpec(
        val frame: android.graphics.RectF,
        val srcRect: android.graphics.Rect,
        val visible: android.graphics.RectF,
        val mode: String,
        val blur: Float,
        val dim: Float,
        val src: Bitmap,
        val ai: Bitmap?,
        val aiDst: android.graphics.RectF?,
    )

    /** Everything the fill preview depends on, captured now (null when nothing to fill). */
    fun fillSpec(): FillSpec? {
        val a = abmp ?: return null
        if (!ready || !hasEmptySpace()) return null
        val fr = frame()
        val (sr, vis) = visible(fr)
        val ref = if (fill == "ai") ai else null
        val aiBmp = ref?.let { aiCache[it.file] }
        val p = picRect()
        val aiDst = if (ref != null && aiBmp != null) {
            android.graphics.RectF(
                p.left + ref.l * bw * scale, p.top + ref.t * bh * scale,
                p.left + ref.r * bw * scale, p.top + ref.b * bh * scale,
            )
        } else {
            null
        }
        return FillSpec(
            android.graphics.RectF(fr.left, fr.top, fr.right, fr.bottom), sr, vis, fill, fillBlur, fillDim, a,
            aiBmp, aiDst,
        )
    }

    fun statusText(): String {
        val r = resolved()
        return when {
            r.own -> "Custom framing for this screen only"
            r.source != null -> "Uses the ${r.source.label} framing until you change it"
            else -> "No framing yet; uses the Scaling setting"
        }
    }

    private fun aspectOf(l: String): Float {
        val a = when (l) {
            "screen" -> aspectFor(placement)
            "original" -> bw / bh
            "free" -> {
                val c = shown
                if (c != null) ((c.r - c.l) * bw) / max(0.0001f, (c.b - c.t) * bh) else aspectFor(placement)
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

    /** Place the picture according to this placement's framing (own or followed). */
    fun init() {
        if (!ready) return
        val fr = frame()
        val c = shown
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
            ai?.file, ai?.l ?: 0f, ai?.t ?: 0f, ai?.r ?: 1f, ai?.b ?: 1f,
        )
    }

    fun commit() {
        if (ready) {
            work[pk] = crop()
            dirty = true
        }
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
            labels[pk] = l
            return
        }
        commit()
        freeFrame = if (l == "free") frame() else null
        labels[pk] = l
        init()
        commit()
    }

    fun setFill(f: String) {
        // an AI fill looks best crisp and undarkened; the others keep their ambient defaults
        if (f == "ai" && fill != "ai") {
            fillBlurs[pk] = 0.1f
            fillDims[pk] = 0f
        } else if (f != "ai" && fill == "ai") {
            fillBlurs[pk] = 0.65f
            fillDims[pk] = 0.2f
        }
        fills[pk] = f
        commit()
    }

    fun setFillBlur(v: Float) { fillBlurs[pk] = v; commit() }
    fun setFillDim(v: Float) { fillDims[pk] = v; commit() }
    fun setFillSoft(v: Float) { fillSofts[pk] = v; commit() }

    /** The picture's rectangle on the canvas. */
    fun picRect(): Rect = Rect(ox, oy, ox + bw * scale, oy + bh * scale)

    /** True when the picture doesn't fill the frame (so the fill shows). */
    fun hasEmptySpace(): Boolean {
        if (!ready) return false
        val fr = frame()
        val p = picRect()
        return p.left > fr.left + 0.5f || p.top > fr.top + 0.5f || p.right < fr.right - 0.5f || p.bottom < fr.bottom - 0.5f
    }

    fun changePlacement(p: Placement) {
        if (p == placement) return
        placement = p
        freeFrame = null
        init()
    }

    /** Drop this screen's own framing so it follows the others again. */
    fun reset() {
        dirty = true
        if (allSelected) {
            work.clear()
            labels.clear()
            fills.clear()
            fillBlurs.clear()
            fillDims.clear()
            fillSofts.clear()
            ais.clear()
        } else {
            for (q in selection) {
                work.remove(q.key)
                labels.remove(q.key)
                fills.remove(q.key)
                fillBlurs.remove(q.key)
                fillDims.remove(q.key)
                fillSofts.remove(q.key)
                ais.remove(q.key)
            }
        }
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
fun CropScreen(albumId: String, imageId: String, startScreen: Screen, pop: () -> Unit) {
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
        CropModel(sp, img, Placement.forSize(startScreen, conf.screenWidthDp, conf.screenHeightDp))
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
    LaunchedEffect(model.placement, model.area, model.bmp) {
        model.freeFrame = null
        model.init()
    }
    // keep the fill preview in step with the edits (rendered off the main thread)
    LaunchedEffect(model) {
        snapshotFlow { model.fillSpec() }.collectLatest { spec ->
            if (spec == null) {
                model.fillPreview = null
                return@collectLatest
            }
            delay(20)
            val bmp = withContext(Dispatchers.Default) {
                FillRenderer.preview(
                    spec.src, spec.srcRect, spec.frame, spec.visible, spec.mode, spec.blur, spec.dim, spec.ai, spec.aiDst,
                )
            }
            model.fillPreview = bmp.asImageBitmap()
        }
    }

    // decode the AI fill for the preview when one is in use
    val aiFile = model.ai?.file
    LaunchedEffect(aiFile) {
        if (aiFile != null && !model.aiCache.containsKey(aiFile)) {
            val b = withContext(Dispatchers.IO) {
                val f = Library.aiFile(aiFile)
                if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
            }
            if (b != null) model.aiCache[aiFile] = b
        }
    }

    var aiStatus by remember { mutableStateOf<String?>(null) }
    var aiProgress by remember { mutableFloatStateOf(-1f) }
    var aiError by remember { mutableStateOf<String?>(null) }
    var aiErrorEngine by remember { mutableStateOf<AiFill.Engine?>(null) }
    var aiSetup by remember { mutableStateOf<AiFill.Engine?>(null) }
    var askDownload by remember { mutableStateOf(false) }

    fun generate(engine: AiFill.Engine) {
        val src = model.abmp ?: return
        if (aiStatus != null || !model.ready) return
        if (!model.hasEmptySpace()) {
            Toast.makeText(ctx, "Zoom out below 100% first to make room", Toast.LENGTH_SHORT).show()
            return
        }
        when (engine) {
            AiFill.Engine.DEVICE -> if (!AiFill.modelReady(ctx)) {
                askDownload = true
                return
            }
            AiFill.Engine.FLUX -> if (!AiFill.fluxReady(sp)) {
                aiSetup = engine
                return
            }
            AiFill.Engine.GEMINI -> if (!AiFill.geminiReady(sp)) {
                aiSetup = engine
                return
            }
        }
        model.commit()
        val c = model.crop()
        val key = model.pk
        aiStatus = when (engine) {
            AiFill.Engine.DEVICE -> "Filling on your phone…"
            AiFill.Engine.FLUX -> "Generating with FLUX… (about 5–15 s)"
            AiFill.Engine.GEMINI -> "Generating with Gemini… (about 10–20 s)"
        }
        scope.launch {
            try {
                val r = when (engine) {
                    AiFill.Engine.DEVICE -> AiFill.onDevice(ctx, src, c)
                    AiFill.Engine.FLUX -> AiFill.flux(sp, src, c)
                    AiFill.Engine.GEMINI -> AiFill.gemini(sp, src, c)
                }
                val b = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(Library.aiFile(r.file).absolutePath) }
                if (b != null) model.aiCache[r.file] = b
                model.setAi(r, key)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                aiErrorEngine = engine
                aiError = e.message ?: e.javaClass.simpleName
            } finally {
                aiStatus = null
            }
        }
    }

    fun downloadThenGenerate() {
        aiStatus = "Downloading the on-device model…"
        aiProgress = 0f
        scope.launch {
            var ok = false
            try {
                AiFill.downloadModel(ctx) { p -> aiProgress = p }
                ok = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                aiError = e.message ?: "Download failed"
            } finally {
                aiStatus = null
                aiProgress = -1f
            }
            if (ok) generate(AiFill.Engine.DEVICE)
        }
    }

    fun store() {
        model.commit()
        val crops: Map<String, Crop> = model.cropsToSave()
        Library.setCrops(albumId, imageId, crops)
        model.dirty = false
        // make the live wallpaper reload right away
        sp.edit().putLong(K.REFRESH.name, System.currentTimeMillis()).apply()
    }

    fun save() {
        store()
        val showing = Screen.entries.filter { Changer.current(sp, it)?.id == imageId }
        val msg = when {
            !isWallpaperActive(ctx) -> "Saved with this picture. Tap Set wallpaper to use it."
            showing.size == 2 -> "Saved and updated on your home & lock screens"
            showing.size == 1 -> "Saved and updated on your ${if (showing[0] == Screen.HOME) "home" else "lock"} screen"
            else -> "Saved with this picture. It'll look like this every time it comes up in rotation."
        }
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        pop()
    }

    fun applyTo(screens: List<Screen>) {
        store()
        applyWallpaper(ctx, albumId, imageId, screens)
        pop()
    }

    var fullPreview by remember { mutableStateOf(false) }
    var aiDialog by remember { mutableStateOf(false) }

    // Leaving the editor keeps your edits (they're used whenever this picture comes up again).
    fun leave() {
        if (model.dirty && model.ready) {
            store()
            Toast.makeText(ctx, "Edits saved with this picture", Toast.LENGTH_SHORT).show()
        }
        pop()
    }
    BackHandler(enabled = !fullPreview) { leave() }
    BackHandler(enabled = fullPreview) { fullPreview = false }

    fun runAi() {
        aiBusy = true
        scope.launch {
            model.commit()
            val c = model.work[model.pk] ?: model.crop()
            val uri = withContext(Dispatchers.IO) { AiHandoff.export(ctx, img, c) }
            aiBusy = false
            if (uri != null) {
                AiHandoff.openEditor(ctx, uri)
            } else {
                Toast.makeText(ctx, "Couldn't save the picture for editing", Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (aiDialog) {
        AlertDialog(
            onDismissRequest = { aiDialog = false },
            title = { Text("Fill in another app") },
            text = {
                Text(
                    "Saves this framing (empty space in black) to Pictures/Amepaper and opens it in your photo editor, " +
                        "e.g. Galaxy AI Generative edit or Google Photos, to fill it with new content. " +
                        "When you're done, come back and tap Use edited picture.",
                )
            },
            confirmButton = {
                TextButton(enabled = !aiBusy && model.ready && AiHandoff.supported(), onClick = {
                    aiDialog = false
                    runAi()
                }) { Text("Open in AI editor") }
            },
            dismissButton = {
                TextButton(onClick = {
                    aiDialog = false
                    replacePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Use edited picture") }
            },
        )
    }

    if (askDownload) {
        AlertDialog(
            onDismissRequest = { askDownload = false },
            title = { Text("Download on-device AI?") },
            text = {
                Text(
                    "A one-time ${AiFill.MODEL_MB} MB download (MI-GAN, MIT license). After that it fills on your phone, " +
                        "offline, in about a second. For richer results use FLUX (free) or Gemini.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askDownload = false
                    downloadThenGenerate()
                }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { askDownload = false }) { Text("Cancel") } },
        )
    }
    aiSetup?.let { engine ->
        AiSetupDialog(engine, confirm = "Generate", onDone = {
            aiSetup = null
            val ready = if (engine == AiFill.Engine.FLUX) AiFill.fluxReady(sp) else AiFill.geminiReady(sp)
            if (ready) generate(engine)
        })
    }
    aiError?.let { msg ->
        AlertDialog(
            onDismissRequest = { aiError = null },
            title = { Text("AI fill didn't work") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { aiError = null }) { Text("OK") } },
            dismissButton = {
                // one tap to the free offline engine when a cloud one fails
                if (aiErrorEngine == AiFill.Engine.FLUX || aiErrorEngine == AiFill.Engine.GEMINI) {
                    TextButton(onClick = {
                        aiError = null
                        generate(AiFill.Engine.DEVICE)
                    }) { Text("Try on-device") }
                }
            },
        )
    }

    val panelMax = (conf.screenHeightDp * 0.25f).dp.coerceIn(110.dp, 230.dp)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (fullPreview) "Preview" else "Edit wallpaper") },
                navigationIcon = {
                    IconButton(onClick = {
                        val wasFull = fullPreview
                        fullPreview = false
                        if (!wasFull) leave()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!fullPreview) TextButton(onClick = { model.reset() }) { Text("Reset") }
                    IconButton(onClick = { fullPreview = !fullPreview }) {
                        Icon(
                            painterResource(if (fullPreview) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen),
                            contentDescription = if (fullPreview) "Show controls" else "Bigger preview",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!fullPreview) EditForBar(model)

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

            // ---- tools (capped so the preview keeps most of the screen)
            if (!fullPreview) {
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
                Column(Modifier.heightIn(max = panelMax).verticalScroll(rememberScrollState())) {
                    when (tool) {
                        0 -> ZoomPanel(model)
                        1 -> ShapePanel(model)
                        else -> FillPanel(
                            model,
                            aiStatus = aiStatus,
                            aiProgress = aiProgress,
                            onGenerate = { generate(it) },
                            onHandoff = { aiDialog = true },
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(onClick = { save() }, modifier = Modifier.weight(1f)) { Text("Save") }
                    SetWallpaperButton(
                        enabled = model.ready,
                        modifier = Modifier.weight(1.6f),
                        onBoth = { applyTo(listOf(Screen.HOME, Screen.LOCK)) },
                        onHome = { applyTo(listOf(Screen.HOME)) },
                        onLock = { applyTo(listOf(Screen.LOCK)) },
                    )
                }
            }
        }
    }
}

/** "Edit for: …" button; opens a checklist of the 8 screens. */
@Composable
private fun EditForBar(model: CropModel) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Edit for",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 10.dp),
            )
            FilledTonalButton(
                onClick = { open = true },
                contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Text(model.selectionLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        }
        Text(
            model.hintText(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
    if (open) ScreenPicker(model) { open = false }
}

private val SHAPES = listOf(
    Disp.COVER to Orient.PORTRAIT,
    Disp.COVER to Orient.LANDSCAPE,
    Disp.MAIN to Orient.PORTRAIT,
    Disp.MAIN to Orient.LANDSCAPE,
)

/** Checklist: rows are screen shapes, columns Home / Lock. Tap a row name to tick both. */
@Composable
private fun ScreenPicker(model: CropModel, onClose: () -> Unit) {
    var sel by remember { mutableStateOf(model.selection) }
    val all = Placement.ALL.toSet()
    fun toggle(p: Placement) {
        sel = if (p in sel) sel - p else sel + p
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Edit which screens?") },
        text = {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { sel = if (sel == all) setOf(model.placement) else all },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = sel == all, onCheckedChange = { sel = if (it) all else setOf(model.placement) })
                    Text("All screens", style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    for (scr in listOf(Screen.HOME, Screen.LOCK)) {
                        Text(
                            if (scr == Screen.HOME) "Home" else "Lock",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .width(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    val col = all.filter { it.screen == scr }.toSet()
                                    sel = if (sel.containsAll(col)) sel - col else sel + col
                                }
                                .padding(vertical = 6.dp),
                        )
                    }
                }
                SHAPES.forEach { (d, o) ->
                    val home = Placement(Screen.HOME, d, o)
                    val lock = Placement(Screen.LOCK, d, o)
                    val custom = listOfNotNull(
                        if (model.isCustom(home)) "home" else null,
                        if (model.isCustom(lock)) "lock" else null,
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    val both = setOf(home, lock)
                                    sel = if (sel.containsAll(both)) sel - both else sel + both
                                }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                        ) {
                            Text("${d.label} · ${o.label}", style = MaterialTheme.typography.bodyLarge)
                            if (custom.isNotEmpty()) {
                                Text(
                                    "Custom on " + custom.joinToString(" & "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                            Checkbox(checked = home in sel, onCheckedChange = { toggle(home) })
                        }
                        Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                            Checkbox(checked = lock in sel, onCheckedChange = { toggle(lock) })
                        }
                    }
                }
                Text(
                    "Tap a row name to tick home and lock together.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = sel.isNotEmpty(), onClick = {
                model.select(sel)
                onClose()
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/** "Set wallpaper" sets home & lock in one tap; the ▾ part offers one screen only. */
@Composable
private fun SetWallpaperButton(
    enabled: Boolean,
    modifier: Modifier,
    onBoth: () -> Unit,
    onHome: () -> Unit,
    onLock: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(modifier) {
        Button(
            onClick = onBoth,
            enabled = enabled,
            shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp, topEnd = 4.dp, bottomEnd = 4.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text("Set wallpaper", maxLines = 1)
        }
        Spacer(Modifier.width(2.dp))
        Box {
            Button(
                onClick = { open = true },
                enabled = enabled,
                shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 20.dp, bottomEnd = 20.dp),
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) {
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "More options")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("Home & lock screens") }, onClick = { open = false; onBoth() })
                DropdownMenuItem(text = { Text("Home screen only") }, onClick = { open = false; onHome() })
                DropdownMenuItem(text = { Text("Lock screen only") }, onClick = { open = false; onLock() })
            }
        }
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
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun FillPanel(
    model: CropModel,
    aiStatus: String?,
    aiProgress: Float,
    onGenerate: (AiFill.Engine) -> Unit,
    onHandoff: () -> Unit,
) {
    var which by remember { mutableIntStateOf(0) } // 0 blur, 1 darken, 2 blend edges
    Column {
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
        if (model.fill == "ai") {
            AiControls(model, aiStatus, aiProgress, onGenerate, onHandoff)
        } else if (!model.hasEmptySpace()) {
            Text(
                "Zoom out below 100% to make room for the fill.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        val names = listOf(if (model.fill == "ai") "Soften" else "Blur", "Darken", "Blend edges")
        val options = buildList {
            if (FillRenderer.usesBlur(model.fill)) add(0)
            if (model.fill != "black") add(1)
            add(2)
        }
        val sel = if (which in options) which else options.first()
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            options.forEachIndexed { i, o ->
                SegmentedButton(
                    selected = sel == o,
                    onClick = { which = o },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(names[o], maxLines = 1) }
            }
        }
        val value = when (sel) {
            0 -> model.fillBlur
            1 -> model.fillDim
            else -> model.fillSoft
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = value,
                onValueChange = {
                    when (sel) {
                        0 -> model.setFillBlur(it)
                        1 -> model.setFillDim(it)
                        else -> model.setFillSoft(it)
                    }
                },
                modifier = Modifier.weight(1f),
            )
            Text(
                "${(value * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(44.dp).padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun AiControls(
    model: CropModel,
    status: String?,
    progress: Float,
    onGenerate: (AiFill.Engine) -> Unit,
    onHandoff: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        if (status != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
            }
            if (progress >= 0f) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        } else {
            Text(
                when {
                    !model.hasEmptySpace() -> "Zoom out below 100% first, then generate."
                    model.ai == null -> "Generate new scenery for the empty space. Your picture stays untouched on top."
                    model.aiShort() -> "The frame now goes past the AI fill (the rest uses Mirror). Generate again to cover it."
                    else -> "AI fill applied. Not quite right? Generate again for a new take."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val enabled = model.hasEmptySpace()
            val pad = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = { onGenerate(AiFill.Engine.FLUX) }, enabled = enabled, contentPadding = pad) {
                    Text("FLUX · free")
                }
                OutlinedButton(onClick = { onGenerate(AiFill.Engine.DEVICE) }, enabled = enabled, contentPadding = pad) {
                    Text("On-device")
                }
                OutlinedButton(onClick = { onGenerate(AiFill.Engine.GEMINI) }, enabled = enabled, contentPadding = pad) {
                    Text("Gemini")
                }
                TextButton(onClick = onHandoff, contentPadding = pad) { Text("Other app…") }
            }
        }
    }
}

@Composable
private fun MiniSlider(title: String, value: Float, onChange: (Float) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
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

    // the fill (exact preview), the picture with blended edges, the frame and handles
    Canvas(Modifier.fillMaxSize()) {
        val fr = m.frame()
        drawRect(Color.Black, fr.topLeft, fr.size)
        val fp = m.fillPreview
        if (fp != null && m.fill != "black") {
            drawImage(
                image = fp,
                dstOffset = IntOffset(fr.left.roundToInt(), fr.top.roundToInt()),
                dstSize = IntSize(fr.width.roundToInt(), fr.height.roundToInt()),
                filterQuality = FilterQuality.Low,
            )
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
