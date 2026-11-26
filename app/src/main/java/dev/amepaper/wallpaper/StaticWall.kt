package dev.amepaper.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.amepaper.data.Changer
import dev.amepaper.data.CropMath
import dev.amepaper.data.K
import dev.amepaper.data.Library
import dev.amepaper.data.Placement
import dev.amepaper.data.Prefs
import dev.amepaper.data.ScaleMode
import dev.amepaper.data.Screen
import dev.amepaper.data.get
import dev.amepaper.render.ImagePrep
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Static mode: pictures are set as regular wallpapers, which One UI builds its Color palette from.
 *  - Lock screen only (default): the lock screen gets a regular picture, the home screen keeps
 *    the live rain. One UI offers a palette made from the lock screen wallpaper.
 *  - Both screens: no rain at all.
 * The interval changer keeps running in the background (every 15 minutes at most, Android's
 * limit for background work), while the rain is on screen, and whenever the app is open.
 */
object StaticWall {
    private const val TAG = "AmeStatic"
    private const val PERIODIC = "amepaper-static"
    private const val NOW = "amepaper-static-now"

    fun enabled(sp: SharedPreferences) = sp.get(K.STATIC_MODE)

    /** Turn static mode on: set the wallpapers now and keep the changer going in the background. */
    fun start(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<StaticWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        applySoon(ctx, force = true)
    }

    fun stop(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(ctx).cancelUniqueWork(NOW)
        Prefs.sp(ctx).edit().remove(K.STATIC_SIG.name).remove(K.STATIC_LOCK_ID.name).apply()
    }

    /**
     * Lock-only mode off: drop our lock picture so the lock screen shows the home wallpaper (the
     * rain) again. Returns false if that didn't work and the wallpaper picker is needed.
     */
    fun clearLock(ctx: Context): Boolean = try {
        val wm = WallpaperManager.getInstance(ctx)
        wm.clear(WallpaperManager.FLAG_LOCK)
        wm.getWallpaperId(WallpaperManager.FLAG_LOCK) < 0
    } catch (t: Throwable) {
        false
    }

    /** Re-check shortly (pictures, framing or effects changed). Only sets what actually changed. */
    fun applySoon(ctx: Context, force: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<StaticWorker>()
            .setInitialDelay(500, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("force" to force))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
    }

    /** Advances the changer if due, then sets the current pictures if they changed. Blocking. */
    fun apply(ctx: Context, force: Boolean): Boolean {
        val app = ctx.applicationContext
        Library.init(app)
        val sp = Prefs.sp(app)
        if (!enabled(sp)) return false
        val wm = WallpaperManager.getInstance(app)
        val lockOnly = sp.get(K.STATIC_LOCK_ONLY)
        // both-screens mode: if the live wallpaper was picked again afterwards, the user went back to rain
        val liveAgain = runCatching { wm.wallpaperInfo?.packageName == app.packageName }.getOrDefault(false)
        if (!lockOnly && liveAgain && sp.get(K.STATIC_SIG).isNotEmpty()) {
            sp.edit().putBoolean(K.STATIC_MODE.name, false).apply()
            stop(app)
            return false
        }
        Changer.tick(app, System.currentTimeMillis())

        val (w, h) = displaySize(app)
        val home = if (lockOnly) "-" else signature(sp, Screen.HOME, w, h)
        val lock = signature(sp, Screen.LOCK, w, h)
        val sig = "$home||$lock"
        // something else replaced our lock picture (e.g. the live wallpaper was set for both screens)
        val lockReplaced = lockOnly &&
            runCatching { wm.getWallpaperId(WallpaperManager.FLAG_LOCK) }.getOrDefault(-1) != sp.get(K.STATIC_LOCK_ID)
        if (!force && !lockReplaced && sig == sp.get(K.STATIC_SIG)) return false

        if (lockOnly) {
            val lockBmp = render(sp, Screen.LOCK, w, h)
            val id = wm.setBitmap(lockBmp, null, true, WallpaperManager.FLAG_LOCK)
            sp.edit().putString(K.STATIC_SIG.name, sig).putInt(K.STATIC_LOCK_ID.name, id).apply()
            return true
        }

        val homeBmp = render(sp, Screen.HOME, w, h)
        if (home == lock) {
            wm.setBitmap(homeBmp, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
        } else {
            wm.setBitmap(homeBmp, null, true, WallpaperManager.FLAG_SYSTEM)
            homeBmp.recycle()
            val lockBmp = render(sp, Screen.LOCK, w, h)
            wm.setBitmap(lockBmp, null, true, WallpaperManager.FLAG_LOCK)
        }
        sp.edit().putString(K.STATIC_SIG.name, sig).apply()
        return true
    }

    /** Size of the screen in use (cover or main), in its natural orientation. */
    private fun displaySize(ctx: Context): Pair<Int, Int> {
        val dm = ctx.getSystemService(DisplayManager::class.java)
        val d = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val p = Point()
        @Suppress("DEPRECATION")
        d?.getRealSize(p)
        var w = p.x
        var h = p.y
        if (w <= 0 || h <= 0) {
            val m = ctx.resources.displayMetrics
            w = m.widthPixels
            h = m.heightPixels
        }
        val rot = d?.rotation ?: Surface.ROTATION_0
        return if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270) Pair(h, w) else Pair(w, h)
    }

