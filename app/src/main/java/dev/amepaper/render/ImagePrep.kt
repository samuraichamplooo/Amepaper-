package dev.amepaper.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import dev.amepaper.data.Crop
import dev.amepaper.data.Library
import dev.amepaper.data.ScaleMode
import dev.amepaper.data.Screen
import dev.amepaper.data.WallImage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A wallpaper ready for the GPU: sharp + fogged copies of the cropped region. */
class Prepared(
    val key: String,
    val screen: Screen,
    val sharp: Bitmap,
    val mid: Bitmap,
    val fog: Bitmap,
    /** Size of the cropped region in image pixels (used for "None" scaling and aspect). */
    val srcW: Float,
    val srcH: Float,
    val mode: ScaleMode,
)

object ImagePrep {
    private const val MAX_TEX = 4096f

    fun keyFor(img: WallImage, crop: Crop?, screen: Screen, w: Int, h: Int, mode: ScaleMode, blur: Float): String =
        "${img.id}|${img.file}|$crop|${w}x$h|$mode|$blur|$screen"

    fun placeholderKey(screen: Screen, w: Int, h: Int): String = "placeholder|${w}x$h|$screen"

    fun prepare(img: WallImage, crop: Crop?, screen: Screen, w: Int, h: Int, mode: ScaleMode, blur: Float, key: String): Prepared? {
        val file = Library.file(img)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val iw = bounds.outWidth
        val ih = bounds.outHeight
        if (iw <= 0 || ih <= 0) return null

        val c = crop
        // Crop rectangle in image pixels; may reach outside the picture when zoomed out.
        val cl = (c?.l ?: 0f) * iw
        val ct = (c?.t ?: 0f) * ih
        val cr = (c?.r ?: 1f) * iw
        val cb = (c?.b ?: 1f) * ih
        val cw = max(1f, cr - cl)
        val ch = max(1f, cb - ct)

        val sw = w.toFloat()
        val sh = h.toFloat()
        val target: Pair<Float, Float> = when (mode) {
            ScaleMode.FILL -> { val s = max(sw / cw, sh / ch); Pair(cw * s, ch * s) }
            ScaleMode.FIT -> { val s = min(sw / cw, sh / ch); Pair(cw * s, ch * s) }
            ScaleMode.STRETCH -> Pair(sw, sh)
            ScaleMode.NONE -> Pair(cw, ch)
        }
        // Never upscale on the CPU (the GPU does that) and stay under the texture limit.
        val tw = min(target.first, cw)
        val th = min(target.second, ch)
        val k = min(1f, MAX_TEX / max(tw, th))
        val outW = max(1, (tw * k).roundToInt())
        val outH = max(1, (th * k).roundToInt())

        var sharp = if (c == null || c.inside) {
            val l = cl.roundToInt().coerceIn(0, iw - 1)
            val t = ct.roundToInt().coerceIn(0, ih - 1)
            val r = cr.roundToInt().coerceIn(l + 1, iw)
            val b = cb.roundToInt().coerceIn(t + 1, ih)
            val region = decodeRegion(file.absolutePath, Rect(l, t, r, b), sampleOpts(r - l, b - t, outW, outH))
                ?: return null
            if (region.width != outW || region.height != outH) {
                Bitmap.createScaledBitmap(region, outW, outH, true)
            } else {
                region
            }
        } else {
            composite(file.absolutePath, iw, ih, cl, ct, cw, ch, outW, outH, c) ?: return null
        }
        if (blur > 0.01f) sharp = Blur.soften(sharp, blur)
        val mid = Blur.mid(sharp)
        val fog = Blur.fog(mid)
        return Prepared(key, screen, sharp, mid, fog, cw, ch, mode)
    }

    private fun sampleOpts(rw: Int, rh: Int, needW: Int, needH: Int): BitmapFactory.Options {
        var sample = 1
        while (rw / (sample * 2) >= needW && rh / (sample * 2) >= needH) sample *= 2
        return BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    }

