package dev.amepaper.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.amepaper.data.K
import dev.amepaper.data.Prefs
import dev.amepaper.data.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Weather Sync: rain follows the real weather (Open-Meteo, no API key needed). */
object Weather {
    private const val PERIODIC = "amepaper_weather"
    private const val ONCE = "amepaper_weather_now"

    data class Place(val lat: Float, val lon: Float, val name: String)

    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (Prefs.sp(ctx).get(K.WX_SYNC)) {
            val req = PeriodicWorkRequestBuilder<WeatherWorker>(30, TimeUnit.MINUTES)
                .setConstraints(constraints())
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        } else {
            wm.cancelUniqueWork(PERIODIC)
        }
    }

    fun refreshNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<WeatherWorker>().setConstraints(constraints()).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, req)
    }

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun lastKnown(ctx: Context): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return listOf(LocationManager.NETWORK_PROVIDER, "fused", LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
            .maxByOrNull { it.time }
    }

    /** One-off location for the "Use my location" button (call with permission granted). */
    @SuppressLint("MissingPermission")
    fun currentLocation(ctx: Context, callback: (Location?) -> Unit) {
        val last = lastKnown(ctx)
        if (last != null && System.currentTimeMillis() - last.time < 30 * 60_000L) {
            callback(last)
            return
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null || !hasPermission(ctx)) {
            callback(last)
            return
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val provider = when {
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> null
            }
            if (provider == null) {
                callback(last)
                return
            }
            try {
                lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(ctx)) { loc ->
                    callback(loc ?: last)
                }
            } catch (_: Exception) {
                callback(last)
            }
        } else {
            callback(last)
        }
    }

    fun savePlace(ctx: Context, p: Place, mode: String) {
        Prefs.sp(ctx).edit()
            .putFloat(K.WX_LAT.name, p.lat)
            .putFloat(K.WX_LON.name, p.lon)
            .putString(K.WX_PLACE.name, p.name)
            .putString(K.WX_MODE.name, mode)
            .apply()
    }

    /** City search for people who'd rather not share location. */
    suspend fun geocode(name: String): Place? = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(name.trim(), "UTF-8")
            val body = get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=en&format=json")
                ?: return@withContext null
            val results = JSONObject(body).optJSONArray("results") ?: return@withContext null
            if (results.length() == 0) return@withContext null
            val r = results.getJSONObject(0)
            val label = listOf(r.optString("name"), r.optString("admin1"), r.optString("country_code"))
                .filter { it.isNotBlank() }
                .joinToString(", ")
            Place(r.getDouble("latitude").toFloat(), r.getDouble("longitude").toFloat(), label)
        } catch (_: Exception) {
            null
        }
    }

    /** Fetches current weather and stores the rain level. Returns false on a network error. */
    suspend fun update(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val sp = Prefs.sp(ctx)
        var lat = sp.get(K.WX_LAT)
        var lon = sp.get(K.WX_LON)
        if (sp.get(K.WX_MODE) == "gps") {
            lastKnown(ctx)?.let {
                lat = it.latitude.toFloat()
                lon = it.longitude.toFloat()
                sp.edit().putFloat(K.WX_LAT.name, lat).putFloat(K.WX_LON.name, lon).apply()
            }
        }
        if (lat.isNaN() || lon.isNaN()) return@withContext true
        try {
            val body = get(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=weather_code,precipitation&timezone=auto",
            ) ?: return@withContext false
            val cur = JSONObject(body).getJSONObject("current")
            val code = cur.optInt("weather_code", 0)
            val precip = cur.optDouble("precipitation", 0.0)
            sp.edit()
                .putInt(K.WX_LEVEL.name, levelFor(code, precip))
                .putString(K.WX_DESC.name, describe(code))
                .putLong(K.WX_UPDATED.name, System.currentTimeMillis())
                .apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            if (conn.responseCode != 200) null else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** 0 dry, 1 drizzle / light rain, 2 rain, 3 heavy rain or storm (WMO weather codes). */
    fun levelFor(code: Int, precip: Double): Int {
        val level = when (code) {
            51, 53, 55, 56, 57, 61 -> 1
            63, 66, 80 -> 2
            65, 67, 81, 82, 95, 96, 99 -> 3
            else -> 0
        }
        return if (level == 0 && precip > 0.05 && code !in 71..77 && code !in 85..86) 1 else level
    }

    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1, 2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71, 73, 75, 77 -> "Snow"
        80 -> "Rain showers"
        81, 82 -> "Heavy showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Unknown"
    }

    fun levelLabel(level: Int): String = when (level) {
        0 -> "no rain"
        1 -> "light rain"
        2 -> "rain"
        3 -> "heavy rain"
        else -> "waiting for first update"
    }
}

class WeatherWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result =
        if (Weather.update(applicationContext)) Result.success() else Result.retry()
}
