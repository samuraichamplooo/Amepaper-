package dev.amepaper.data

import android.content.Context
import android.content.SharedPreferences

class BoolKey(val name: String, val def: Boolean)
class IntKey(val name: String, val def: Int)
class LongKey(val name: String, val def: Long)
class FloatKey(val name: String, val def: Float)
class StrKey(val name: String, val def: String)

fun SharedPreferences.get(k: BoolKey): Boolean = getBoolean(k.name, k.def)
fun SharedPreferences.get(k: IntKey): Int = getInt(k.name, k.def)
fun SharedPreferences.get(k: LongKey): Long = getLong(k.name, k.def)
fun SharedPreferences.get(k: FloatKey): Float = getFloat(k.name, k.def)
fun SharedPreferences.get(k: StrKey): String = getString(k.name, k.def) ?: k.def

enum class Screen { HOME, LOCK }

enum class ScaleMode(val label: String) {
    FILL("Fill"), FIT("Fit"), STRETCH("Stretch"), NONE("None");

    companion object {
        fun from(s: String): ScaleMode = entries.firstOrNull { it.name == s } ?: FILL
    }
}

/** Crop slots: tall screens (folded cover screen / normal phones) and wide screens (unfolded main screen). */
enum class Slot { TALL, WIDE }

object Prefs {
    const val FILE = "amepaper"

    @Volatile
    private var instance: SharedPreferences? = null

    fun sp(ctx: Context): SharedPreferences =
        instance ?: synchronized(this) {
            instance ?: ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).also { instance = it }
        }
}

class FxKeys(
    val brightOn: BoolKey, val brightAmt: FloatKey,
    val blurOn: BoolKey, val blurAmt: FloatKey,
    val vigOn: BoolKey, val vigAmt: FloatKey,
    val grayOn: BoolKey, val grayAmt: FloatKey,
)

object K {
    // --- interval changer ---
    val CHANGING = BoolKey("changing", true)
    val ROTATE_HOME = BoolKey("rotate_home", true)
    val ROTATE_LOCK = BoolKey("rotate_lock", true)
    val MIRROR = BoolKey("mirror_lock", false)
    val INDIVIDUAL = BoolKey("individual", false)
    val INTERVAL = IntKey("interval", 1440)
    val INTERVAL_HOME = IntKey("interval_home", 1440)
    val INTERVAL_LOCK = IntKey("interval_lock", 1440)
    val SHUFFLE = BoolKey("shuffle", true)
    val HOME_ALBUM = StrKey("home_album", "")
    val LOCK_ALBUM = StrKey("lock_album", "")
    val HOME_IMAGE = StrKey("home_image", "")
    val LOCK_IMAGE = StrKey("lock_image", "")
    val HOME_NEXT = LongKey("home_next", 0L)
    val LOCK_NEXT = LongKey("lock_next", 0L)

    // --- display ---
    val SCALE = StrKey("scale", "FILL")
    val HSCROLL = BoolKey("hscroll", false)

    // --- visual effects (per screen) ---
    val FX_HOME = FxKeys(
        BoolKey("fx_bright_home", false), FloatKey("fx_bright_home_amt", 0.7f),
        BoolKey("fx_blur_home", false), FloatKey("fx_blur_home_amt", 0.35f),
        BoolKey("fx_vig_home", false), FloatKey("fx_vig_home_amt", 0.5f),
        BoolKey("fx_gray_home", false), FloatKey("fx_gray_home_amt", 1f),
    )
    val FX_LOCK = FxKeys(
        BoolKey("fx_bright_lock", false), FloatKey("fx_bright_lock_amt", 0.7f),
        BoolKey("fx_blur_lock", false), FloatKey("fx_blur_lock_amt", 0.35f),
        BoolKey("fx_vig_lock", false), FloatKey("fx_vig_lock_amt", 0.5f),
        BoolKey("fx_gray_lock", false), FloatKey("fx_gray_lock_amt", 1f),
    )
    val ADAPTIVE = BoolKey("fx_adaptive", false)

    fun fx(s: Screen): FxKeys = if (s == Screen.HOME) FX_HOME else FX_LOCK

    // --- rain ---
    val RAIN_ON = BoolKey("rain_on", true)
    val RAIN_HOME = BoolKey("rain_home", true)
    val RAIN_LOCK = BoolKey("rain_lock", true)
    val RAIN_AMOUNT = FloatKey("rain_amount", 0.6f)
    val DROPLETS = FloatKey("droplets", 0.5f)
    val DROP_SIZE = FloatKey("drop_size", 1f)
    val FOG = FloatKey("fog", 0.3f)
    val REFRACTION = FloatKey("refraction", 0.6f)
    val TRAILS = FloatKey("trails", 0.8f)
    val SPEED = FloatKey("speed", 1f)
    val FPS = IntKey("fps", 30)
    val SAVER = BoolKey("battery_saver", true)

    // --- weather sync ---
    val WX_SYNC = BoolKey("wx_sync", false)
    val WX_MODE = StrKey("wx_mode", "gps") // gps | city
    val WX_LAT = FloatKey("wx_lat", Float.NaN)
    val WX_LON = FloatKey("wx_lon", Float.NaN)
    val WX_PLACE = StrKey("wx_place", "")
    val WX_LEVEL = IntKey("wx_level", -1) // -1 unknown, 0 dry, 1 drizzle, 2 rain, 3 heavy
    val WX_DESC = StrKey("wx_desc", "")
    val WX_UPDATED = LongKey("wx_updated", 0L)
    val WX_DRY = StrKey("wx_dry", "clear") // clear | droplets | keep

