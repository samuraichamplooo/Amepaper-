package dev.amepaper.render

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

/** Small CPU blurs: a few box-blur passes on a downscaled copy look like a smooth Gaussian. */
object Blur {

    fun box(src: Bitmap, radius: Int, passes: Int): Bitmap {
        val w = src.width
        val h = src.height
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        src.getPixels(a, 0, w, 0, 0, w, h)
        repeat(passes) {
            horizontal(a, b, w, h, radius)
            vertical(b, a, w, h, radius)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(a, 0, w, 0, 0, w, h)
        return out
    }

    /** Blur effect from the Visual Effects section. [amount] 0..1. */
    fun soften(src: Bitmap, amount: Float): Bitmap {
        val f = 2f + amount.coerceIn(0f, 1f) * 14f
        val sw = max(8, (src.width / f).roundToInt())
        val sh = max(8, (src.height / f).roundToInt())
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val blurred = box(small, 2, 3)
        return Bitmap.createScaledBitmap(blurred, src.width, src.height, true)
    }

    /** The fogged-glass copy used by the shader (also fills letterbox bars in Fit mode). */
    fun fog(src: Bitmap): Bitmap {
        val sw = max(16, src.width / 8)
        val sh = max(16, src.height / 8)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        return box(small, 3, 3)
    }

    private fun horizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = src[row + i.coerceIn(0, w - 1)]
                sr += (p shr 16) and 0xff
                sg += (p shr 8) and 0xff
                sb += p and 0xff
            }
            for (x in 0 until w) {
                dst[row + x] = (0xff shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val pOut = src[row + (x - r).coerceIn(0, w - 1)]
                val pIn = src[row + (x + r + 1).coerceIn(0, w - 1)]
                sr += ((pIn shr 16) and 0xff) - ((pOut shr 16) and 0xff)
                sg += ((pIn shr 8) and 0xff) - ((pOut shr 8) and 0xff)
                sb += (pIn and 0xff) - (pOut and 0xff)
            }
        }
    }

    private fun vertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = src[i.coerceIn(0, h - 1) * w + x]
                sr += (p shr 16) and 0xff
                sg += (p shr 8) and 0xff
                sb += p and 0xff
            }
            for (y in 0 until h) {
                dst[y * w + x] = (0xff shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val pOut = src[(y - r).coerceIn(0, h - 1) * w + x]
                val pIn = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                sr += ((pIn shr 16) and 0xff) - ((pOut shr 16) and 0xff)
                sg += ((pIn shr 8) and 0xff) - ((pOut shr 8) and 0xff)
                sb += (pIn and 0xff) - (pOut and 0xff)
            }
        }
    }
}
