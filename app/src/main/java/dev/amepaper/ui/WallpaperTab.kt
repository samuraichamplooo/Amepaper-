package dev.amepaper.ui

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.amepaper.R
import dev.amepaper.data.Album
import dev.amepaper.data.BoolKey
import dev.amepaper.data.FloatKey
import dev.amepaper.data.Changer
import dev.amepaper.data.FxKeys
import dev.amepaper.data.IntKey
import dev.amepaper.data.K
import dev.amepaper.data.Library
import dev.amepaper.data.Prefs
import dev.amepaper.data.ScaleMode
import dev.amepaper.data.Screen
import dev.amepaper.data.Screens
import dev.amepaper.data.Placement
import dev.amepaper.data.WallImage
import dev.amepaper.data.get
import dev.amepaper.wallpaper.RainWallpaperService

fun isWallpaperActive(ctx: Context): Boolean =
    try {
        WallpaperManager.getInstance(ctx).wallpaperInfo?.packageName == ctx.packageName
    } catch (_: Exception) {
        false
    }

fun openWallpaperPicker(ctx: Context) {
    val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        .putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(ctx, RainWallpaperService::class.java),
        )
    try {
        ctx.startActivity(intent)
    } catch (_: Exception) {
        try {
            ctx.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        } catch (_: Exception) {
            Toast.makeText(ctx, "Couldn't open the wallpaper picker", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Shows a picture on the chosen screens right away (with its saved crop). If Amepaper isn't the
 * live wallpaper yet, opens the system picker so the change becomes visible.
 */
fun applyWallpaper(ctx: Context, albumId: String, imageId: String, screens: List<Screen>) {
    if (screens.isEmpty()) return
    val sp = Prefs.sp(ctx)
    if (Screen.LOCK in screens && Screen.HOME !in screens && sp.get(K.MIRROR)) {
        sp.edit().putBoolean(K.MIRROR.name, false).apply()
    }
    screens.forEach { Changer.show(ctx, it, albumId, imageId) }
    val where = when {
        screens.size > 1 -> "home & lock screens"
        screens.first() == Screen.HOME -> "home screen"
        else -> "lock screen"
    }
    if (isWallpaperActive(ctx)) {
        Toast.makeText(ctx, "Set on your $where", Toast.LENGTH_SHORT).show()
    } else {
        Toast.makeText(ctx, "Saved for your $where. Now apply Amepaper as your wallpaper.", Toast.LENGTH_LONG).show()
        openWallpaperPicker(ctx)
    }
}

@Composable
fun WallpaperTab(push: (Route) -> Unit) {
    val ctx = LocalContext.current
    val sp = remember { Prefs.sp(ctx) }
    val albums by Library.albums.collectAsState()

    val homeAlbum = pref(K.HOME_ALBUM)
    val lockAlbum = pref(K.LOCK_ALBUM)
    val homeImage = pref(K.HOME_IMAGE)
    val lockImage = pref(K.LOCK_IMAGE)
    val mirror = pref(K.MIRROR)
    val rotateHome = pref(K.ROTATE_HOME)
    val rotateLock = pref(K.ROTATE_LOCK)
    val changing = pref(K.CHANGING)
    val individual = pref(K.INDIVIDUAL)
    val shuffle = pref(K.SHUFFLE)
    val scale = pref(K.SCALE)
    val hscroll = pref(K.HSCROLL)
    val adaptive = pref(K.ADAPTIVE)

    // Read so the previews recompose when any of these change.
    val stamp = listOf(homeAlbum.value, lockAlbum.value, homeImage.value, lockImage.value, mirror.value)
    val curHome = remember(albums, stamp) { Changer.current(sp, Screen.HOME) }
    val curLock = remember(albums, stamp) { Changer.current(sp, Screen.LOCK) }
    val homeAlb = remember(albums, homeAlbum.value) { Changer.albumFor(sp, Screen.HOME) }
    val lockAlb = remember(albums, lockAlbum.value) { Changer.albumFor(sp, Screen.LOCK) }

    var pickFor by remember { mutableStateOf<Screen?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        ActivateBanner()
        CurrentWallpapersCard(lock = curLock, home = curHome) { img, screen ->
            val owner = albums.firstOrNull { a -> a.images.any { it.id == img.id } }
            if (owner != null) push(Route.CropRoute(owner.id, img.id, screen))
        }
        EngineStatus()

        Spacer(Modifier.height(16.dp))
        MultiChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                checked = rotateLock.value && !mirror.value,
                onCheckedChange = { rotateLock.value = it },
                enabled = !mirror.value,
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Lock") }
            SegmentedButton(
                checked = rotateHome.value,
                onCheckedChange = { rotateHome.value = it },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Home") }
        }

        Spacer(Modifier.height(16.dp))
        GroupCard {
            if (!mirror.value) {
                NavRow(lockAlb?.name ?: "No album", "Lock Album", leading = { SmallThumb(lockAlb?.images?.firstOrNull()) }) {
                    pickFor = Screen.LOCK
                }
                RowDivider()
            }
            NavRow(homeAlb?.name ?: "No album", "Home Album", leading = { SmallThumb(homeAlb?.images?.firstOrNull()) }) {
                pickFor = Screen.HOME
            }
            RowDivider()
            SwitchPref("Same wallpaper on both", "Lock screen shows whatever the home screen shows", mirror)
            RowDivider()
            SwitchPref(
                "Wallpaper changing",
                "Pause or resume scheduled changes without clearing your albums.",
                changing,
            ) { Changer.resetSchedule(ctx) }
            RowDivider()
            SwitchPref("Individual Scheduling", "Separate intervals for home and lock", individual) {
                Changer.resetSchedule(ctx)
            }
            RowDivider()
            if (individual.value) {
                IntervalEditor("Home interval", K.INTERVAL_HOME)
                RowDivider()
                IntervalEditor("Lock interval", K.INTERVAL_LOCK)
            } else {
                IntervalEditor("Time Interval", K.INTERVAL)
            }
            RowDivider()
            SwitchPref("Shuffle", null, shuffle)
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                Changer.changeNow(ctx)
                if (!isWallpaperActive(ctx)) {
                    Toast.makeText(ctx, "Set Amepaper as your wallpaper to see it", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Change wallpaper now") }

        SectionLabel("Scaling")
        GroupCard {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp)) {
                val modes = ScaleMode.entries
                modes.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = scale.value == m.name,
                        onClick = { scale.value = m.name },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    ) { Text(m.label) }
                }
            }
            RowDivider()
            SwitchPref(
                "Horizontal scrolling",
                "Allow wide Fill wallpapers to move between home-screen pages. Lock-screen wallpapers remain centered.",
                hscroll,
            )
        }
        Text(
            "Tip: tap a wallpaper above (or in Library) to zoom in or out, crop, or reshape it for the cover and main screens.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp, top = 8.dp, end = 6.dp),
        )

        SectionLabel("Visual Effects")
        EffectCard("Brightness Effect", "Change the image brightness", { it.brightOn }, { it.brightAmt }, 0.1f..1f, true)
        Spacer(Modifier.height(12.dp))
        EffectCard("Blur Effect", "Add blur to the image", { it.blurOn }, { it.blurAmt }, 0.05f..1f, false)
        Spacer(Modifier.height(12.dp))
        EffectCard("Vignette Effect", "Darken the edges of the image", { it.vigOn }, { it.vigAmt }, 0.05f..1f, true)
        Spacer(Modifier.height(12.dp))
        EffectCard("Gray Filter", "Make the colors grayscale", { it.grayOn }, { it.grayAmt }, 0.05f..1f, true)
        Spacer(Modifier.height(12.dp))
        GroupCard {
            SwitchPref("Adaptive Brightness", "Dim wallpapers a little when the system is in dark mode", adaptive)
        }
        Spacer(Modifier.height(32.dp))
    }

    pickFor?.let { s ->
        AlbumPickerDialog(
            albums = albums,
            title = if (s == Screen.HOME) "Home album" else "Lock album",
            onPick = { id ->
                if (s == Screen.HOME) homeAlbum.value = id else lockAlbum.value = id
                pickFor = null
            },
            onDismiss = { pickFor = null },
        )
    }
}

/** "amepaper", "home" (lock shows the home wallpaper), "other" or null if unknown. */
fun lockScreenSource(ctx: Context): String? = try {
    val wm = WallpaperManager.getInstance(ctx)
    val lockId = wm.getWallpaperId(WallpaperManager.FLAG_LOCK)
    when {
        lockId < 0 -> "home"
        android.os.Build.VERSION.SDK_INT >= 34 ->
            if (wm.getWallpaperInfo(WallpaperManager.FLAG_LOCK)?.packageName == ctx.packageName) "amepaper" else "other"
        else -> "other"
    }
} catch (_: Exception) {
    null
}

@Composable
private fun EngineStatus() {
    val ctx = LocalContext.current
    val status = pref(K.ENGINE_STATUS).value
    val seen = pref(K.ENGINE_SEEN).value
    var homeActive by remember { mutableStateOf(isWallpaperActive(ctx)) }
    var lock by remember { mutableStateOf(lockScreenSource(ctx)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                homeActive = isWallpaperActive(ctx)
                lock = lockScreenSource(ctx)
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val lockText = when {
        lock == "amepaper" || (lock == "home" && homeActive) -> "Amepaper"
        lock == "home" -> "same as home (not Amepaper)"
        lock == "other" -> "its own wallpaper, not Amepaper"
        else -> "unknown"
    }
    val lastText = if (seen == 0L) {
        "The live wallpaper hasn't drawn anything yet."
    } else {
        val t = android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(seen))
        "Last drawn $t: $status"
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                "Home screen: " + (if (homeActive) "Amepaper" else "not Amepaper") + "  ·  Lock screen: $lockText",
                style = MaterialTheme.typography.bodySmall,
                color = if (homeActive) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            Text(
                lastText,
                style = MaterialTheme.typography.bodySmall,
                color = if (status.contains("ERROR")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ActivateBanner() {
    val ctx = LocalContext.current
    var active by remember { mutableStateOf(isWallpaperActive(ctx)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) active = isWallpaperActive(ctx)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    if (active) return
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Amepaper isn't your wallpaper yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Set it once and choose \"Home and lock screens\". After that, rotation and rain run on their own.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Button(onClick = { openWallpaperPicker(ctx) }) { Text("Set wallpaper") }
        }
    }
}

@Composable
private fun CurrentWallpapersCard(lock: WallImage?, home: WallImage?, onEdit: (WallImage, Screen) -> Unit) {
    val ctx = LocalContext.current
    val homeP = currentPlacement(Screen.HOME)
    val lockP = currentPlacement(Screen.LOCK)
    // read so the frames reshape after the first visit to the other screen
    val tall = pref(K.ASPECT_TALL).value
    val wide = pref(K.ASPECT_WIDE).value
    val aspect = remember(homeP, tall, wide) { Screens.aspect(Prefs.sp(ctx), homeP) }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "Current Wallpapers",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                val perFrame = (maxWidth - 24.dp) / 2
                var fw: Dp = perFrame * 0.86f
                var fh: Dp = fw / aspect
                val maxH = 330.dp
                if (fh > maxH) {
                    fh = maxH
                    fw = fh * aspect
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    PhoneFrame(lock, lockP, fw, fh, "Lock") { onEdit(it, Screen.LOCK) }
                    PhoneFrame(home, homeP, fw, fh, "Home") { onEdit(it, Screen.HOME) }
                }
            }
            Text(
                "Tap a wallpaper to zoom & crop it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PhoneFrame(img: WallImage?, placement: Placement, w: Dp, h: Dp, label: String, onEdit: (WallImage) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val shape = RoundedCornerShape(min(w, h) * 0.1f)
        Box(
            Modifier
                .size(w, h)
                .clip(shape)
                .background(Color.Black)
                .border(3.dp, Color.Black, shape)
                .clickable(enabled = img != null) { if (img != null) onEdit(img) },
            contentAlignment = Alignment.Center,
        ) {
            if (img != null) {
                WallThumb(img, cropOn(img, placement), Modifier.fillMaxSize())
            } else {
                Text("Add images\nin Library", color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (img != null) {
                Icon(
                    painterResource(R.drawable.ic_crop),
                    contentDescription = "Zoom & crop",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(start = 6.dp).size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun IntervalEditor(title: String, key: IntKey) {
    val ctx = LocalContext.current
    val handle = pref(key)
    val total = handle.value
    var days by remember(key.name) { mutableStateOf((total / 1440).toString()) }
    var hours by remember(key.name) { mutableStateOf(((total % 1440) / 60).toString()) }
    var mins by remember(key.name) { mutableStateOf((total % 60).toString()) }

    fun commit() {
        val d = days.toIntOrNull() ?: 0
        val h = hours.toIntOrNull() ?: 0
        val m = mins.toIntOrNull() ?: 0
        val t = (d * 1440 + h * 60 + m).coerceAtLeast(1)
        if (t != handle.value) {
            handle.value = t
            Changer.resetSchedule(ctx)
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NumberField("Days", days, Modifier.weight(1f)) { days = it.filter { c -> c.isDigit() }.take(3); commit() }
            NumberField("Hours", hours, Modifier.weight(1f)) { hours = it.filter { c -> c.isDigit() }.take(2); commit() }
            NumberField("Mins", mins, Modifier.weight(1f)) { mins = it.filter { c -> c.isDigit() }.take(2); commit() }
        }
        Text(
            "Total: " + Changer.formatMinutes(handle.value),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun EffectCard(
    title: String,
    subtitle: String,
    on: (FxKeys) -> BoolKey,
    amount: (FxKeys) -> FloatKey,
    range: ClosedFloatingPointRange<Float>,
    live: Boolean,
) {
    val homeOn = pref(on(K.FX_HOME))
    val homeAmt = pref(amount(K.FX_HOME))
    val lockOn = pref(on(K.FX_LOCK))
    val lockAmt = pref(amount(K.FX_LOCK))
    GroupCard {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SwitchPref("Home", null, homeOn)
        if (homeOn.value) SliderPref("Home amount", homeAmt, range, live)
        SwitchPref("Lock", null, lockOn)
        if (lockOn.value) SliderPref("Lock amount", lockAmt, range, live)
    }
}

@Composable
fun AlbumPickerDialog(
    albums: List<Album>,
    title: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                albums.forEach { a ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(a.id) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        SmallThumb(a.images.firstOrNull())
                        Column {
                            Text(a.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${a.images.size} wallpaper" + if (a.images.size == 1) "" else "s",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    "Make new albums in the Library tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
