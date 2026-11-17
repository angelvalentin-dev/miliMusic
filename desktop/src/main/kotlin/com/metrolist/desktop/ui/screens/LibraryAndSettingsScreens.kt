package com.metrolist.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.data.DesktopAudioQuality
import com.metrolist.desktop.data.DesktopDarkMode
import com.metrolist.desktop.data.DesktopPrefKeys
import com.metrolist.desktop.db.DesktopSong
import com.metrolist.desktop.state.DesktopAppState
import com.metrolist.desktop.state.NavDestination
import com.metrolist.desktop.ui.components.ArtworkImage
import com.metrolist.desktop.ui.components.SongRowItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun LibraryScreen(
    initialTab: Int = 0,
    appState: DesktopAppState,
) {
    var selectedTab by remember(initialTab) { mutableStateOf(initialTab) }
    val tabs = listOf("Favoritas", "Canciones", "Álbumes", "Artistas", "Playlists", "Descargas", "Historial")

    val likedSongs by appState.likedSongs.collectAsState()
    val librarySongs by appState.librarySongs.collectAsState()
    val downloadedSongs by appState.downloadedSongs.collectAsState()
    val playlists by appState.playlists.collectAsState()
    val albums by appState.bookmarkedAlbums.collectAsState()
    val artists by appState.bookmarkedArtists.collectAsState()
    val history by appState.recentlyPlayed.collectAsState()
    val pb by appState.playbackState.collectAsState()
    val settings by appState.settings.collectAsState()

    var createPlaylistOpen by remember { mutableStateOf(false) }

    if (createPlaylistOpen) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { createPlaylistOpen = false },
            title = { Text("Nueva playlist") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre de la playlist") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    enabled = name.isNotBlank(),
                    onClick = {
                        appState.database.createPlaylist(name)
                        createPlaylistOpen = false
                    },
                ) { Text("Crear") }
            },
            dismissButton = {
                TextButton(onClick = { createPlaylistOpen = false }) { Text("Cancelar") }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Tu Biblioteca", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (settings.isLoggedIn) {
                    OutlinedButton(onClick = appState::syncLibraryWithYtm) {
                        Icon(Icons.Rounded.Sync, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Sincronizar YTM")
                    }
                }
                Button(onClick = { createPlaylistOpen = true }) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Nueva playlist")
                }
            }
        }

        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { idx, label ->
                Tab(
                    selected = selectedTab == idx,
                    onClick = { selectedTab = idx },
                    text = { Text(label) },
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            when (selectedTab) {
                0 -> SongListTab(likedSongs, pb.currentSong?.id, appState, "Aún no tienes canciones favoritas")
                1 -> SongListTab(librarySongs, pb.currentSong?.id, appState, "Tu biblioteca de canciones está vacía")
                2 -> {
                    if (albums.isEmpty()) {
                        EmptyTabMessage("No hay álbumes guardados")
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(170.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(albums) { album ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { appState.navigate(NavDestination.Album(album.id)) },
                                ) {
                                    Column(Modifier.padding(10.dp)) {
                                        ArtworkImage(album.thumbnailUrl, album.title, modifier = Modifier.fillMaxWidth().height(150.dp))
                                        Spacer(Modifier.height(8.dp))
                                        Text(album.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${album.songCount} canciones", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
                3 -> {
                    if (artists.isEmpty()) {
                        EmptyTabMessage("No sigues a ningún artista aún")
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(170.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(artists) { artist ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { appState.navigate(NavDestination.Artist(artist.id)) },
                                ) {
                                    Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        ArtworkImage(artist.thumbnailUrl, artist.name, isCircle = true, modifier = Modifier.size(130.dp))
                                        Spacer(Modifier.height(8.dp))
                                        Text(artist.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
                4 -> {
                    if (playlists.isEmpty()) {
                        EmptyTabMessage("Crea una playlist para organizar tu música")
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(180.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(playlists) { pl ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { appState.navigate(NavDestination.Playlist(pl.id, isLocal = pl.isLocal)) },
                                ) {
                                    Column(Modifier.padding(10.dp)) {
                                        ArtworkImage(pl.thumbnailUrl, pl.name, modifier = Modifier.fillMaxWidth().height(150.dp))
                                        Spacer(Modifier.height(8.dp))
                                        Text(pl.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${pl.songCount} canciones", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
                5 -> SongListTab(downloadedSongs, pb.currentSong?.id, appState, "No tienes canciones descargadas")
                6 -> SongListTab(history, pb.currentSong?.id, appState, "Sin historial de reproducción")
            }
        }
    }
}

@Composable
private fun SongListTab(
    songs: List<DesktopSong>,
    currentId: String?,
    appState: DesktopAppState,
    emptyText: String,
) {
    if (songs.isEmpty()) {
        EmptyTabMessage(emptyText)
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.padding(bottom = 10.dp)) {
            Button(onClick = { appState.audioPlayer.playQueue(songs, 0) }) {
                Icon(Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text("Reproducir todo (${songs.size})")
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(songs) { idx, song ->
                SongRowItem(
                    song = song,
                    appState = appState,
                    index = idx,
                    isCurrent = currentId == song.id,
                    onPlay = { appState.audioPlayer.playQueue(songs, idx) },
                )
            }
        }
    }
}

@Composable
private fun EmptyTabMessage(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val AccentPaletteSwatches = listOf(
    0xFFED5564.toInt(),
    0xFF3F8CFF.toInt(),
    0xFF8B5CF6.toInt(),
    0xFF10B981.toInt(),
    0xFFF59E0B.toInt(),
    0xFFEC4899.toInt(),
    0xFF06B6D4.toInt(),
)

@Composable
fun SettingsScreen(appState: DesktopAppState) {
    val settings by appState.settings.collectAsState()
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Text("Ajustes", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }

        // Account section
        item {
            SettingsCard(title = "Cuenta de YouTube Music") {
                if (settings.isLoggedIn) {
                    Text(
                        text = settings.accountName.ifBlank { "Cuenta conectada" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (settings.accountEmail.isNotBlank()) {
                        Text(settings.accountEmail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = appState::syncLibraryWithYtm) {
                            Icon(Icons.Rounded.Sync, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Sincronizar ahora")
                        }
                        OutlinedButton(onClick = appState::logout) {
                            Text("Cerrar sesión")
                        }
                    }
                } else {
                    Text("Inicia sesión para sincronizar tus playlists, Me gusta y recomendaciones.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = appState::openLoginDialog) {
                        Text("Iniciar sesión con YouTube Music")
                    }
                }
            }
        }

        // Appearance
        item {
            SettingsCard(title = "Apariencia") {
                Text("Tema", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DesktopDarkMode.entries.forEach { mode ->
                        val label = when (mode) {
                            DesktopDarkMode.SYSTEM -> "Sistema"
                            DesktopDarkMode.LIGHT -> "Claro"
                            DesktopDarkMode.DARK -> "Oscuro"
                        }
                        FilterChip(
                            selected = settings.darkMode == mode,
                            onClick = { scope.launch { appState.preferences.set(DesktopPrefKeys.DarkMode, mode.name) } },
                            label = { Text(label) },
                        )
                    }
                }

                SettingsSwitchRow(
                    title = "Negro puro (OLED)",
                    subtitle = "Usa negro absoluto en modo oscuro",
                    checked = settings.pureBlack,
                    onCheckedChange = { scope.launch { appState.preferences.set(DesktopPrefKeys.PureBlack, it) } },
                )

                SettingsSwitchRow(
                    title = "Color dinámico de carátula",
                    subtitle = "Adapta la paleta Material 3 a la canción actual",
                    checked = settings.dynamicTheme,
                    onCheckedChange = { scope.launch { appState.preferences.set(DesktopPrefKeys.DynamicTheme, it) } },
                )

                Text("Color base", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AccentPaletteSwatches.forEach { argb ->
                        val selected = settings.selectedThemeColor == argb
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                    shape = CircleShape,
                                )
                                .clickable {
                                    scope.launch { appState.preferences.set(DesktopPrefKeys.SelectedThemeColor, argb) }
                                },
                        )
                    }
                }
            }
        }

        // Audio & Downloads
        item {
            SettingsCard(title = "Audio y Descargas") {
                Text("Calidad de audio", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DesktopAudioQuality.entries.forEach { q ->
                        val label = when (q) {
                            DesktopAudioQuality.AUTO -> "Automática"
                            DesktopAudioQuality.HIGH -> "Alta"
                            DesktopAudioQuality.LOW -> "Baja"
                        }
                        FilterChip(
                            selected = settings.audioQuality == q,
                            onClick = { scope.launch { appState.preferences.set(DesktopPrefKeys.AudioQuality, q.name) } },
                            label = { Text(label) },
                        )
                    }
                }

                SettingsSwitchRow(
                    title = "Caché de canciones",
                    subtitle = "Guarda las canciones reproducidas para reproducción instantánea",
                    checked = settings.enableSongCache,
                    onCheckedChange = { scope.launch { appState.preferences.set(DesktopPrefKeys.EnableSongCache, it) } },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Carpeta de descargas", fontWeight = FontWeight.Medium)
                        Text(settings.downloadsDir, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            val chosen = withContext(Dispatchers.Main) {
                                val chooser = JFileChooser(File(settings.downloadsDir)).apply {
                                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                    dialogTitle = "Seleccionar carpeta de descargas"
                                }
                                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                    chooser.selectedFile.absolutePath
                                } else null
                            }
                            if (chosen != null) {
                                appState.preferences.set(DesktopPrefKeys.DownloadsDir, chosen)
                            }
                        }
                    }) {
                        Icon(Icons.Rounded.FolderOpen, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Cambiar")
                    }
                }
            }
        }

        // Lyrics
        item {
            SettingsCard(title = "Letras") {
                SettingsSwitchRow(
                    title = "LrcLib",
                    subtitle = "Letras sincronizadas de la comunidad",
                    checked = settings.enableLrcLib,
                    onCheckedChange = { scope.launch { appState.preferences.set(DesktopPrefKeys.EnableLrcLib, it) } },
                )
                SettingsSwitchRow(
                    title = "BetterLyrics",
                    subtitle = "Letras sincronizadas enriquecidas",
                    checked = settings.enableBetterLyrics,
                    onCheckedChange = { scope.launch { appState.preferences.set(DesktopPrefKeys.EnableBetterLyrics, it) } },
                )
            }
        }

        // Windows integration
        item {
            SettingsCard(title = "Integración con Windows") {
                SettingsSwitchRow(
                    title = "Minimizar a la bandeja del sistema",
                    subtitle = "Mantener reproducción en segundo plano al cerrar la ventana",
                    checked = settings.minimizeToTray,
                    onCheckedChange = { scope.launch { appState.preferences.set(DesktopPrefKeys.MinimizeToTray, it) } },
                )
                SettingsSwitchRow(
                    title = "Controles multimedia de Windows (SMTC)",
                    subtitle = "Muestra carátula y responde a teclas físicas Play/Pause/Next/Prev",
                    checked = settings.enableSmtc,
                    onCheckedChange = { scope.launch { appState.preferences.set(DesktopPrefKeys.EnableSmtc, it) } },
                )
                Text(
                    text = "Atajos: Espacio (Reproducir/Pausar) • Ctrl+F (Buscar) • Ctrl+L (Letras) • Ctrl+Q (Cola) • Ctrl+←/→ (Anterior/Siguiente) • Alt+←/→ (Atrás/Adelante)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Backup & Restore (.backup compatible with Android)
        item {
            SettingsCard(title = "Copia de seguridad y Restauración (.backup)") {
                Text(
                    "Compatible con archivos .backup exportados desde miliMusic en Android.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        scope.launch {
                            val target = withContext(Dispatchers.Main) {
                                val chooser = JFileChooser().apply {
                                    dialogTitle = "Exportar copia de seguridad"
                                    selectedFile = File("miliMusic.backup")
                                    fileFilter = FileNameExtensionFilter("Metrolist/miliMusic Backup (*.backup, *.zip)", "backup", "zip")
                                }
                                if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
                            } ?: return@launch
                            appState.backupService.exportBackup(target)
                                .onSuccess { appState.showStatus("Backup guardado en ${target.name}") }
                                .onFailure { appState.showStatus("Error al exportar: ${it.message}") }
                        }
                    }) {
                        Icon(Icons.Rounded.Backup, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Exportar .backup")
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            val source = withContext(Dispatchers.Main) {
                                val chooser = JFileChooser().apply {
                                    dialogTitle = "Importar copia de seguridad"
                                    fileFilter = FileNameExtensionFilter("Metrolist/miliMusic Backup (*.backup, *.zip)", "backup", "zip")
                                }
                                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
                            } ?: return@launch
                            appState.backupService.importBackup(source)
                                .onSuccess { appState.showStatus("Copia de seguridad restaurada") }
                                .onFailure { appState.showStatus("Error al importar: ${it.message}") }
                        }
                    }) {
                        Icon(Icons.Rounded.Restore, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Importar .backup")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
