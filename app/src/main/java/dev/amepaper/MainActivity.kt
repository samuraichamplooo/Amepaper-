package dev.amepaper

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import dev.amepaper.data.Library
import dev.amepaper.data.Screens
import dev.amepaper.ui.AmeTheme
import dev.amepaper.ui.AppRoot
import dev.amepaper.weather.Weather
import android.content.SharedPreferences
import dev.amepaper.data.K
import dev.amepaper.data.Prefs
import dev.amepaper.wallpaper.StaticWall

class MainActivity : ComponentActivity() {
    /** In static mode, any change to pictures, framing or effects updates the wallpaper. */
    private val staticSync = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
        if (key == null || !StaticWall.enabled(sp)) return@OnSharedPreferenceChangeListener
        if (key in K.IMAGE_KEYS || key in K.LIGHT_FX_KEYS || key == K.REFRESH.name) StaticWall.applySoon(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Library.init(this)
        Weather.schedule(this)
        recordScreen()
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(staticSync)
        setContent {
            AmeTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // catches a due change and a fold/unfold since last time
        if (StaticWall.enabled(Prefs.sp(this))) StaticWall.applySoon(this)
    }

    override fun onDestroy() {
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(staticSync)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        recordScreen()
        if (StaticWall.enabled(Prefs.sp(this))) StaticWall.applySoon(this)
    }

    /** Folded = cover screen, unfolded = main screen: remember both shapes for cropping. */
    private fun recordScreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = windowManager.maximumWindowMetrics.bounds
            Screens.record(this, b.width(), b.height())
        } else {
            val dm = resources.displayMetrics
            Screens.record(this, dm.widthPixels, dm.heightPixels)
        }
    }
}
