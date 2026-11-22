package dev.amepaper.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.amepaper.R
import dev.amepaper.data.Album
import dev.amepaper.data.Changer
import dev.amepaper.data.Importer
import dev.amepaper.data.Library
import dev.amepaper.data.Prefs
import dev.amepaper.data.Screen
import dev.amepaper.data.Screens
import dev.amepaper.data.WallImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryTab(push: (Route) -> Unit) {
    val albums by Library.albums.collectAsState()
    var creating by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 100.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(albums, key = { it.id }) { a ->
                AlbumCard(a) { push(Route.AlbumRoute(a.id)) }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { creating = true },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("New album") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (creating) {
        NameDialog("New album", "", onDone = { Library.createAlbum(it); creating = false }, onDismiss = { creating = false })
    }
}

@Composable
private fun AlbumCard(a: Album, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(10.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.8f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                val cover = a.images.firstOrNull()
                if (cover != null) {
                    WallThumb(cover, null, Modifier.fillMaxSize(), sizePx = 500)
                } else {
                    Icon(painterResource(R.drawable.ic_tab_library), contentDescription = null)
                }
            }
            Text(a.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 6.dp, top = 10.dp))
            Text(
                "${a.images.size} wallpaper" + if (a.images.size == 1) "" else "s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
            )
        }
    }
}

@Composable
fun AlbumScreen(albumId: String, push: (Route) -> Unit, pop: () -> Unit) {
    val ctx = LocalContext.current
    val albums by Library.albums.collectAsState()
    val album = albums.firstOrNull { it.id == albumId }
    if (album == null) {
        LaunchedEffect(Unit) { pop() }
        return
    }
    val scope = rememberCoroutineScope()
    var remaining by remember { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<WallImage?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        if (uris.isNotEmpty()) {
            remaining = uris.size
            scope.launch {
                for (u in uris) {
                    val img = withContext(Dispatchers.IO) { Importer.import(ctx, u) }
                    if (img != null) Library.addImage(albumId, img)
                    remaining -= 1
                }
            }
        }
    }

    val homeP = currentPlacement(Screen.HOME)
    val aspect = Screens.aspect(Prefs.sp(ctx), homeP).coerceIn(0.4f, 1.8f)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(album.name) },
                navigationIcon = {
                    IconButton(onClick = pop) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, contentDescription = "Rename") }
                    IconButton(onClick = { deleting = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete album") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(108.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Button(
                        onClick = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        enabled = remaining == 0,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text("  Add wallpapers")
                    }
                    if (remaining > 0) {
                        Text("Adding $remaining…", modifier = Modifier.padding(top = 10.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                    Text(
                        if (album.images.isEmpty()) "No wallpapers yet." else "Tap a wallpaper to zoom & crop it. Long-press for more.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, start = 4.dp),
                    )
                }
            }
            items(album.images, key = { it.id }) { img ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspect)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .combinedClickable(
                            onClick = { push(Route.CropRoute(albumId, img.id)) },
                            onLongClick = { menuFor = img },
                        ),
                ) {
                    WallThumb(img, cropOn(img, homeP), Modifier.fillMaxSize(), sizePx = 500)
                    if (img.hasCrop) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_crop),
                                contentDescription = "Cropped",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (renaming) {
        NameDialog("Rename album", album.name, onDone = { Library.renameAlbum(albumId, it); renaming = false }, onDismiss = { renaming = false })
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete \"${album.name}\"?") },
            text = { Text("Its ${album.images.size} wallpapers will be removed from Amepaper. Your original photos are not touched.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    Library.deleteAlbum(albumId)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
    menuFor?.let { img ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text("Wallpaper") },
            text = {
                Column {
                    MenuItem("Set on home screen") {
                        menuFor = null; applyWallpaper(ctx, albumId, img.id, listOf(Screen.HOME))
                    }
                    MenuItem("Set on lock screen") {
                        menuFor = null; applyWallpaper(ctx, albumId, img.id, listOf(Screen.LOCK))
                    }
                    MenuItem("Set on home & lock screens") {
                        menuFor = null; applyWallpaper(ctx, albumId, img.id, listOf(Screen.HOME, Screen.LOCK))
                    }
                    MenuItem("Zoom & crop") {
                        menuFor = null; push(Route.CropRoute(albumId, img.id))
                    }
                    MenuItem("Remove from album") {
                        Library.removeImage(albumId, img.id); menuFor = null
                    }
                }
            },
            confirmButton = { TextButton(onClick = { menuFor = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onDone(name.trim()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
