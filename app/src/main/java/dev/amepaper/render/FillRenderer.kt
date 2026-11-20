package dev.amepaper.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
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

/**
 * Fills the empty space around a zoomed-out picture. Used for the wallpaper itself, the
 * thumbnails and (via drawFillBase / drawPicture) the live preview in the crop editor, so all
 * three look the same.
 *
 * Modes: "blur" (blurred copy covering the frame), "extend" (edge pixels stretched outward),
 * "mirror" (picture reflected at its edges), "color" (gradient from the edge colours), "black".
 */
object FillRenderer {

    val MODES = listOf("extend", "mirror", "blur", "color", "black")

    fun label(mode: String): String = when (mode) {
        "extend" -> "Extend"
        "mirror" -> "Mirror"
        "blur" -> "Blur"
        "color" -> "Color"
        else -> "Black"
    }

    fun usesBlur(mode: String) = mode == "blur" || mode == "extend" || mode == "mirror"

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
     * Blurring happens on a small copy, so it stays fast at any size.
     */
    fun renderFill(c: Canvas, frame: RectF, src: Bitmap?, srcRect: Rect?, pic: RectF, mode: String, blur: Float, dim: Float) {
        val black = Paint().apply { color = Color.BLACK }
        c.drawRect(frame, black)
        if (src == null || mode == "black") return
        if (!usesBlur(mode) || blur < 0.01f) {
            drawFillBase(c, frame, src, srcRect, pic, mode)
        } else {
            val d = 4f
            val sw = max(8, (frame.width() / d).roundToInt())
            val sh = max(8, (frame.height() / d).roundToInt())
            val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            val sc = Canvas(small)
            val sr = srcRect ?: Rect(0, 0, src.width, src.height)
            val tw = max(2, (pic.width() / d).roundToInt())
            val th = max(2, (pic.height() / d).roundToInt())
            val part = Bitmap.createBitmap(src, sr.left, sr.top, max(1, sr.width()), max(1, sr.height()))
            val smallSrc = Bitmap.createScaledBitmap(part, min(tw, part.width), min(th, part.height), true)
            val smallPic = RectF(
                (pic.left - frame.left) / d, (pic.top - frame.top) / d,
                (pic.right - frame.left) / d, (pic.bottom - frame.top) / d,
            )
            drawFillBase(sc, RectF(0f, 0f, sw.toFloat(), sh.toFloat()), smallSrc, null, smallPic, mode)
            val r = blurRadius(blur, min(sw, sh).toFloat()).roundToInt().coerceAtLeast(1)
            val blurred = Blur.box(small, r, 3)
            c.drawBitmap(blurred, null, frame, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        if (dim > 0.01f) {
            c.drawRect(frame, Paint().apply { color = Color.argb((dim.coerceIn(0f, 1f) * 255).roundToInt(), 0, 0, 0) })
        }
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
        val f = max(1f, soft.coerceIn(0f, 1f) * 0.15f * min(pic.width(), pic.height()))
        val layer = c.saveLayer(pic, null)
        c.drawBitmap(src, srcRect, pic, paint)
        val mask = Paint(Paint.ANTI_ALIAS_FLAG)
        mask.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        val clear = 0x00000000
        val solid = 0xFF000000.toInt()
        if (pic.left > frame.left + 0.5f) {
            mask.shader = LinearGradient(pic.left, 0f, pic.left + f, 0f, clear, solid, Shader.TileMode.CLAMP)
            c.drawRect(pic, mask)
        }
        if (pic.right < frame.right - 0.5f) {
            mask.shader = LinearGradient(pic.right, 0f, pic.right - f, 0f, clear, solid, Shader.TileMode.CLAMP)
            c.drawRect(pic, mask)
        }
        if (pic.top > frame.top + 0.5f) {
            mask.shader = LinearGradient(0f, pic.top, 0f, pic.top + f, clear, solid, Shader.TileMode.CLAMP)
            c.drawRect(pic, mask)
        }
        if (pic.bottom < frame.bottom - 0.5f) {
            mask.shader = LinearGradient(0f, pic.bottom, 0f, pic.bottom - f, clear, solid, Shader.TileMode.CLAMP)
            c.drawRect(pic, mask)
        }
        c.restoreToCount(layer)
    }

    /**
     * Renders a crop of [src] (crop rectangle [cl],[ct],[cw],[ch] in [src] pixels, may reach
     * outside it) into a new [outW]×[outH] bitmap, with the chosen fill.
     */
    fun compose(
        src: Bitmap, cl: Float, ct: Float, cw: Float, ch: Float, outW: Int, outH: Int,
        mode: String, blur: Float, dim: Float, soft: Float,
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
        renderFill(c, frame, src, sr, pic, mode, blur, dim)
        drawPicture(c, src, sr, pic, frame, soft)
        return out
    }
}
