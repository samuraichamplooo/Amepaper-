package dev.amepaper.data

import kotlin.math.max
import kotlin.math.min

/** How a picture is framed on a given placement, and where that framing came from. */
data class Resolved(val crop: Crop?, val source: Placement?, val own: Boolean)

object CropMath {

    /**
     * The same framing (centre and zoom level) re-shaped to [aspect] (width / height).
     * [iw] and [ih] are the picture's size in pixels.
     */
    fun adapt(c: Crop, iw: Float, ih: Float, aspect: Float): Crop {
        if (iw <= 0f || ih <= 0f) return c
        val a2 = aspect.coerceIn(0.2f, 5f)
        val ia = iw / ih
        val ch1 = max(1f, (c.b - c.t) * ih)
        val a1 = ((c.r - c.l) * iw) / ch1
        val coverH1 = if (ia > a1) ih else iw / a1
        val zoom = coverH1 / ch1
        val coverH2 = if (ia > a2) ih else iw / a2
        val ch2 = coverH2 / zoom
        val cw2 = ch2 * a2
        val cx = (c.l + c.r) / 2f * iw
        val cy = (c.t + c.b) / 2f * ih
        fun place(center: Float, size: Float, full: Float): Float {
            val lo = min(0f, full - size)
            val hi = max(0f, full - size)
            return (center - size / 2f).coerceIn(lo, hi)
        }
        val left = place(cx, cw2, iw)
        val top = place(cy, ch2, ih)
        return Crop("screen", left / iw, top / ih, (left + cw2) / iw, (top + ch2) / ih, c.fill, c.fillBlur, c.fillDim, c.fillSoft)
    }

    /**
     * The framing [img] uses on [p] (a screen of shape [aspect]): its own if set, else the same
     * screen shape on the other screen (home ↔ lock), else the nearest framing adapted to fit.
     */
    fun resolve(img: WallImage, crops: Map<String, Crop>, p: Placement, aspect: Float): Resolved {
        crops[p.key]?.let { return Resolved(it, p, true) }
        crops[p.otherScreen.key]?.let { return Resolved(it, p.otherScreen, false) }
        val order = listOf(
            p.otherOrient,
            p.otherScreen.otherOrient,
            p.otherDisp,
            p.otherScreen.otherDisp,
            p.otherDisp.otherOrient,
            p.otherScreen.otherDisp.otherOrient,
        )
        for (q in order) {
            crops[q.key]?.let { return Resolved(adapt(it, img.w.toFloat(), img.h.toFloat(), aspect), q, false) }
        }
        return Resolved(null, null, false)
    }

    fun resolve(img: WallImage, p: Placement, aspect: Float): Resolved = resolve(img, img.crops, p, aspect)
}
