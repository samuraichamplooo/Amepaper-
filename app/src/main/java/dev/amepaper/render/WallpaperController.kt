package dev.amepaper.render

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import dev.amepaper.data.Changer
import dev.amepaper.data.K
import dev.amepaper.data.Library
import dev.amepaper.data.Prefs
import dev.amepaper.data.RainParams
import dev.amepaper.data.ScaleMode
import dev.amepaper.data.Screen
import dev.amepaper.data.Screens
import dev.amepaper.data.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Glue between settings / library and the renderer. Used by the live wallpaper engine and by
 * the preview in the Rain tab. Everything here runs on the main thread except image decoding.
 */
class WallpaperController(
    ctx: Context,
    private val renderer: RainRenderer,
    private val queue: (Runnable) -> Unit,
    private val requestRender: () -> Unit,
    private val preview: Boolean,
) : SharedPreferences.OnSharedPreferenceChangeListener {

    private val app = ctx.applicationContext
    private val sp = Prefs.sp(app)
    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var w = 0
    private var h = 0
    private var screen = Screen.HOME
    private var visible = false
    private var released = false
    private var looping = false
    private var fadeUntil = 0L
    private var shownKey: String? = null

    /** Screens this engine may have to show (both, when one engine serves home and lock). */
    var screens: Set<Screen> = setOf(Screen.HOME)

    private val prepared = HashMap<Screen, Prepared>()
    private val loading = HashMap<Screen, String>()

    private val frame = object : Runnable {
        override fun run() {
            if (released || !visible) {
                looping = false
                return
            }
            val anim = renderer.params.animated || renderer.fading || System.currentTimeMillis() < fadeUntil
            requestRender()
            if (anim) {
                main.postDelayed(this, frameDelay())
            } else {
                looping = false
            }
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (released || !visible) return
            Changer.tick(app, System.currentTimeMillis())
            main.postDelayed(this, 30_000L)
        }
    }

    init {
        Library.init(app)
        sp.registerOnSharedPreferenceChangeListener(this)
        renderer.params = RainParams.read(sp)
        scope.launch {
            Library.albums.collect { refresh(fade = true) }
        }
    }

    // ---------------------------------------------------------------- inputs

    fun postSurfaceSize(width: Int, height: Int) {
        main.post { setSurfaceSize(width, height) }
    }

    fun setSurfaceSize(width: Int, height: Int) {
        if (released || width <= 0 || height <= 0) return
        if (width == w && height == h) return
        w = width
        h = height
        if (!preview) Screens.record(app, w, h)
        prepared.clear()
        loading.clear()
        shownKey = null
        refresh(fade = false)
    }

    fun setScreen(s: Screen, fade: Boolean) {
        if (released) return
        if (s == screen && shownKey != null) return
        screen = s
        val p = prepared[s]
        if (p != null && p.key == keyFor(s)) {
            send(p, fade, 350f)
        } else {
            load(s, fade)
        }
        for (o in screens) {
            if (o != s && prepared[o]?.key != keyFor(o)) load(o, false)
        }
    }

    fun setVisible(v: Boolean) {
        if (released) return
        visible = v
        main.removeCallbacks(tickRunnable)
        if (v) {
            if (!preview) {
                Changer.tick(app, System.currentTimeMillis())
                main.postDelayed(tickRunnable, 30_000L)
            }
            refresh(fade = false) // catch any edit made while the wallpaper was hidden
            pushFx()
            requestRender()
            kick()
        }
    }

    fun setOffset(x: Float) {
        renderer.xOffset = x
        if (!looping) requestRender()
    }

    fun onConfigurationChanged() {
        pushFx()
    }

    fun release() {
        if (released) return
        released = true
        sp.unregisterOnSharedPreferenceChangeListener(this)
        scope.cancel()
        main.removeCallbacks(frame)
        main.removeCallbacks(tickRunnable)
        io.shutdownNow()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (released) return
        when {
            key == null -> {
                pushParams()
                pushFx()
                refresh(fade = true)
            }
            key == K.REFRESH.name -> {
                prepared.clear()
                loading.clear()
                shownKey = null
                refresh(fade = visible)
            }
            key in K.RAIN_KEYS -> pushParams()
            key in K.LIGHT_FX_KEYS -> pushFx()
            key in K.IMAGE_KEYS -> refresh(fade = key == K.HOME_IMAGE.name || key == K.LOCK_IMAGE.name)
        }
    }

    // ---------------------------------------------------------------- internals

    private fun scaleMode(): ScaleMode = ScaleMode.from(sp.get(K.SCALE))

    private fun blurFor(s: Screen): Float {
        val fx = K.fx(s)
        return if (sp.get(fx.blurOn)) sp.get(fx.blurAmt) else 0f
    }

    private fun fxFor(s: Screen): FloatArray {
        val fx = K.fx(s)
        var bright = if (sp.get(fx.brightOn)) sp.get(fx.brightAmt) else 1f
        if (sp.get(K.ADAPTIVE)) {
            val night = (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            if (night) bright *= 0.78f
        }
        val vig = if (sp.get(fx.vigOn)) sp.get(fx.vigAmt) else 0f
        val gray = if (sp.get(fx.grayOn)) sp.get(fx.grayAmt) else 0f
        val rain = if (sp.get(if (s == Screen.HOME) K.RAIN_HOME else K.RAIN_LOCK)) 1f else 0f
        return floatArrayOf(bright, vig, gray, rain)
    }

    private fun scrollFor(s: Screen): Boolean =
        s == Screen.HOME && sp.get(K.HSCROLL) && scaleMode() == ScaleMode.FILL

    private fun keyFor(s: Screen): String? {
        if (w == 0 || h == 0) return null
        val img = Changer.current(sp, s) ?: return ImagePrep.placeholderKey(s, w, h)
        return ImagePrep.keyFor(img, s, w, h, scaleMode(), blurFor(s))
    }

    private fun pushParams() {
        renderer.params = RainParams.read(sp)
        requestRender()
        kick()
    }

    private fun pushFx() {
        val home = fxFor(Screen.HOME)
        val lock = fxFor(Screen.LOCK)
        val sh = scrollFor(Screen.HOME)
        queue(Runnable {
            renderer.updateFx(Screen.HOME, home, sh)
            renderer.updateFx(Screen.LOCK, lock, false)
        })
        requestRender()
    }

    /** Make sure the current screen shows the right image; preload the other screen. */
    private fun refresh(fade: Boolean) {
        if (released || w == 0 || h == 0) return
        val key = keyFor(screen)
        val p = prepared[screen]
        if (p != null && p.key == key) {
            if (shownKey != key) send(p, fade, 800f)
        } else {
            load(screen, fade)
        }
        for (s in screens) {
            if (s == screen) continue
            if (prepared[s]?.key != keyFor(s)) load(s, false)
        }
    }

    private fun send(p: Prepared, fade: Boolean, durationMs: Float) {
        shownKey = p.key
        writeStatus(describe(p.screen, null))
        val fx = fxFor(p.screen)
        val scroll = scrollFor(p.screen)
        val doFade = fade && visible
        if (doFade) fadeUntil = System.currentTimeMillis() + durationMs.toLong() + 150L
        queue(Runnable { renderer.show(p, fx, scroll, doFade, durationMs) })
        requestRender()
        kick()
    }

    private fun load(s: Screen, fade: Boolean) {
        val key = keyFor(s) ?: return
        if (loading[s] == key) return
        loading[s] = key
        val img = Changer.current(sp, s)
        val mode = scaleMode()
        val blur = blurFor(s)
        val ww = w
        val hh = h
        try {
            io.execute {
                var error: String? = null
                var p: Prepared? = try {
                    if (img != null) ImagePrep.prepare(img, s, ww, hh, mode, blur, key)
                    else ImagePrep.placeholder(s, ww, hh, key)
                } catch (t: Throwable) {
                    Log.e(TAG, "Couldn't prepare wallpaper", t)
                    error = t.javaClass.simpleName + ": " + (t.message ?: "")
                    null
                }
                if (p == null && img != null) {
                    if (error == null) error = "couldn't read the picture file"
                    // show the picture without its crop rather than nothing
                    p = try {
                        ImagePrep.prepare(img.copy(tall = null, wide = null), s, ww, hh, mode, blur, key)
                    } catch (t: Throwable) {
                        null
                    }
                }
                val err = error
                main.post {
                    onLoaded(s, key, p, ww, hh, fade)
                    if (err != null) writeStatus(describe(s, err))
                }
            }
        } catch (e: Exception) {
            loading.remove(s)
        }
    }

    private fun onLoaded(s: Screen, key: String, p: Prepared?, ww: Int, hh: Int, fade: Boolean) {
        if (released) return
        if (loading[s] == key) loading.remove(s)
        if (p == null || ww != w || hh != h) return
        if (keyFor(s) != key) return // settings changed while decoding; a newer load is coming
        prepared[s] = p
        if (s == screen) send(p, fade, 800f)
    }

    private fun describe(s: Screen, error: String?): String {
        val img = Changer.current(sp, s)
        val slot = Screens.slotFor(w, h)
        val c = img?.crop(slot)
        val where = if (s == Screen.HOME) "Home" else "Lock"
        val shape = if (slot == dev.amepaper.data.Slot.TALL) "cover/tall" else "main/wide"
        val cropText = when {
            img == null -> "no picture in album"
            c == null -> "no crop"
            c.inside -> "crop ${c.aspect}"
            else -> "crop ${c.aspect}, zoomed out, ${c.fill} fill"
        }
        val base = "$where · ${w}×$h ($shape) · $cropText"
        return if (error != null) "$base · ERROR $error" else base
    }

    private fun writeStatus(text: String) {
        if (preview) return
        sp.edit()
            .putString(K.ENGINE_STATUS.name, text)
            .putLong(K.ENGINE_SEEN.name, System.currentTimeMillis())
            .apply()
    }

    private fun kick() {
        if (released || !visible || looping) return
        looping = true
        main.post(frame)
    }

    private fun frameDelay(): Long {
        var fps = sp.get(K.FPS).coerceIn(10, 120)
        if (sp.get(K.SAVER)) {
            val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm?.isPowerSaveMode == true) fps = minOf(fps, 15)
        }
        return 1000L / fps
    }

    companion object {
        private const val TAG = "AmeController"
    }
}
