package dev.amepaper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import dev.amepaper.R
import dev.amepaper.data.Screen

sealed interface Route {
    data object Main : Route
    data class AlbumRoute(val id: String) : Route
    data class CropRoute(val albumId: String, val imageId: String, val screen: Screen = Screen.HOME) : Route
    data object Settings : Route
}

private class TabSpec(val label: String, val icon: Int)

private val TABS = listOf(
    TabSpec("Wallpaper", R.drawable.ic_tab_wallpaper),
    TabSpec("Rain", R.drawable.ic_tab_rain),
    TabSpec("Library", R.drawable.ic_tab_library),
)

@Composable
fun AppRoot() {
    var stack by remember { mutableStateOf(listOf<Route>(Route.Main)) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val push: (Route) -> Unit = { stack = stack + it }
    val pop: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }

    BackHandler(enabled = stack.size > 1) { pop() }

    when (val r = stack.last()) {
        Route.Main -> MainScreen(tab, { tab = it }, push)
        is Route.AlbumRoute -> AlbumScreen(r.id, push, pop)
        is Route.CropRoute -> CropScreen(r.albumId, r.imageId, r.screen, pop)
        Route.Settings -> SettingsScreen(pop)
    }
}

@Composable
private fun MainScreen(tab: Int, onTab: (Int) -> Unit, push: (Route) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Amepaper") },
                actions = {
                    IconButton(onClick = { push(Route.Settings) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                TABS.forEachIndexed { i, t ->
                    Tab(
                        selected = tab == i,
                        onClick = { onTab(i) },
                        text = { Text(t.label) },
                        icon = { Icon(painterResource(t.icon), contentDescription = null) },
                    )
                }
            }
            when (tab) {
                0 -> WallpaperTab(push)
                1 -> RainTab()
                else -> LibraryTab(push)
            }
        }
    }
}
