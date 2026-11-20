package dev.amepaper.data

import android.content.Context
import android.content.SharedPreferences
import kotlin.random.Random

/**
 * The interval changer. Which image each screen shows is stored in prefs; the live wallpaper
 * calls [tick] whenever it becomes visible (and every 30 s while visible), so a change that
 * was due while the screen was off happens the moment you look at it.
 */
object Changer {

    fun effectiveScreen(sp: SharedPreferences, s: Screen): Screen =
        if (s == Screen.LOCK && sp.get(K.MIRROR)) Screen.HOME else s

    fun albumFor(sp: SharedPreferences, s: Screen): Album? {
        val id = sp.get(if (s == Screen.HOME) K.HOME_ALBUM else K.LOCK_ALBUM)
        val all = Library.albums.value
        return all.firstOrNull { it.id == id } ?: all.firstOrNull()
    }

    fun imageKey(s: Screen): StrKey = if (s == Screen.HOME) K.HOME_IMAGE else K.LOCK_IMAGE
    private fun nextKey(s: Screen): LongKey = if (s == Screen.HOME) K.HOME_NEXT else K.LOCK_NEXT
    private fun rotates(sp: SharedPreferences, s: Screen): Boolean =
        sp.get(if (s == Screen.HOME) K.ROTATE_HOME else K.ROTATE_LOCK)

    /** The image shown on [s] right now (null if its album is empty). */
    fun current(sp: SharedPreferences, s: Screen): WallImage? {
        val e = effectiveScreen(sp, s)
        val album = albumFor(sp, e) ?: return null
        if (album.images.isEmpty()) return null
        val id = sp.get(imageKey(e))
        return album.images.firstOrNull { it.id == id } ?: album.images.first()
    }

    fun intervalMs(sp: SharedPreferences, s: Screen): Long {
        val minutes = if (sp.get(K.INDIVIDUAL)) {
            sp.get(if (s == Screen.HOME) K.INTERVAL_HOME else K.INTERVAL_LOCK)
        } else {
            sp.get(K.INTERVAL)
        }
        return maxOf(1, minutes) * 60_000L
    }

    private fun advance(sp: SharedPreferences, s: Screen, e: SharedPreferences.Editor) {
        val album = albumFor(sp, s) ?: return
        val imgs = album.images
        if (imgs.isEmpty()) return
        val idx = imgs.indexOfFirst { it.id == sp.get(imageKey(s)) }
        val next = when {
            imgs.size == 1 -> 0
            sp.get(K.SHUFFLE) -> {
                var r = Random.nextInt(imgs.size)
                while (r == idx) r = Random.nextInt(imgs.size)
                r
            }
            else -> (idx + 1).mod(imgs.size)
        }
        e.putString(imageKey(s).name, imgs[next].id)
    }

    /** Screens the changer is allowed to rotate. */
    private fun rotating(sp: SharedPreferences): List<Screen> {
        val mirror = sp.get(K.MIRROR)
        return Screen.entries.filter { s -> rotates(sp, s) && !(mirror && s == Screen.LOCK) }
    }

    /** "Change wallpaper now". */
    fun changeNow(ctx: Context) {
        val sp = Prefs.sp(ctx)
        val now = System.currentTimeMillis()
        val e = sp.edit()
        val targets = rotating(sp).ifEmpty { listOf(Screen.HOME) }
        for (s in targets) {
            advance(sp, s, e)
            e.putLong(nextKey(s).name, now + intervalMs(sp, s))
        }
        e.apply()
    }

    /** Show a specific image on a screen right away. */
    fun show(ctx: Context, s: Screen, albumId: String, imageId: String) {
        val sp = Prefs.sp(ctx)
        val e = sp.edit()
        e.putString(if (s == Screen.HOME) K.HOME_ALBUM.name else K.LOCK_ALBUM.name, albumId)
        e.putString(imageKey(s).name, imageId)
        e.putLong(nextKey(s).name, System.currentTimeMillis() + intervalMs(sp, s))
        e.apply()
    }

    /** Restart the countdown (after the interval or the on/off switch changes). */
    fun resetSchedule(ctx: Context) {
        val sp = Prefs.sp(ctx)
        val now = System.currentTimeMillis()
        val e = sp.edit()
        for (s in Screen.entries) e.putLong(nextKey(s).name, now + intervalMs(sp, s))
        e.apply()
    }

    /** Advances screens whose time is up. Returns true if anything changed. */
    fun tick(ctx: Context, now: Long): Boolean {
        val sp = Prefs.sp(ctx)
        if (!sp.get(K.CHANGING)) return false
        var changed = false
        var touched = false
        val e = sp.edit()
        for (s in rotating(sp)) {
            val iv = intervalMs(sp, s)
            val next = sp.get(nextKey(s))
            if (next <= 0L) {
                e.putLong(nextKey(s).name, now + iv)
                touched = true
                continue
            }
            if (now >= next) {
                advance(sp, s, e)
                val late = (now - next) % iv
                e.putLong(nextKey(s).name, now + iv - late)
                changed = true
                touched = true
            }
        }
        if (touched) e.apply()
        return changed
    }

    fun formatMinutes(total: Int): String {
        val d = total / 1440
        val h = (total % 1440) / 60
        val m = total % 60
        val parts = mutableListOf<String>()
        if (d > 0) parts += "$d day" + if (d == 1) "" else "s"
        if (h > 0) parts += "$h hour" + if (h == 1) "" else "s"
        if (m > 0) parts += "$m min" + if (m == 1) "" else "s"
        return if (parts.isEmpty()) "1 min" else parts.joinToString(" ")
    }
}
