package dev.amepaper.render

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.SurfaceHolder

/**
 * A GLSurfaceView that draws into a WallpaperService.Engine's surface instead of a window.
 * GLSurfaceView asks for its holder inside its own constructor, so the engine's holder is
 * handed over through [pendingHolder] right before construction.
 */
class WallpaperGLView(context: Context) : GLSurfaceView(context) {

    companion object {
        @JvmStatic
        var pendingHolder: SurfaceHolder? = null
    }

    private var engineHolder: SurfaceHolder? = pendingHolder

    override fun getHolder(): SurfaceHolder = engineHolder ?: pendingHolder ?: super.getHolder()

    /** Stops the GL thread. */
    fun release() {
        super.onDetachedFromWindow()
    }
}
