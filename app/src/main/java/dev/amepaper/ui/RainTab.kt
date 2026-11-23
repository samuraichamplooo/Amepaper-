package dev.amepaper.ui

import android.Manifest
import android.content.Context
import android.opengl.GLSurfaceView
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.amepaper.data.K
import dev.amepaper.data.Prefs
import dev.amepaper.render.RainRenderer
import dev.amepaper.render.WallpaperController
import dev.amepaper.weather.Weather
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.math.roundToInt

private class Preset(
    val name: String, val rain: Float, val drops: Float, val size: Float, val fog: Float,
    val trails: Float, val speed: Float, val impacts: Float, val falling: Float,
)

private val PRESETS = listOf(
    Preset("Rain Paper", 0.6f, 0.7f, 1f, 0.3f, 0.9f, 1f, 0.45f, 0.25f),
    Preset("Drizzle", 0.3f, 0.8f, 0.85f, 0.2f, 0.7f, 0.8f, 0.25f, 0.15f),
    Preset("Downpour", 1f, 0.9f, 1.1f, 0.4f, 1f, 1.35f, 0.85f, 0.6f),
    Preset("Storm", 0.9f, 0.8f, 1.2f, 0.45f, 1f, 1.6f, 1f, 0.9f),
    Preset("Misty window", 0.35f, 0.5f, 1.1f, 0.8f, 1f, 0.8f, 0.2f, 0.1f),
    Preset("Just droplets", 0f, 0.85f, 1f, 0.25f, 0.5f, 1f, 0f, 0f),
    Preset("Clear glass", 0.4f, 0.6f, 1f, 0f, 0.8f, 1f, 0.45f, 0.2f),
)

@Composable
fun RainTab() {
    val ctx = LocalContext.current
    val rainOn = pref(K.RAIN_ON)
    val rainHome = pref(K.RAIN_HOME)
    val rainLock = pref(K.RAIN_LOCK)
    val fps = pref(K.FPS)
    val saver = pref(K.SAVER)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        PreviewCard()

        Spacer(Modifier.height(16.dp))
        GroupCard {
            SwitchPref("Rain on glass", "Raindrops, trails and fogged glass over your wallpaper", rainOn)
            RowDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Show on", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                MultiChoiceSegmentedButtonRow {
                    SegmentedButton(
                        checked = rainLock.value,
                        onCheckedChange = { rainLock.value = it },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        enabled = rainOn.value,
                    ) { Text("Lock") }
                    SegmentedButton(
                        checked = rainHome.value,
                        onCheckedChange = { rainHome.value = it },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        enabled = rainOn.value,
                    ) { Text("Home") }
                }
            }
        }

        SectionLabel("Presets")
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PRESETS.forEach { p ->
                AssistChip(onClick = { applyPreset(ctx, p) }, label = { Text(p.name) })
            }
        }

        SectionLabel("Customization")
        GroupCard {
            Spacer(Modifier.height(8.dp))
            SliderPref("Raindrops hitting the glass", pref(K.IMPACTS))
            SliderPref("Sliding drops", pref(K.RAIN_AMOUNT))
            SliderPref("Droplets on the glass", pref(K.DROPLETS))
            SliderPref("Drop size", pref(K.DROP_SIZE), 0.5f..2f, format = { "×" + "%.1f".format(it) })
            SliderPref("Glass blur", pref(K.FOG))
            SliderPref("Refraction", pref(K.REFRACTION))
            SliderPref("Trails", pref(K.TRAILS))
            SliderPref("Falling rain (outside)", pref(K.FALLING))
            SliderPref("Speed", pref(K.SPEED), 0.3f..2.5f, format = { "×" + "%.1f".format(it) })
            Spacer(Modifier.height(8.dp))
        }

        SectionLabel("Performance")
        GroupCard {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text("Frame rate", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Higher is smoother, lower saves battery. 120 matches your screen's fastest refresh.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val options = listOf(30, 60, 120)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    options.forEachIndexed { i, f ->
                        SegmentedButton(
                            selected = fps.value == f,
                            onClick = { fps.value = f },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        ) { Text("$f") }
                    }
                }
            }
            RowDivider()
            SwitchPref("Battery saver", "Drop to 15 fps while battery saver is on", saver)
        }

        SectionLabel("Weather Sync")
        WeatherCard()
        Spacer(Modifier.height(32.dp))
    }
}

private fun applyPreset(ctx: Context, p: Preset) {
    Prefs.sp(ctx).edit()
        .putBoolean(K.RAIN_ON.name, true)
        .putFloat(K.RAIN_AMOUNT.name, p.rain)
        .putFloat(K.DROPLETS.name, p.drops)
        .putFloat(K.DROP_SIZE.name, p.size)
        .putFloat(K.FOG.name, p.fog)
        .putFloat(K.TRAILS.name, p.trails)
        .putFloat(K.SPEED.name, p.speed)
        .putFloat(K.IMPACTS.name, p.impacts)
        .putFloat(K.FALLING.name, p.falling)
        .apply()
}