    /** Everything that decides what a screen's wallpaper looks like (equal = identical picture). */
    private fun signature(sp: SharedPreferences, s: Screen, w: Int, h: Int): String {
        val img = Changer.current(sp, s)
        val crop = img?.let { CropMath.resolve(it, Placement.forSize(s, w, h), w.toFloat() / max(1, h)).crop }
        val fx = K.fx(s)
        return listOf(
            img?.id, img?.file, crop, sp.get(K.SCALE), w, h,
            if (sp.get(fx.blurOn)) sp.get(fx.blurAmt) else 0f,
            if (sp.get(fx.brightOn)) sp.get(fx.brightAmt) else 1f,
            if (sp.get(fx.vigOn)) sp.get(fx.vigAmt) else 0f,
            if (sp.get(fx.grayOn)) sp.get(fx.grayAmt) else 0f,
        ).joinToString("|")
    }

    /** The finished wallpaper for [s]: framing, fill, scaling and effects baked in. */
    private fun render(sp: SharedPreferences, s: Screen, w: Int, h: Int): Bitmap {
        val img = Changer.current(sp, s)
        val mode = ScaleMode.from(sp.get(K.SCALE))
        val fx = K.fx(s)
        val blur = if (sp.get(fx.blurOn)) sp.get(fx.blurAmt) else 0f
        val crop = img?.let { CropMath.resolve(it, Placement.forSize(s, w, h), w.toFloat() / max(1, h)).crop }
        val p = when {
            img == null -> ImagePrep.placeholder(s, w, h, "static")
            else -> ImagePrep.prepare(img, crop, s, w, h, mode, blur, "static")
                ?: ImagePrep.prepare(img, null, s, w, h, mode, blur, "static")
                ?: ImagePrep.placeholder(s, w, h, "static")
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        val sw = max(1f, p.srcW)
        val sh = max(1f, p.srcH)
        val (dw, dh) = when (mode) {
            ScaleMode.FILL -> max(w / sw, h / sh).let { Pair(sw * it, sh * it) }
            ScaleMode.FIT -> min(w / sw, h / sh).let { Pair(sw * it, sh * it) }
            ScaleMode.STRETCH -> Pair(w.toFloat(), h.toFloat())
            ScaleMode.NONE -> Pair(sw, sh)
        }
        val dst = RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)

        // the same brightness and black & white as the live wallpaper's effects
        val bright = if (sp.get(fx.brightOn)) sp.get(fx.brightAmt) else 1f
        val gray = if (sp.get(fx.grayOn)) sp.get(fx.grayAmt) else 0f
        val cm = ColorMatrix().apply { setSaturation(1f - gray.coerceIn(0f, 1f)) }
        cm.postConcat(ColorMatrix().apply { setScale(bright, bright, bright, 1f) })
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        c.drawBitmap(p.sharp, null, dst, paint)

        val vig = if (sp.get(fx.vigOn)) sp.get(fx.vigAmt) else 0f
        if (vig > 0.01f) {
            val r = 0.75f * kotlin.math.hypot(w.toFloat(), h.toFloat())
            val a = (vig.coerceIn(0f, 1f) * 255).toInt()
            val shader = RadialGradient(
                w / 2f, h / 2f, r,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(a, 0, 0, 0)),
                floatArrayOf(0f, 0.23f, 1f), Shader.TileMode.CLAMP,
            )
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { this.shader = shader })
        }
        return out
    }
}

/** Runs static mode's changer and sets the wallpaper when needed. */
class StaticWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result = try {
        StaticWall.apply(applicationContext, inputData.getBoolean("force", false))
        Result.success()
    } catch (t: Throwable) {
        Log.e("AmeStatic", "Couldn't set the wallpaper", t)
        Result.retry()
    }
}