    /** A zoomed-out crop: the picture sits inside the frame, empty space gets the fill. */
    private fun composite(
        path: String, iw: Int, ih: Int,
        cl: Float, ct: Float, cw: Float, ch: Float,
        outW: Int, outH: Int, crop: Crop,
    ): Bitmap? {
        val x0 = max(0f, cl).roundToInt().coerceIn(0, iw - 1)
        val y0 = max(0f, ct).roundToInt().coerceIn(0, ih - 1)
        val x1 = min(iw.toFloat(), cl + cw).roundToInt().coerceIn(x0 + 1, iw)
        val y1 = min(ih.toFloat(), ct + ch).roundToInt().coerceIn(y0 + 1, ih)
        val kx = outW / cw
        val ky = outH / ch
        val needW = max(1, ((x1 - x0) * kx).roundToInt())
        val needH = max(1, ((y1 - y0) * ky).roundToInt())
        val region = decodeRegion(path, Rect(x0, y0, x1, y1), sampleOpts(x1 - x0, y1 - y0, needW, needH)) ?: return null
        // crop rectangle in the decoded region's own pixels
        val sx = region.width.toFloat() / (x1 - x0)
        val sy = region.height.toFloat() / (y1 - y0)
        var ai: Bitmap? = null
        var aiDst: RectF? = null
        if (crop.fill == "ai" && crop.aiFile != null) {
            val f = Library.aiFile(crop.aiFile)
            if (f.exists()) {
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, b)
                ai = BitmapFactory.decodeFile(f.absolutePath, sampleOpts(b.outWidth, b.outHeight, outW, outH))
                aiDst = RectF(
                    (crop.aiL * iw - cl) * kx, (crop.aiT * ih - ct) * ky,
                    (crop.aiR * iw - cl) * kx, (crop.aiB * ih - ct) * ky,
                )
            }
        }
        return FillRenderer.compose(
            region,
            (cl - x0) * sx, (ct - y0) * sy, cw * sx, ch * sy,
            outW, outH, crop.fill, crop.fillBlur, crop.fillDim, crop.fillSoft,
            ai, aiDst,
        )
    }

    private fun decodeRegion(path: String, rect: Rect, opts: BitmapFactory.Options): Bitmap? {
        try {
            @Suppress("DEPRECATION")
            val dec: BitmapRegionDecoder? = BitmapRegionDecoder.newInstance(path, false)
            if (dec != null) {
                try {
                    val bmp: Bitmap? = dec.decodeRegion(rect, opts)
                    if (bmp != null) return bmp
                } finally {
                    dec.recycle()
                }
            }
        } catch (e: Exception) {
            // fall through to a full decode
        }
        val full = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = opts.inSampleSize })
            ?: return null
        val s = opts.inSampleSize.toFloat()
        val x = (rect.left / s).roundToInt().coerceIn(0, full.width - 1)
        val y = (rect.top / s).roundToInt().coerceIn(0, full.height - 1)
        val rw = (rect.width() / s).roundToInt().coerceIn(1, full.width - x)
        val rh = (rect.height() / s).roundToInt().coerceIn(1, full.height - y)
        return Bitmap.createBitmap(full, x, y, rw, rh)
    }

    /** Shown when an album has no wallpapers yet: a soft dusk gradient. */
    fun placeholder(screen: Screen, w: Int, h: Int, key: String): Prepared {
        val pw = max(16, w / 2)
        val ph = max(16, h / 2)
        val bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = Paint()
        p.shader = LinearGradient(
            0f, 0f, pw * 0.3f, ph.toFloat(),
            intArrayOf(0xFF1B2A41.toInt(), 0xFF4A2340.toInt(), 0xFF7E2148.toInt()),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, pw.toFloat(), ph.toFloat(), p)
        return Prepared(key, screen, bmp, Blur.mid(bmp), Blur.fog(bmp), w.toFloat(), h.toFloat(), ScaleMode.STRETCH)
    }
}