@Composable
private fun PreviewCard() {
    val conf = LocalConfiguration.current
    val aspect = (conf.screenWidthDp.toFloat() / conf.screenHeightDp.coerceAtLeast(1)).coerceIn(0.35f, 1.2f)
    val height = 380.dp
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Live preview · home screen",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
            )
            Box(Modifier.size(height * aspect, height).clip(RoundedCornerShape(18.dp))) {
                RainPreview(Modifier.fillMaxSize())
            }
        }
    }
}

/** Holds a GLSurfaceView running the same renderer as the live wallpaper. */
private class PreviewHolder(ctx: Context) {
    val renderer = RainRenderer(ctx.applicationContext)
    val view: GLSurfaceView = GLSurfaceView(ctx).apply {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
    }
    val controller = WallpaperController(
        ctx.applicationContext,
        renderer,
        { r -> view.queueEvent(r) },
        { view.requestRender() },
        preview = true,
    )
    private var resumed = false

    init {
        renderer.onSize = { w, h -> controller.postSurfaceSize(w, h) }
    }

    fun resume() {
        if (resumed) return
        resumed = true
        view.onResume()
        controller.setVisible(true)
    }

    fun pause() {
        if (!resumed) return
        resumed = false
        controller.setVisible(false)
        view.onPause()
    }

    fun release() {
        pause()
        controller.release()
    }
}

@Composable
private fun RainPreview(modifier: Modifier) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val holder = remember { PreviewHolder(ctx) }
    AndroidView(factory = { holder.view }, modifier = modifier)
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> holder.resume()
                Lifecycle.Event.ON_PAUSE -> holder.pause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) holder.resume()
        onDispose {
            owner.lifecycle.removeObserver(obs)
            holder.release()
        }
    }
}

@Composable
private fun WeatherCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sync = pref(K.WX_SYNC)
    val place = pref(K.WX_PLACE)
    val desc = pref(K.WX_DESC)
    val updated = pref(K.WX_UPDATED)
    val level = pref(K.WX_LEVEL)
    val dry = pref(K.WX_DRY)
    var city by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun useMyLocation() {
        busy = true
        status = "Finding you…"
        Weather.currentLocation(ctx) { loc ->
            busy = false
            if (loc != null) {
                Weather.savePlace(ctx, Weather.Place(loc.latitude.toFloat(), loc.longitude.toFloat(), "Current location"), "gps")
                Weather.refreshNow(ctx)
                status = null
            } else {
                status = "Couldn't get your location. Turn on Location, or search for a city."
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) status = "Location permission denied. You can search for a city instead."
        if (granted) useMyLocation()
    }

    GroupCard {
        SwitchPref("Weather Sync", "Rain follows the real weather where you are", sync) { on ->
            Weather.schedule(ctx)
            if (on) Weather.refreshNow(ctx)
        }
        if (sync.value) {
            RowDivider()
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                val now = if (updated.value == 0L) {
                    "Waiting for the first update"
                } else {
                    val t = DateFormat.getTimeFormat(ctx).format(Date(updated.value))
                    "${desc.value} (${Weather.levelLabel(level.value)}) · updated $t"
                }
                Text("Now: $now", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Location: " + place.value.ifEmpty { "not set" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, onClick = {
                        if (Weather.hasPermission(ctx)) useMyLocation()
                        else permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }) { Text("Use my location") }
                    OutlinedButton(onClick = { Weather.refreshNow(ctx) }) { Text("Refresh") }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = city,
                        onValueChange = { city = it },
                        label = { Text("Or search a city") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(enabled = city.isNotBlank() && !busy, onClick = {
                        busy = true
                        scope.launch {
                            val found = Weather.geocode(city)
                            busy = false
                            if (found != null) {
                                Weather.savePlace(ctx, found, "city")
                                Weather.refreshNow(ctx)
                                status = null
                                city = ""
                            } else {
                                status = "Couldn't find that place."
                            }
                        }
                    }) { Text("Find") }
                }
                status?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
            }
            RowDivider()
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text("When it's not raining", style = MaterialTheme.typography.titleMedium)
                val opts = listOf("clear" to "Clear glass", "droplets" to "Droplets", "keep" to "Keep rain")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    opts.forEachIndexed { i, (k, label) ->
                        SegmentedButton(
                            selected = dry.value == k,
                            onClick = { dry.value = k },
                            shape = SegmentedButtonDefaults.itemShape(i, opts.size),
                        ) { Text(label) }
                    }
                }
                Text(
                    "Light rain softens the effect and storms make it heavier. Your sliders set the normal \"rain\" look.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

@Suppress("unused")
private fun pct(v: Float) = "${(v * 100).roundToInt()}%"
