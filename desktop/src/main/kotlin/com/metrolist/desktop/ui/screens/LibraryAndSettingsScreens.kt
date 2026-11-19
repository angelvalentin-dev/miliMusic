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
import androidx.compose.material.icons.rounded.PlayArrow
import com.metrolist.desktop.ui.icons.Backup
import com.metrolist.desktop.ui.icons.FolderOpen
import com.metrolist.desktop.ui.icons.Restore
import com.metrolist.desktop.ui.icons.Sync
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.metrolist.desktop.data.DesktopAudioQuality
import com.metrolist.desktop.data.DesktopDarkMode
import com.metrolist.desktop.data.DesktopPrefKeys
import com.metrolist.desktop.db.DesktopAlarmEntry
import com.metrolist.desktop.db.DesktopSong
import com.metrolist.desktop.integrations.DesktopLastFmService
import com.metrolist.desktop.state.DesktopAppState
import com.metrolist.desktop.state.NavDestination
import com.metrolist.desktop.ui.components.ArtworkImage
import com.metrolist.desktop.ui.components.SongRowItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun LibraryScreen(
    initialTab: Int = 0,
    appState: DesktopAppState,
) {
    var selectedTab by remember(initialTab) { mutableStateOf(initialTab) }
    val tabs = listOf(
        "Favoritas",
        "Canciones",
        "Álbumes",
        "Artistas",
        "Playlists",
        "Descargas",
        "Historial",
        "Top 50",
        "En caché",
    )

    val likedSongs by appState.likedSongs.collectAsState()
    val librarySongs by appState.librarySongs.collectAsState()
    val downloadedSongs by appState.downloadedSongs.collectAsState()
    val topSongs by appState.topSongs.collectAsState()
    val cachedSongs by appState.cachedSongs.collectAsState()
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
                OutlinedButton(onClick = appState::openWrappedDialog) {
                    Text("Metrolist Wrapped ✨")
                }
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

        TabRow(selectedTabIndex = selectedTab.coerceIn(0, tabs.lastIndex)) {
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
                                        .semantics(mergeDescendants = true) {
                                            contentDescription = "Álbum ${album.title}, ${album.songCount} canciones"
                                        }
                                        .clickable(
                                            role = Role.Button,
                                            onClickLabel = "Abrir álbum ${album.title}",
                                        ) { appState.navigate(NavDestination.Album(album.id)) },
                                ) {
                                    Column(Modifier.padding(10.dp)) {
                                        ArtworkImage(album.thumbnailUrl, null, modifier = Modifier.fillMaxWidth().height(150.dp))
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
                                        .semantics(mergeDescendants = true) {
                                            contentDescription = "Artista ${artist.name}"
                                        }
                                        .clickable(
                                            role = Role.Button,
                                            onClickLabel = "Abrir artista ${artist.name}",
                                        ) { appState.navigate(NavDestination.Artist(artist.id)) },
                                ) {
                                    Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        ArtworkImage(artist.thumbnailUrl, null, isCircle = true, modifier = Modifier.size(130.dp))
                                        Spacer(Modifier.height(8.dp))
                                        Text(artist.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
                4 -> {
                    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            text = "Playlists Automáticas",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics { heading() },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AutoPlaylistCard("Mi Top 50", "${topSongs.size} canciones", Modifier.weight(1f)) {
                                selectedTab = 7
                            }
                            AutoPlaylistCard("En Caché", "${cachedSongs.size} canciones", Modifier.weight(1f)) {
                                selectedTab = 8
                            }
                            AutoPlaylistCard("Favoritas", "${likedSongs.size} canciones", Modifier.weight(1f)) {
                                selectedTab = 0
                            }
                            AutoPlaylistCard("Descargadas", "${downloadedSongs.size} canciones", Modifier.weight(1f)) {
                                selectedTab = 5
                            }
                        }
                        Text(
                            text = "Tus Playlists",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics { heading() },
                        )
                        if (playlists.isEmpty()) {
                            EmptyTabMessage("Crea una playlist para organizar tu música")
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(180.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                items(playlists) { pl ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .semantics(mergeDescendants = true) {
                                                contentDescription = "Playlist ${pl.name}, ${pl.songCount} canciones"
                                            }
                                            .clickable(
                                                role = Role.Button,
                                                onClickLabel = "Abrir playlist ${pl.name}",
                                            ) { appState.navigate(NavDestination.Playlist(pl.id, isLocal = pl.isLocal)) },
                                    ) {
                                        Column(Modifier.padding(10.dp)) {
                                            ArtworkImage(pl.thumbnailUrl, null, modifier = Modifier.fillMaxWidth().height(150.dp))
                                            Spacer(Modifier.height(8.dp))
                                            Text(pl.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${pl.songCount} canciones", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                5 -> SongListTab(downloadedSongs, pb.currentSong?.id, appState, "No tienes canciones descargadas")
                6 -> SongListTab(history, pb.currentSong?.id, appState, "Sin historial de reproducción")
                7 -> SongListTab(topSongs, pb.currentSong?.id, appState, "Reproduce canciones para generar tu Top 50")
                8 -> SongListTab(cachedSongs, pb.currentSong?.id, appState, "Aún no hay canciones guardadas en caché")
            }
        }
    }
}

@Composable
private fun AutoPlaylistCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .semantics(mergeDescendants = true) {
                contentDescription = "Playlist automática $title, $subtitle"
            }
            .clickable(
                role = Role.Button,
                onClickLabel = "Abrir playlist automática $title",
                onClick = onClick,
            ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    0xFFED5564.toInt() to "Rojo coral",
    0xFF3F8CFF.toInt() to "Azul",
    0xFF8B5CF6.toInt() to "Violeta",
    0xFF10B981.toInt() to "Verde esmeralda",
    0xFFF59E0B.toInt() to "Ámbar",
    0xFFEC4899.toInt() to "Rosa",
    0xFF06B6D4.toInt() to "Cian",
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
            Text(
                text = "Ajustes",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
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
                    AccentPaletteSwatches.forEach { (argb, colorName) ->
                        val isSelected = settings.selectedThemeColor == argb
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                    shape = CircleShape,
                                )
                                .semantics {
                                    contentDescription = "Color de tema $colorName"
                                    selected = isSelected
                                    stateDescription = if (isSelected) "Seleccionado" else "No seleccionado"
                                }
                                .clickable(
                                    role = Role.RadioButton,
                                    onClickLabel = "Seleccionar color de tema $colorName",
                                ) {
                                    scope.launch { appState.preferences.set(DesktopPrefKeys.SelectedThemeColor, argb) }
                                },
                        )
                    }
                }
            }
        }

        // Audio & Downloads
        item {
            SettingsCard(title = "Audio, Reproductor y Descargas") {
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

                SettingsSwitchRow(
                    title = "Cola infinita (Autoplay / Automix)",
                    subtitle = "Agrega automáticamente canciones similares cuando termina la cola",
                    checked = settings.autoplay,
                    onCheckedChange = { scope.launch { appState.preferences.set(DesktopPrefKeys.Autoplay, it) } },
                )

                SettingsSwitchRow(
                    title = "Persistencia de cola",
                    subtitle = "Recuerda la cola y la posición de reproducción al cerrar y abrir la app",
                    checked = settings.persistentQueue,
                    onCheckedChange = { scope.launch { appState.preferences.set(DesktopPrefKeys.PersistentQueue, it) } },
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = appState::openEqualizerDialog) {
                        Text("Ecualizador y Velocidad (${if (settings.eqEnabled) settings.eqPreset else "Off"} • ${"%.2f".format(settings.playbackSpeed).trimEnd('0').trimEnd('.')}x)")
                    }
                    OutlinedButton(onClick = appState::openSleepTimerDialog) {
                        Text("Temporizador (Sleep Timer)")
                    }
                }

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

        // Music Alarms
        item {
            val alarms by appState.alarmScheduler.alarms.collectAsState()
            val playlists by appState.playlists.collectAsState()
            var addAlarmOpen by remember { mutableStateOf(false) }

            if (addAlarmOpen) {
                var hourText by remember { mutableStateOf("07") }
                var minuteText by remember { mutableStateOf("00") }
                var selectedSource by remember { mutableStateOf("TOP") }
                var randomSong by remember { mutableStateOf(true) }
                val sourceOptions = buildList {
                    add("TOP" to "Mi Top 50")
                    add("LIKED" to "Canciones Favoritas")
                    add("DOWNLOADED" to "Canciones Descargadas")
                    add("CACHED" to "Canciones en Caché")
                    playlists.forEach { add(it.id to "Playlist: ${it.name}") }
                }

                AlertDialog(
                    onDismissRequest = { addAlarmOpen = false },
                    title = { Text("Nueva alarma musical", fontWeight = FontWeight.Bold) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.width(420.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = hourText,
                                    onValueChange = { hourText = it.filter(Char::isDigit).take(2) },
                                    label = { Text("Hora (0-23)") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = minuteText,
                                    onValueChange = { minuteText = it.filter(Char::isDigit).take(2) },
                                    label = { Text("Minuto (0-59)") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Text("Lista a reproducir:", style = MaterialTheme.typography.labelLarge)
                            LazyColumn(modifier = Modifier.height(140.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(sourceOptions) { (id, label) ->
                                    FilterChip(
                                        selected = selectedSource == id,
                                        onClick = { selectedSource = id },
                                        label = { Text(label) },
                                    )
                                }
                            }
                            SettingsSwitchRow(
                                title = "Orden aleatorio",
                                subtitle = "Mezclar las canciones al sonar la alarma",
                                checked = randomSong,
                                onCheckedChange = { randomSong = it },
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val h = (hourText.toIntOrNull() ?: 7).coerceIn(0, 23)
                                val m = (minuteText.toIntOrNull() ?: 0).coerceIn(0, 59)
                                val entry = DesktopAlarmEntry(
                                    id = UUID.randomUUID().toString(),
                                    enabled = true,
                                    hour = h,
                                    minute = m,
                                    playlistId = selectedSource,
                                    randomSong = randomSong,
                                )
                                appState.saveAlarms(alarms + entry)
                                addAlarmOpen = false
                                appState.showStatus("Alarma programada para las %02d:%02d".format(h, m))
                            },
                        ) { Text("Guardar alarma") }
                    },
                    dismissButton = {
                        TextButton(onClick = { addAlarmOpen = false }) { Text("Cancelar") }
                    },
                )
            }

            SettingsCard(title = "Alarmas musicales") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Reproduce automáticamente tu música a la hora programada (compatible con .backup de Android).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { addAlarmOpen = true }) {
                        Icon(Icons.Rounded.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Nueva alarma")
                    }
                }
                if (alarms.isEmpty()) {
                    Text("No tienes alarmas configuradas.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    alarms.forEach { alarm ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column {
                                    Text(
                                        text = "%02d:%02d".format(alarm.hour, alarm.minute),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.ExtraBold,
                                    )
                                    Text(
                                        text = "Fuente: ${alarm.playlistId}${if (alarm.randomSong) " • Aleatorio" else ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val alarmTimeStr = "%02d:%02d".format(alarm.hour, alarm.minute)
                                    OutlinedButton(onClick = { appState.alarmScheduler.triggerAlarm(alarm) }) {
                                        Text("Probar")
                                    }
                                    Switch(
                                        checked = alarm.enabled,
                                        onCheckedChange = { enabled ->
                                            appState.saveAlarms(alarms.map { if (it.id == alarm.id) it.copy(enabled = enabled) else it })
                                        },
                                        modifier = Modifier.semantics {
                                            contentDescription = "Alarma de las $alarmTimeStr"
                                            stateDescription = if (alarm.enabled) "Activada" else "Desactivada"
                                        },
                                    )
                                    TextButton(
                                        onClick = {
                                            appState.saveAlarms(alarms.filterNot { it.id == alarm.id })
                                        },
                                    ) {
                                        Icon(
                                            Icons.Rounded.Delete,
                                            contentDescription = "Eliminar alarma de las $alarmTimeStr",
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Integrations: Discord RPC, Last.fm, Listen Together
        item {
            val discordConnected by appState.discordRpc.connected.collectAsState()
            val ltState by appState.listenTogetherManager.state.collectAsState()

            var discordDetails by remember(settings.discordDetailsTemplate) { mutableStateOf(settings.discordDetailsTemplate) }
            var discordState by remember(settings.discordStateTemplate) { mutableStateOf(settings.discordStateTemplate) }
            var discordToken by remember(settings.discordToken) { mutableStateOf(settings.discordToken) }

            var lfmApiKey by remember(settings.lastFmApiKey) { mutableStateOf(settings.lastFmApiKey) }
            var lfmSecret by remember(settings.lastFmSecret) { mutableStateOf(settings.lastFmSecret) }
            var lfmUserInput by remember { mutableStateOf("") }
            var lfmPassInput by remember { mutableStateOf("") }

            SettingsCard(title = "Integraciones (Discord, Last.fm y Escuchar Juntos)") {
                // Listen Together
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Escuchar Juntos (Listen Together)", fontWeight = FontWeight.Bold)
                        Text(
                            text = if (ltState.roomCode.isNotBlank()) "En sala: ${ltState.roomCode} (${ltState.users.size} usuarios)" else "Sincroniza tu reproducción en tiempo real con amigos en Android o Windows",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = appState::openListenTogetherDialog) {
                        Text(if (ltState.roomCode.isNotBlank()) "Ver Sala (${ltState.roomCode})" else "Abrir Escuchar Juntos")
                    }
                }

                HorizontalDivider()

                // Discord Rich Presence
                SettingsSwitchRow(
                    title = "Discord Rich Presence",
                    subtitle = if (discordConnected) "Conectado a Discord" else "Muestra la canción actual en tu perfil de Discord (automático por IPC en Windows o con Token)",
                    checked = settings.enableDiscordRpc,
                    onCheckedChange = { scope.launch { appState.preferences.set(DesktopPrefKeys.EnableDiscordRPC, it) } },
                )
                if (settings.enableDiscordRpc) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = discordDetails,
                            onValueChange = {
                                discordDetails = it
                                scope.launch { appState.preferences.set(DesktopPrefKeys.DiscordDetailsTemplate, it) }
                            },
                            label = { Text("Plantilla Detalles ({song.name})") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = discordState,
                            onValueChange = {
                                discordState = it
                                scope.launch { appState.preferences.set(DesktopPrefKeys.DiscordStateTemplate, it) }
                            },
                            label = { Text("Plantilla Estado ({artist.name})") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OutlinedTextField(
                        value = discordToken,
                        onValueChange = {
                            discordToken = it
                            scope.launch { appState.preferences.set(DesktopPrefKeys.DiscordToken, it.trim()) }
                        },
                        label = { Text("Token de Discord (opcional si usas Discord Desktop en Windows)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                HorizontalDivider()

                // Last.fm Scrobbling
                SettingsSwitchRow(
                    title = "Last.fm Scrobbling",
                    subtitle = if (settings.lastFmSession.isNotBlank()) "Sesión activa como ${settings.lastFmUsername}" else "Envía tus reproducciones y Me gusta a tu perfil de Last.fm",
                    checked = settings.enableLastFmScrobbling,
                    onCheckedChange = { scope.launch { appState.preferences.set(DesktopPrefKeys.EnableLastFMScrobbling, it) } },
                )
                if (settings.enableLastFmScrobbling) {
                    SettingsSwitchRow(
                        title = "Actualizar 'Reproduciendo ahora'",
                        subtitle = "Notifica a Last.fm al empezar una canción",
                        checked = settings.lastFmUseNowPlaying,
                        onCheckedChange = { scope.launch { appState.preferences.set(DesktopPrefKeys.LastFMUseNowPlaying, it) } },
                    )
                    SettingsSwitchRow(
                        title = "Sincronizar Me gusta con Last.fm",
                        subtitle = "Marca Love/Unlove en Last.fm al dar Me gusta",
                        checked = settings.lastFmUseSendLikes,
                        onCheckedChange = { scope.launch { appState.preferences.set(DesktopPrefKeys.LastFMUseSendLikes, it) } },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = lfmApiKey,
                            onValueChange = {
                                lfmApiKey = it
                                scope.launch { appState.preferences.set(DesktopPrefKeys.LastFMApiKey, it.trim()) }
                            },
                            label = { Text("Last.fm API Key") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = lfmSecret,
                            onValueChange = {
                                lfmSecret = it
                                scope.launch { appState.preferences.set(DesktopPrefKeys.LastFMSecret, it.trim()) }
                            },
                            label = { Text("Last.fm Secret") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (settings.lastFmSession.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Conectado a Last.fm como: ${settings.lastFmUsername}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        appState.preferences.set(DesktopPrefKeys.LastFMSession, "")
                                        appState.preferences.set(DesktopPrefKeys.LastFMUsername, "")
                                        appState.showStatus("Sesión de Last.fm cerrada")
                                    }
                                },
                            ) {
                                Text("Cerrar sesión Last.fm")
                            }
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedTextField(
                                value = lfmUserInput,
                                onValueChange = { lfmUserInput = it },
                                label = { Text("Usuario Last.fm") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = lfmPassInput,
                                onValueChange = { lfmPassInput = it },
                                label = { Text("Contraseña Last.fm") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                enabled = lfmUserInput.isNotBlank() && lfmPassInput.isNotBlank(),
                                onClick = {
                                    scope.launch {
                                        val key = lfmApiKey.ifBlank { System.getenv("LASTFM_API_KEY").orEmpty() }
                                        val sec = lfmSecret.ifBlank { System.getenv("LASTFM_SECRET").orEmpty() }
                                        DesktopLastFmService.authenticateMobileSession(lfmUserInput, lfmPassInput, key, sec)
                                            .onSuccess { (user, sessionKey) ->
                                                appState.preferences.set(DesktopPrefKeys.LastFMUsername, user)
                                                appState.preferences.set(DesktopPrefKeys.LastFMSession, sessionKey)
                                                lfmPassInput = ""
                                                appState.showStatus("Conectado a Last.fm como $user")
                                            }
                                            .onFailure { e ->
                                                appState.showStatus("Error Last.fm: ${e.message}")
                                            }
                                    }
                                },
                            ) {
                                Text("Conectar")
                            }
                        }
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
                    text = "Atajos: Espacio (Reproducir/Pausar) • Ctrl+F (Buscar) • Ctrl+L (Letras) • Ctrl+Q (Cola) • Ctrl+E (Ecualizador) • Ctrl+←/→ (Anterior/Siguiente) • Alt+←/→ (Atrás/Adelante)",
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
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
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
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
