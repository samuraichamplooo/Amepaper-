package dev.amepaper.wallpaper

import android.app.KeyguardManager
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.opengl.GLSurfaceView
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import dev.amepaper.data.Screen
import dev.amepaper.render.RainRenderer
import dev.amepaper.render.WallpaperController
import dev.amepaper.render.WallpaperGLView

/**
 * The live wallpaper. One engine can be on both the home and lock screen; it then shows the
 * lock-screen image while the keyguard is up and cross-fades to the home image on unlock.
 * It reports the home picture's colors to Android, so the system color palette can follow it.
 */
class RainWallpaperService : WallpaperService() {

    private val engines = mutableSetOf<RainEngine>()

    override fun onCreateEngine(): Engine = RainEngine()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        engines.forEach { it.onConfigChanged() }
    }

    inner class RainEngine : Engine() {
        private var glView: WallpaperGLView? = null
        private var controller: WallpaperController? = null
        private var receiverRegistered = false

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> updateScreen(fade = false, screenOff = true)
                    Intent.ACTION_SCREEN_ON -> updateScreen(fade = false)
                    Intent.ACTION_USER_PRESENT -> updateScreen(fade = true)
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            val service = this@RainWallpaperService

            WallpaperGLView.pendingHolder = surfaceHolder
            val view = WallpaperGLView(service)
            WallpaperGLView.pendingHolder = null
            view.setEGLContextClientVersion(3)
            view.preserveEGLContextOnPause = true
            val renderer = RainRenderer(service)
            view.setRenderer(renderer)
            view.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            glView = view

            val c = WallpaperController(
                service,
                renderer,
                { r -> view.queueEvent(r) },
                { view.requestRender() },
                preview = isPreview,
            )
            renderer.onSize = { w, h -> c.postSurfaceSize(w, h) }
            // let Android (and One UI's Color palette) pick up the colors of the current picture
            c.onColorsChanged = {
                if (Build.VERSION.SDK_INT >= 27) {
                    try {
                        notifyColorsChanged()
                    } catch (_: Throwable) {
                    }
                }
            }
            controller = c
            engines.add(this)

            if (!isPreview) {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                ContextCompat.registerReceiver(service, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                receiverRegistered = true
            }
            updateScreen(fade = false)
        }

        /** The palette of the picture on screen, so system colors can follow the wallpaper. */
        override fun onComputeColors(): WallpaperColors? = controller?.themeColors

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            controller?.setSurfaceSize(width, height)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            val v = glView ?: return
            if (visible) {
                v.onResume()
                updateScreen(fade = false)
                controller?.setVisible(true)
            } else {
                controller?.setVisible(false)
                v.onPause()
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float, xOffsetStep: Float, yOffsetStep: Float,
            xPixelOffset: Int, yPixelOffset: Int,
        ) {
            controller?.setOffset(xOffset)
        }

        override fun onDestroy() {
            engines.remove(this)
            if (receiverRegistered) {
                try {
                    unregisterReceiver(receiver)
                } catch (_: Exception) {
                }
                receiverRegistered = false
            }
            controller?.release()
            controller = null
            glView?.release()
            glView = null
            super.onDestroy()
        }

        fun onConfigChanged() {
            controller?.onConfigurationChanged()
        }

        /** Which screens this engine is drawing on, when Android tells us (Android 14+). */
        private fun flags(): Int {
            if (Build.VERSION.SDK_INT < 34) return -1
            return try {
                Engine::class.java.getMethod("getWallpaperFlags").invoke(this) as? Int ?: -1
            } catch (_: Throwable) {
                -1
            }
        }

        private fun updateScreen(fade: Boolean, screenOff: Boolean = false) {
            val c = controller ?: return
            if (isPreview) {
                c.screens = setOf(Screen.HOME)
                c.setScreen(Screen.HOME, false)
                return
            }
            val f = flags()
            val sys = f > 0 && (f and WallpaperManager.FLAG_SYSTEM) != 0
            val lock = f > 0 && (f and WallpaperManager.FLAG_LOCK) != 0
            when {
                lock && !sys -> {
                    c.screens = setOf(Screen.LOCK)
                    c.setScreen(Screen.LOCK, false)
                }
                sys && !lock -> {
                    c.screens = setOf(Screen.HOME)
                    c.setScreen(Screen.HOME, false)
                }
                else -> {
                    c.screens = setOf(Screen.HOME, Screen.LOCK)
                    val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                    val locked = screenOff || km.isKeyguardLocked
                    c.setScreen(if (locked) Screen.LOCK else Screen.HOME, fade)
                }
            }
        }
    }
}
