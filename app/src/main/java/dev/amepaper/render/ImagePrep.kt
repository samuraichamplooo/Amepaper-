package dev.amepaper.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import dev.amepaper.data.Library
import dev.amepaper.data.ScaleMode
import dev.amepaper.data.Screen
import dev.amepaper.data.Screens
import dev.amepaper.data.WallImage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A wallpaper ready for the GPU: sharp + fogged copies of the cropped region. */
class Prepared(
    val key: String,
    val screen: Screen,
    val sharp: Bitmap,
    val fog: Bitmap,
    /** Size of the cropped region in image pixels (used for "None" scaling and aspect). */
    val srcW: Float,
    val srcH: Float,
    val mode: ScaleMode,
)

object ImagePrep {
    private const val MAX_TEX = 4096f

    fun keyFor(img: WallImage, screen: Screen, w: Int, h: Int, mode: ScaleMode, blur: Float): String {
        val slot = Screens.slotFor(w, h)
        return "${img.id}|$slot|${img.crop(slot)}|${w}x$h|$mode|$blur|$screen"
    }

    fun placeholderKey(screen: Screen, w: Int, h: Int): String = "placeholder|${w}x$h|$screen"

    fun prepare(img: WallImage, screen: Screen, w: Int, h: Int, mode: ScaleMode, blur: Float, key: String): Prepared? {
        val file = Library.file(img)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val iw = bounds.outWidth
        val ih = bounds.outHeight
        if (iw <= 0 || ih <= 0) return null

        val c = img.crop(Screens.slotFor(w, h))
        val l = ((c?.l ?: 0f) * iw).roundToInt().coerceIn(0, iw - 1)
        val t = ((c?.t ?: 0f) * ih).roundToInt().coerceIn(0, ih - 1)
        val r = ((c?.r ?: 1f) * iw).roundToInt().coerceIn(l + 1, iw)
        val b = ((c?.b ?: 1f) * ih).roundToInt().coerceIn(t + 1, ih)
        val rw = r - l
        val rh = b - t

        val sw = w.toFloat()
        val sh = h.toFloat()
        val target: Pair<Float, Float> = when (mode) {
            ScaleMode.FILL -> { val s = max(sw / rw, sh / rh); Pair(rw * s, rh * s) }
            ScaleMode.FIT -> { val s = min(sw / rw, sh / rh); Pair(rw * s, rh * s) }
            ScaleMode.STRETCH -> Pair(sw, sh)
            ScaleMode.NONE -> Pair(rw.toFloat(), rh.toFloat())
        }
        // Never upscale on the CPU (the GPU does that) and stay under the texture limit.
        val tw = min(target.first, rw.toFloat())
        val th = min(target.second, rh.toFloat())
        val k = min(1f, MAX_TEX / max(tw, th))
        val outW = max(1, (tw * k).roundToInt())
        val outH = max(1, (th * k).roundToInt())

        var sample = 1
        while (rw / (sample * 2) >= outW && rh / (sample * 2) >= outH) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val region = decodeRegion(file.absolutePath, Rect(l, t, r, b), opts) ?: return null
        var sharp = if (region.width != outW || region.height != outH) {
            Bitmap.createScaledBitmap(region, outW, outH, true)
        } else {
            region
        }
        if (blur > 0.01f) sharp = Blur.soften(sharp, blur)
        val fog = Blur.fog(sharp)
        return Prepared(key, screen, sharp, fog, rw.toFloat(), rh.toFloat(), mode)
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
        return Prepared(key, screen, bmp, Blur.fog(bmp), w.toFloat(), h.toFloat(), ScaleMode.STRETCH)
    }
}