    // --- appearance ---
    val DYNAMIC = BoolKey("dynamic_color", false)
    val AMOLED = BoolKey("amoled", false)

    // --- measured screen shapes (width / height) ---
    val ASPECT_TALL = FloatKey("aspect_tall", 0f)
    val ASPECT_WIDE = FloatKey("aspect_wide", 0f)

    /** Settings that only change the rain parameters. */
    val RAIN_KEYS = setOf(
        RAIN_ON.name, RAIN_AMOUNT.name, DROPLETS.name, DROP_SIZE.name, FOG.name, REFRACTION.name,
        TRAILS.name, SPEED.name, WX_SYNC.name, WX_LEVEL.name, WX_DRY.name, FPS.name, SAVER.name,
    )

    /** Settings that are applied in the shader without decoding the image again. */
    val LIGHT_FX_KEYS = setOf(
        FX_HOME.brightOn.name, FX_HOME.brightAmt.name, FX_HOME.vigOn.name, FX_HOME.vigAmt.name,
        FX_HOME.grayOn.name, FX_HOME.grayAmt.name,
        FX_LOCK.brightOn.name, FX_LOCK.brightAmt.name, FX_LOCK.vigOn.name, FX_LOCK.vigAmt.name,
        FX_LOCK.grayOn.name, FX_LOCK.grayAmt.name,
        ADAPTIVE.name, RAIN_HOME.name, RAIN_LOCK.name, HSCROLL.name,
    )

    /** Settings that need the wallpaper image to be prepared again. */
    val IMAGE_KEYS = setOf(
        HOME_ALBUM.name, LOCK_ALBUM.name, HOME_IMAGE.name, LOCK_IMAGE.name, MIRROR.name, SCALE.name,
        FX_HOME.blurOn.name, FX_HOME.blurAmt.name, FX_LOCK.blurOn.name, FX_LOCK.blurAmt.name,
    )
}

object Screens {
    const val DEFAULT_TALL = 9f / 21f // Galaxy Z Fold cover screen
    const val DEFAULT_WIDE = 0.9f     // Galaxy Z Fold main screen (portrait)

    fun slotFor(w: Int, h: Int): Slot =
        if (h.toFloat() / maxOf(1, w) >= 1.5f) Slot.TALL else Slot.WIDE

    /** Remember the shape of the screens this device has, so crops can default to them. */
    fun record(ctx: Context, w: Int, h: Int) {
        if (w <= 0 || h <= 0 || w > h) return
        val sp = Prefs.sp(ctx)
        val key = if (slotFor(w, h) == Slot.TALL) K.ASPECT_TALL else K.ASPECT_WIDE
        val a = w.toFloat() / h
        if (kotlin.math.abs(sp.get(key) - a) > 0.004f) sp.edit().putFloat(key.name, a).apply()
    }

    fun aspect(sp: SharedPreferences, slot: Slot): Float {
        val v = sp.get(if (slot == Slot.TALL) K.ASPECT_TALL else K.ASPECT_WIDE)
        return if (v > 0f) v else if (slot == Slot.TALL) DEFAULT_TALL else DEFAULT_WIDE
    }
}

data class RainParams(
    val rain: Float,
    val drops: Float,
    val size: Float,
    val fog: Float,
    val refraction: Float,
    val trails: Float,
    val speed: Float,
) {
    val animated: Boolean get() = rain > 0.001f || drops > 0.001f

    companion object {
        val OFF = RainParams(0f, 0f, 1f, 0f, 0.6f, 0.8f, 1f)

        fun read(sp: SharedPreferences): RainParams {
            if (!sp.get(K.RAIN_ON)) return OFF
            val base = RainParams(
                rain = sp.get(K.RAIN_AMOUNT),
                drops = sp.get(K.DROPLETS),
                size = sp.get(K.DROP_SIZE),
                fog = sp.get(K.FOG),
                refraction = sp.get(K.REFRACTION),
                trails = sp.get(K.TRAILS),
                speed = sp.get(K.SPEED),
            )
            if (!sp.get(K.WX_SYNC)) return base
            return when (sp.get(K.WX_LEVEL)) {
                0 -> when (sp.get(K.WX_DRY)) {
                    "keep" -> base
                    "droplets" -> base.copy(rain = 0f, drops = maxOf(0.25f, base.drops * 0.6f), fog = base.fog * 0.4f)
                    else -> base.copy(rain = 0f, drops = 0f, fog = 0f)
                }
                1 -> base.copy(rain = base.rain * 0.45f, drops = maxOf(base.drops, 0.45f))
                3 -> base.copy(
                    rain = minOf(1f, base.rain * 1.4f + 0.15f),
                    drops = minOf(1f, base.drops + 0.2f),
                    fog = maxOf(base.fog, 0.4f),
                    speed = base.speed * 1.25f,
                )
                else -> base // 2 = rain, -1 = not known yet
            }
        }
    }
}
