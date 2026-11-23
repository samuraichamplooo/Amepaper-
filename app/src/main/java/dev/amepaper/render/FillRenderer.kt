package dev.amepaper.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Fills the empty space around a zoomed-out picture. Used for the wallpaper itself, the
 * thumbnails and (via drawFillBase / drawPicture) the live preview in the crop editor, so all
 * three look the same.
 *
 * Modes: "blur" (blurred copy covering the frame), "extend" (edge pixels stretched outward),
 * "mirror" (picture reflected at its edges), "color" (gradient from the edge colours), "black".
 */
object FillRenderer {

    val MODES = listOf("mirror", "extend", "blur", "color", "black", "ai")

    fun label(mode: String): String = when (mode) {
        "extend" -> "Extend"
        "mirror" -> "Mirror"
        "blur" -> "Blur"
        "color" -> "Color"
        "ai" -> "AI ✦"
        else -> "Black"
    }

    fun usesBlur(mode: String) = mode == "blur" || mode == "extend" || mode == "mirror" || mode == "ai"

    /** Blur radius as a fraction of the frame's short side (box-blur radius ≈ Gaussian sigma). */
    fun blurRadius(blur: Float, frameShortSide: Float): Float = blur.coerceIn(0f, 1f) * 0.06f * frameShortSide

