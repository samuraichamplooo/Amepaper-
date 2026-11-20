package dev.amepaper.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.amepaper.data.K
import dev.amepaper.data.Prefs

@Composable
fun SettingsScreen(pop: () -> Unit) {
    val ctx = LocalContext.current
    val dynamic = pref(K.DYNAMIC)
    val amoled = pref(K.AMOLED)
    val version = remember {
        try {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = pop) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SectionLabel("Appearance")
            GroupCard {
                SwitchPref(
                    "Dynamic color",
                    "Use colors from your system theme",
                    dynamic,
                    enabled = Build.VERSION.SDK_INT >= 31,
                )
                RowDivider()
                SwitchPref("Pure black", "Black background in dark mode", amoled)
            }

            SectionLabel("Wallpaper")
            GroupCard {
                NavRow("Set live wallpaper", "Open the system picker and apply Amepaper") { openWallpaperPicker(ctx) }
                RowDivider()
                NavRow("Reset rain settings", "Back to the default rain look") {
                    val e = Prefs.sp(ctx).edit()
                    listOf(K.RAIN_AMOUNT, K.DROPLETS, K.DROP_SIZE, K.FOG, K.REFRACTION, K.TRAILS, K.SPEED, K.IMPACTS, K.FALLING)
                        .forEach { e.putFloat(it.name, it.def) }
                    e.putInt(K.FPS.name, K.FPS.def)
                    e.apply()
                }
            }

            SectionLabel("About")
            GroupCard {
                Column(Modifier.padding(20.dp)) {
                    Text("Amepaper $version", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "雨 (ame) means rain. A rain-on-glass live wallpaper with an interval changer for your home and lock screens.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        "Weather data by Open-Meteo.com (CC BY 4.0). Shader hash functions by Dave Hoskins (MIT).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
