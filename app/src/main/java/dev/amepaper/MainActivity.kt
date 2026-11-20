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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Library.init(this)
        Weather.schedule(this)
        recordScreen()
        setContent {
            AmeTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot()
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        recordScreen()
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