    /**
     * Draws the un-blurred fill. [src] is the visible part of the picture, drawn at [pic]
     * (canvas coordinates); [frame] is the crop frame on the canvas.
     */
    fun drawFillBase(c: Canvas, frame: RectF, src: Bitmap, srcRect: Rect?, pic: RectF, mode: String) {
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val sr = srcRect ?: Rect(0, 0, src.width, src.height)
        when (mode) {
            "extend", "mirror" -> {
                val tile = if (mode == "mirror") Shader.TileMode.MIRROR else Shader.TileMode.CLAMP
                val part = if (sr.left == 0 && sr.top == 0 && sr.width() == src.width && sr.height() == src.height) {
                    src
                } else {
                    Bitmap.createBitmap(src, sr.left, sr.top, max(1, sr.width()), max(1, sr.height()))
                }
                val shader = BitmapShader(part, tile, tile)
                val m = Matrix()
                m.setScale(pic.width() / part.width, pic.height() / part.height)
                m.postTranslate(pic.left, pic.top)
                shader.setLocalMatrix(m)
                paint.shader = shader
                c.drawRect(frame, paint)
            }
            "blur" -> {
                val s = max(frame.width() / sr.width(), frame.height() / sr.height())
                val w = sr.width() * s
                val h = sr.height() * s
                val cx = frame.centerX()
                val cy = frame.centerY()
                c.drawBitmap(src, sr, RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f), paint)
            }
            "color" -> {
                val colors = edgeColors(src, sr)
                val gapX = max(0f, pic.left - frame.left) + max(0f, frame.right - pic.right)
                val gapY = max(0f, pic.top - frame.top) + max(0f, frame.bottom - pic.bottom)
                val shader = if (gapX >= gapY) {
                    LinearGradient(
                        frame.left, 0f, frame.right, 0f,
                        intArrayOf(colors[2], colors[2], colors[3], colors[3]),
                        floatArrayOf(0f, frac(pic.left, frame.left, frame.width()), frac(pic.right, frame.left, frame.width()), 1f),
                        Shader.TileMode.CLAMP,
                    )
                } else {
                    LinearGradient(
                        0f, frame.top, 0f, frame.bottom,
                        intArrayOf(colors[0], colors[0], colors[1], colors[1]),
                        floatArrayOf(0f, frac(pic.top, frame.top, frame.height()), frac(pic.bottom, frame.top, frame.height()), 1f),
                        Shader.TileMode.CLAMP,
                    )
                }
                paint.shader = shader
                c.drawRect(frame, paint)
            }
            else -> {
                paint.color = Color.BLACK
                c.drawRect(frame, paint)
            }
        }
    }

    private fun frac(v: Float, start: Float, len: Float): Float = ((v - start) / max(1f, len)).coerceIn(0f, 1f)

    /** Average colour of the top, bottom, left and right edges of [sr] in [src]. */
    fun edgeColors(src: Bitmap, sr: Rect): IntArray {
        val part = Bitmap.createBitmap(src, sr.left, sr.top, max(1, sr.width()), max(1, sr.height()))
        val tiny = Bitmap.createScaledBitmap(part, 12, 12, true)
        fun avg(xs: IntRange, ys: IntRange): Int {
            var r = 0; var g = 0; var b = 0; var n = 0
            for (y in ys) for (x in xs) {
                val p = tiny.getPixel(x, y)
                r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++
            }
            return Color.rgb(r / n, g / n, b / n)
        }
        return intArrayOf(
            avg(0..11, 0..1),   // top
            avg(0..11, 10..11), // bottom
            avg(0..1, 0..11),   // left
            avg(10..11, 0..11), // right
        )
    }

    /**
     * Draws the whole fill (blurred if needed, then darkened) into [frame] on [c].
     * Work happens on a small copy, so it stays fast at any size. Extend and Mirror blur
     * progressively: lightly next to the picture, more the farther away, so the fill flows out of
     * the picture's edges. Blur gives a zoomed, softly blurred "ambient" backdrop.
     */
    fun renderFill(
        c: Canvas, frame: RectF, src: Bitmap?, srcRect: Rect?, pic: RectF, mode: String, blur: Float, dim: Float,
        ai: Bitmap? = null, aiDst: RectF? = null,
    ) {
        if (mode == "ai") {
            // AI fill: a blurred mirror underneath (for any part the AI image doesn't cover), then the AI image
            renderFill(c, frame, src, srcRect, pic, "mirror", max(blur, 0.6f), 0f)
            if (ai != null && aiDst != null) {
                val img = if (blur > 0.02f) softenAi(ai, blur) else ai
                c.save()
                c.clipRect(frame)
                c.drawBitmap(img, null, aiDst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                c.restore()
            }
            if (dim > 0.01f) {
                c.drawRect(frame, Paint().apply { color = Color.argb((dim.coerceIn(0f, 1f) * 255).roundToInt(), 0, 0, 0) })
            }
            return
        }
        val black = Paint().apply { color = Color.BLACK }
        c.drawRect(frame, black)
        if (src == null || mode == "black") return
        if (mode == "color" || (mode != "blur" && blur < 0.01f)) {
            drawFillBase(c, frame, src, srcRect, pic, mode)
        } else {
            val d = 3f
            val sw = max(8, (frame.width() / d).roundToInt())
            val sh = max(8, (frame.height() / d).roundToInt())
            val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            val sc = Canvas(small)
            val sr = srcRect ?: Rect(0, 0, src.width, src.height)
            val part = Bitmap.createBitmap(src, sr.left, sr.top, max(1, sr.width()), max(1, sr.height()))
            val tw = max(2, (pic.width() / d).roundToInt())
            val th = max(2, (pic.height() / d).roundToInt())
            val smallSrc = Bitmap.createScaledBitmap(part, min(tw, part.width), min(th, part.height), true)
            val smallPic = RectF(
                (pic.left - frame.left) / d, (pic.top - frame.top) / d,
                (pic.right - frame.left) / d, (pic.bottom - frame.top) / d,
            )
            if (mode == "blur") {
                // the picture blown up to cover the frame (a touch extra so edges stay rich)
                val z = 0.06f
                drawFillBase(sc, RectF(-sw * z, -sh * z, sw * (1 + z), sh * (1 + z)), smallSrc, null, smallPic, "blur")
            } else {
                drawFillBase(sc, RectF(0f, 0f, sw.toFloat(), sh.toFloat()), smallSrc, null, smallPic, mode)
            }
            val short = min(sw, sh).toFloat()
            val far = max(1, blurRadius(max(blur, 0.02f), short).roundToInt())
            val result = when {
                blur < 0.01f -> small
                mode == "blur" -> Blur.box(small, far, 3)
                else -> {
                    // Extend needs more blur near the picture to hide stretched-pixel streaks
                    val near = max(1, (far * if (mode == "extend") 0.75f else 0.5f).roundToInt())
                    progressive(Blur.box(small, near, 3), Blur.box(small, far, 3), smallPic, 0.3f * short)
                }
            }
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.18f) })
            c.drawBitmap(result, null, frame, paint)
        }
        if (dim > 0.01f) {
            c.drawRect(frame, Paint().apply { color = Color.argb((dim.coerceIn(0f, 1f) * 255).roundToInt(), 0, 0, 0) })
        }
    }

    /** Mixes [near] into [far] by distance from [pic]: closer to the picture stays sharper. */
    private fun progressive(near: Bitmap, far: Bitmap, pic: RectF, falloff: Float): Bitmap {
        val w = near.width
        val h = near.height
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        near.getPixels(a, 0, w, 0, 0, w, h)
        far.getPixels(b, 0, w, 0, 0, w, h)
        val inv = 1f / max(1f, falloff)
        for (y in 0 until h) {
            val fy = y + 0.5f
            val dy = max(max(pic.top - fy, fy - pic.bottom), 0f)
            val row = y * w
            for (x in 0 until w) {
                val fx = x + 0.5f
                val dx = max(max(pic.left - fx, fx - pic.right), 0f)
                var t = sqrt(dx * dx + dy * dy) * inv
                if (t > 1f) t = 1f
                t = t * t * (3f - 2f * t)
                val p = a[row + x]
                val q = b[row + x]
                val r = ((p shr 16 and 0xff) + ((q shr 16 and 0xff) - (p shr 16 and 0xff)) * t).toInt()
                val g = ((p shr 8 and 0xff) + ((q shr 8 and 0xff) - (p shr 8 and 0xff)) * t).toInt()
                val bl = ((p and 0xff) + ((q and 0xff) - (p and 0xff)) * t).toInt()
                a[row + x] = (0xff shl 24) or (r shl 16) or (g shl 8) or bl
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(a, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * The fill alone for the editor preview: [frame] and [pic] in editor pixels, [src] the
     * editor's copy of the picture with [srcRect] the part inside the frame. Rendered small.
     */
    fun preview(
        src: Bitmap, srcRect: Rect, frame: RectF, pic: RectF, mode: String, blur: Float, dim: Float,
        ai: Bitmap? = null, aiDst: RectF? = null,
    ): Bitmap {
        val k = min(1f, 720f / max(1f, max(frame.width(), frame.height())))
        val ow = max(8, (frame.width() * k).roundToInt())
        val oh = max(8, (frame.height() * k).roundToInt())
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val p = RectF(
            (pic.left - frame.left) * k, (pic.top - frame.top) * k,
            (pic.right - frame.left) * k, (pic.bottom - frame.top) * k,
        )
        val ad = aiDst?.let {
            RectF((it.left - frame.left) * k, (it.top - frame.top) * k, (it.right - frame.left) * k, (it.bottom - frame.top) * k)
        }
        renderFill(Canvas(out), RectF(0f, 0f, ow.toFloat(), oh.toFloat()), src, srcRect, p, mode, blur, dim, ai, ad)
        return out
    }

    /** Gentle softening for an AI fill (the slider's "Soften"); drawn scaled, so no need to upsize. */
    private fun softenAi(src: Bitmap, blur: Float): Bitmap {
        val f = 1f + blur.coerceIn(0f, 1f) * 5f
        val sw = max(8, (src.width / f).roundToInt())
        val sh = max(8, (src.height / f).roundToInt())
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        return Blur.box(small, 1 + (blur * 2f).roundToInt(), 2)
    }

    /** A little film grain over the fill so smooth dark gradients don't show bands. */
    fun grain(bmp: Bitmap, amount: Int = 3) {
        if (!bmp.isMutable) return
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var seed = 0x2545F491
        val span = 2 * amount + 1
        for (i in px.indices) {
            seed = seed * 1664525 + 1013904223
            val n = ((seed ushr 16) and 0x7fff) % span - amount
            val p = px[i]
            val r = ((p shr 16 and 0xff) + n).coerceIn(0, 255)
            val g = ((p shr 8 and 0xff) + n).coerceIn(0, 255)
            val b = ((p and 0xff) + n).coerceIn(0, 255)
            px[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    /**
     * Draws the picture with its edges faded into the fill wherever the fill shows
     * ([soft] 0..1 sets how wide the fade is).
     */
    fun drawPicture(c: Canvas, src: Bitmap, srcRect: Rect?, pic: RectF, frame: RectF, soft: Float) {
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (soft < 0.01f) {
            c.drawBitmap(src, srcRect, pic, paint)
            return
        }
        val f = max(1f, soft.coerceIn(0f, 1f) * 0.24f * min(pic.width(), pic.height()))
        val layer = c.saveLayer(pic, null)
        c.drawBitmap(src, srcRect, pic, paint)
        val mask = Paint(Paint.ANTI_ALIAS_FLAG)
        mask.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        // smoothstep-shaped fade (no visible start or end of the ramp)
        val colors = intArrayOf(0x00000000, 0x28000000, 0x80000000.toInt(), 0xD7000000.toInt(), 0xFF000000.toInt())
        val stops = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        fun fade(x0: Float, y0: Float, x1: Float, y1: Float) {
            mask.shader = LinearGradient(x0, y0, x1, y1, colors, stops, Shader.TileMode.CLAMP)
            c.drawRect(pic, mask)
        }
        if (pic.left > frame.left + 0.5f) fade(pic.left, 0f, pic.left + f, 0f)
        if (pic.right < frame.right - 0.5f) fade(pic.right, 0f, pic.right - f, 0f)
        if (pic.top > frame.top + 0.5f) fade(0f, pic.top, 0f, pic.top + f)
        if (pic.bottom < frame.bottom - 0.5f) fade(0f, pic.bottom, 0f, pic.bottom - f)
        c.restoreToCount(layer)
    }

    /**
     * Renders a crop of [src] (crop rectangle [cl],[ct],[cw],[ch] in [src] pixels, may reach
     * outside it) into a new [outW]×[outH] bitmap, with the chosen fill.
     */
    fun compose(
        src: Bitmap, cl: Float, ct: Float, cw: Float, ch: Float, outW: Int, outH: Int,
        mode: String, blur: Float, dim: Float, soft: Float,
        ai: Bitmap? = null, aiDst: RectF? = null,
    ): Bitmap {
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val kx = outW / cw
        val ky = outH / ch
        val frame = RectF(0f, 0f, outW.toFloat(), outH.toFloat())
        val x0 = max(0f, cl)
        val y0 = max(0f, ct)
        val x1 = min(src.width.toFloat(), cl + cw)
        val y1 = min(src.height.toFloat(), ct + ch)
        if (x1 - x0 < 1f || y1 - y0 < 1f) {
            c.drawColor(Color.BLACK)
            return out
        }
        val sr = Rect(x0.roundToInt(), y0.roundToInt(), max(x0.roundToInt() + 1, x1.roundToInt()), max(y0.roundToInt() + 1, y1.roundToInt()))
        val pic = RectF((sr.left - cl) * kx, (sr.top - ct) * ky, (sr.right - cl) * kx, (sr.bottom - ct) * ky)
        renderFill(c, frame, src, sr, pic, mode, blur, dim, ai, aiDst)
        if (mode != "black" && mode != "ai") grain(out)
        drawPicture(c, src, sr, pic, frame, soft)
        return out
    }
}
