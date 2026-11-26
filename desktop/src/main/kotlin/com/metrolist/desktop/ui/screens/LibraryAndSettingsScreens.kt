package com.metrolist.desktop.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.db.DesktopHistoryEntry
import com.metrolist.desktop.db.DesktopSong
import com.metrolist.desktop.db.toDesktopSong
import com.metrolist.desktop.state.DesktopAppState
import com.metrolist.desktop.state.NavDestination
import com.metrolist.desktop.ui.components.ArtworkImage
import com.metrolist.desktop.ui.components.SongRowItem
import com.metrolist.desktop.ui.icons.Shuffle
import com.metrolist.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

private enum class LibrarySort(val label: String) {
    DEFAULT("Recientes"),
    TITLE("Título"),
    ARTIST("Artista"),
    PLAY_TIME("Más escuchadas"),
    DURATION("Duración"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(initialTab: Int, appState: DesktopAppState) {
    val settings by appState.settings.collectAsState()
    val pb by appState.playbackState.collectAsState()
    val likedSongs by appState.likedSongs.collectAsState()
    val librarySongs by appState.librarySongs.collectAsState()
    val downloadedSongs by appState.downloadedSongs.collectAsState()
    val uploadedSongs by appState.uploadedSongs.collectAsState()
    val topSongs by appState.topSongs.collectAsState()
    val cachedSongs by appState.cachedSongs.collectAsState()
    val playlists by appState.playlists.collectAsState()
    val albums by appState.bookmarkedAlbums.collectAsState()
    val artists by appState.bookmarkedArtists.collectAsState()
    val podcasts by appState.bookmarkedPodcasts.collectAsState()
    val recentlyPlayed by appState.recentlyPlayed.collectAsState()
    val scope = rememberCoroutineScope()

    val tabs = listOf(
        "Mix",          // 0
        "Favoritas",    // 1
        "Canciones",    // 2
        "Álbumes",      // 3
        "Artistas",     // 4
        "Playlists",    // 5
        "Podcasts",     // 6
        "Subidas",      // 7
        "Descargas",    // 8
        "Historial",    // 9
        "Top ${settings.topSize}", // 10
        "En caché",     // 11
    )
    // Keep backwards compatibility if sidebar passes 5 for Downloads or 0 for Library
    var selectedTab by remember(initialTab) { mutableStateOf(initialTab.coerceIn(0, tabs.lastIndex)) }
    var filterQuery by remember { mutableStateOf("") }
    var sortBy by remember { mutableStateOf(LibrarySort.DEFAULT) }
    var sortDescending by remember { mutableStateOf(false) }
    var gridView by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var createPlaylistOpen by remember { mutableStateOf(false) }

    // History state (Local vs Remote YTM)
    var remoteHistoryMode by remember { mutableStateOf(false) }
    var localHistoryEntries by remember { mutableStateOf<List<DesktopHistoryEntry>>(emptyList()) }
    var remoteHistorySongs by remember { mutableStateOf<List<DesktopSong>>(emptyList()) }
    var loadingRemoteHistory by remember { mutableStateOf(false) }

    DisposableEffect(createPlaylistOpen) {
        appState.setLocalModalOpen(createPlaylistOpen)
        onDispose { appState.setLocalModalOpen(false) }
    }

    LaunchedEffect(selectedTab, remoteHistoryMode, recentlyPlayed.size) {
        if (selectedTab == 9) {
            if (!remoteHistoryMode) {
                localHistoryEntries = withContext(Dispatchers.IO) { appState.database.historyEntries(150) }
            } else if (settings.isLoggedIn) {
                loadingRemoteHistory = true
                remoteHistorySongs = withContext(Dispatchers.IO) {
                    YouTube.musicHistory().getOrNull()?.sections.orEmpty()
                        .flatMap { it.songs }
                        .map { it.toDesktopSong() }
                }
                loadingRemoteHistory = false
            }
        }
    }

    fun applySongFilterAndSort(input: List<DesktopSong>): List<DesktopSong> {
        val filtered = if (filterQuery.isBlank()) input else input.filter {
            it.title.contains(filterQuery, true) || it.artistDisplay.contains(filterQuery, true) || it.albumName.orEmpty().contains(filterQuery, true)
        }
        val sorted = when (sortBy) {
            LibrarySort.DEFAULT -> filtered
            LibrarySort.TITLE -> filtered.sortedBy { it.title.lowercase() }
            LibrarySort.ARTIST -> filtered.sortedBy { it.artistDisplay.lowercase() }
            LibrarySort.PLAY_TIME -> filtered.sortedByDescending { it.totalPlayTime }
            LibrarySort.DURATION -> filtered.sortedByDescending { it.duration }
        }
        return if (sortDescending) sorted.reversed() else sorted
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Biblioteca",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectedTab == 7 && settings.isLoggedIn) {
                    Button(onClick = {
                        val chooser = JFileChooser().apply {
                            isMultiSelectionEnabled = true
                            fileFilter = FileNameExtensionFilter("Audio (MP3, FLAC, M4A, OGG, WMA)", "mp3", "flac", "m4a", "ogg", "wma", "wav")
                        }
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            appState.uploadAudioFilesToYtm(chooser.selectedFiles.toList())
                        }
                    }) { Text("Subir audio a YTM") }
                }
                if (settings.isLoggedIn) {
                    OutlinedButton(onClick = { appState.syncLibraryWithYtm() }) {
                        Text("Sincronizar YTM")
                    }
                }
                Button(onClick = { createPlaylistOpen = true }) {
                    Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Nueva playlist")
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tabs.forEachIndexed { idx, label ->
                FilterChip(
                    selected = selectedTab == idx,
                    onClick = {
                        selectedTab = idx
                        selectionMode = false
                        selectedIds.clear()
                    },
                    label = { Text(label) },
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = filterQuery,
                onValueChange = { filterQuery = it },
                placeholder = { Text("Filtrar en ${tabs[selectedTab]}...") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            if (selectedTab in listOf(1, 2, 7, 8, 10, 11)) {
                FilterChip(
                    selected = selectionMode,
                    onClick = {
                        selectionMode = !selectionMode
                        if (!selectionMode) selectedIds.clear()
                    },
                    label = { Text(if (selectionMode) "Selección (${selectedIds.size})" else "Seleccionar") },
                )
            }
            if (selectedTab in listOf(3, 4, 5, 6)) {
                FilterChip(selected = gridView, onClick = { gridView = !gridView }, label = { Text(if (gridView) "Cuadrícula" else "Lista") })
            }
        }

        if (selectedTab in listOf(1, 2, 7, 8, 10, 11)) {
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LibrarySort.entries.forEach { s ->
                    FilterChip(selected = sortBy == s, onClick = { sortBy = s }, label = { Text(s.label) })
                }
                FilterChip(selected = sortDescending, onClick = { sortDescending = !sortDescending }, label = { Text(if (sortDescending) "Desc" else "Asc") })
            }
        }

        Spacer(Modifier.height(10.dp))

        when (selectedTab) {
            0 -> {
                // Mix: Auto-Playlists + Playlists + Albums + Artists
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text("Listas automáticas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (settings.showLikedPlaylist) AutoPlaylistCard("Favoritas", "${likedSongs.size} canciones") { selectedTab = 1 }
                            if (settings.showDownloadedPlaylist) AutoPlaylistCard("Descargas", "${downloadedSongs.size} canciones") { selectedTab = 8 }
                            if (settings.showTopPlaylist) AutoPlaylistCard("Top ${settings.topSize}", "${topSongs.size} canciones") { selectedTab = 10 }
                            if (settings.showCachedPlaylist) AutoPlaylistCard("En caché", "${cachedSongs.size} canciones") { selectedTab = 11 }
                            if (settings.showUploadedPlaylist) AutoPlaylistCard("Subidas", "${uploadedSongs.size} canciones") { selectedTab = 7 }
                        }
                    }
                    if (playlists.isNotEmpty()) {
                        item { Text("Playlists (${playlists.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                        items(playlists.filter { filterQuery.isBlank() || it.name.contains(filterQuery, true) }) { pl ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { appState.navigate(NavDestination.Playlist(pl.id, isLocal = pl.isLocal)) }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ArtworkImage(pl.thumbnailUrl, pl.name, modifier = Modifier.size(48.dp))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(pl.name, fontWeight = FontWeight.SemiBold)
                                    Text("${pl.songCount} canciones • ${if (pl.isLocal) "Local" else "YouTube Music"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (albums.isNotEmpty()) {
                        item { Text("Álbumes guardados (${albums.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                        items(albums.filter { filterQuery.isBlank() || it.title.contains(filterQuery, true) }) { alb ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { appState.navigate(NavDestination.Album(alb.id)) }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ArtworkImage(alb.thumbnailUrl, alb.title, modifier = Modifier.size(48.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(alb.title, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            1 -> LibrarySongListSection(applySongFilterAndSort(likedSongs), "No tienes canciones marcadas con Me gusta", appState, pb.currentSong?.id, selectionMode, selectedIds)
            2 -> LibrarySongListSection(applySongFilterAndSort(librarySongs), "Tu biblioteca de canciones está vacía", appState, pb.currentSong?.id, selectionMode, selectedIds)

            3 -> {
                val filteredAlbums = albums.filter { filterQuery.isBlank() || it.title.contains(filterQuery, true) }
                if (filteredAlbums.isEmpty()) {
                    EmptyLibraryHint("Aún no has guardado álbumes")
                } else if (gridView) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(filteredAlbums.chunked(4)) { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                                row.forEach { alb ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                        modifier = Modifier.weight(1f).clickable { appState.navigate(NavDestination.Album(alb.id)) },
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            ArtworkImage(alb.thumbnailUrl, alb.title, modifier = Modifier.size(130.dp))
                                            Text(alb.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(filteredAlbums) { alb ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { appState.navigate(NavDestination.Album(alb.id)) }.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ArtworkImage(alb.thumbnailUrl, alb.title, modifier = Modifier.size(52.dp))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(alb.title, fontWeight = FontWeight.SemiBold)
                                    alb.year?.let { Text("$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                        }
                    }
                }
            }

            4 -> {
                val filteredArtists = artists.filter { filterQuery.isBlank() || it.displayName.contains(filterQuery, true) }
                if (filteredArtists.isEmpty()) {
                    EmptyLibraryHint("Aún no sigues artistas")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(filteredArtists) { art ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { appState.navigate(NavDestination.Artist(art.id)) }.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ArtworkImage(art.thumbnailUrl, art.displayName, isCircle = true, modifier = Modifier.size(52.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(art.displayName, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            5 -> {
                val filteredPlaylists = playlists.filter { filterQuery.isBlank() || it.name.contains(filterQuery, true) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filteredPlaylists) { pl ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                                appState.navigate(NavDestination.Playlist(pl.id, isLocal = pl.isLocal))
                            }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ArtworkImage(pl.thumbnailUrl, pl.name, modifier = Modifier.size(52.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(pl.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${pl.songCount} canciones • ${if (pl.isLocal) "Local" else "YouTube Music"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            6 -> {
                val filteredPodcasts = podcasts.filter { filterQuery.isBlank() || it.title.contains(filterQuery, true) }
                if (filteredPodcasts.isEmpty()) {
                    EmptyLibraryHint("Aún no has guardado podcasts. Abre cualquier podcast y pulsa Guardar.")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(filteredPodcasts) { pod ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { appState.navigate(NavDestination.Podcast(pod.id)) }.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ArtworkImage(pod.thumbnailUrl, pod.title, modifier = Modifier.size(52.dp))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(pod.title, fontWeight = FontWeight.SemiBold)
                                    pod.author?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                        }
                    }
                }
            }

            7 -> LibrarySongListSection(applySongFilterAndSort(uploadedSongs), "No hay canciones subidas sincronizadas. Pulsa 'Subir audio a YTM' para añadir archivos.", appState, pb.currentSong?.id, selectionMode, selectedIds)
            8 -> LibrarySongListSection(applySongFilterAndSort(downloadedSongs), "No hay canciones descargadas para uso sin conexión", appState, pb.currentSong?.id, selectionMode, selectedIds)

            9 -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = !remoteHistoryMode, onClick = { remoteHistoryMode = false }, label = { Text("Historial local") })
                        FilterChip(selected = remoteHistoryMode, onClick = { remoteHistoryMode = true }, enabled = settings.isLoggedIn, label = { Text("Historial YouTube Music") })
                        if (!remoteHistoryMode && localHistoryEntries.isNotEmpty()) {
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    appState.database.clearListenHistory()
                                    localHistoryEntries = emptyList()
                                }
                                appState.showStatus("Historial de escucha borrado")
                            }) { Text("Borrar historial") }
                        }
                    }

                    if (remoteHistoryMode) {
                        if (loadingRemoteHistory) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        } else {
                            LibrarySongListSection(applySongFilterAndSort(remoteHistorySongs), "Sin historial remoto en YouTube Music", appState, pb.currentSong?.id, false, selectedIds)
                        }
                    } else if (localHistoryEntries.isEmpty()) {
                        EmptyLibraryHint("Aún no has reproducido canciones")
                    } else {
                        val today = remember { LocalDate.now() }
                        val zone = remember { ZoneId.systemDefault() }
                        val filteredEntries = localHistoryEntries.filter {
                            filterQuery.isBlank() || it.song.title.contains(filterQuery, true) || it.song.artistDisplay.contains(filterQuery, true)
                        }
                        val grouped = remember(filteredEntries) {
                            filteredEntries.groupBy { entry ->
                                val d = Instant.ofEpochMilli(entry.timestampMs).atZone(zone).toLocalDate()
                                when {
                                    d == today -> "Hoy"
                                    d == today.minusDays(1) -> "Ayer"
                                    d.isAfter(today.minusDays(7)) -> "Esta semana"
                                    else -> "Anteriormente"
                                }
                            }
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            grouped.forEach { (sectionTitle, entries) ->
                                item {
                                    Text(sectionTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp).semantics { heading() })
                                }
                                items(entries, key = { it.eventId }) { entry ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            SongRowItem(
                                                song = entry.song,
                                                appState = appState,
                                                isCurrent = pb.currentSong?.id == entry.song.id,
                                                onPlay = { appState.audioPlayer.playSong(entry.song) },
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                scope.launch(Dispatchers.IO) {
                                                    appState.database.deleteHistoryEvent(entry.eventId)
                                                    localHistoryEntries = appState.database.historyEntries(150)
                                                }
                                            },
                                            modifier = Modifier.size(30.dp),
                                        ) {
                                            Icon(Icons.Rounded.Delete, contentDescription = "Eliminar del historial", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            10 -> LibrarySongListSection(applySongFilterAndSort(topSongs), "Escucha algunas canciones para generar tu Top ${settings.topSize}", appState, pb.currentSong?.id, selectionMode, selectedIds)
            11 -> LibrarySongListSection(applySongFilterAndSort(cachedSongs), "No hay canciones en caché local todavía", appState, pb.currentSong?.id, selectionMode, selectedIds)
        }
    }

    if (createPlaylistOpen) {
        var name by remember { mutableStateOf("") }
        var syncYtm by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { createPlaylistOpen = false },
            title = { Text("Nueva playlist") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") }, singleLine = true)
                    if (settings.isLoggedIn) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = syncYtm, onCheckedChange = { syncYtm = it })
                            Text("Crear también en YouTube Music", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = name.isNotBlank(),
                    onClick = {
                        appState.createPlaylist(name, syncToYtm = syncYtm)
                        createPlaylistOpen = false
                    },
                ) { Text("Crear") }
            },
            dismissButton = { TextButton(onClick = { createPlaylistOpen = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun AutoPlaylistCard(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
        modifier = Modifier
            .width(170.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = "Abrir $title", onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibrarySongListSection(
    songs: List<DesktopSong>,
    emptyMessage: String,
    appState: DesktopAppState,
    currentSongId: String?,
    selectionMode: Boolean,
    selectedIds: MutableList<String>,
) {
    if (songs.isEmpty()) {
        EmptyLibraryHint(emptyMessage)
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { appState.audioPlayer.playQueue(songs, 0) }) {
                Icon(Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text("Reproducir todo (${songs.size})")
            }
            OutlinedButton(onClick = { appState.audioPlayer.playQueue(songs.shuffled(), 0, shuffled = true) }) {
                Icon(Icons.Rounded.Shuffle, null)
                Spacer(Modifier.width(6.dp))
                Text("Aleatorio")
            }
        }
        if (selectionMode && selectedIds.isNotEmpty()) {
            val chosen = songs.filter { it.id in selectedIds }
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                FlowRow(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { appState.audioPlayer.playQueue(chosen, 0) }) { Text("Reproducir (${chosen.size})") }
                    OutlinedButton(onClick = { appState.audioPlayer.addAllToQueue(chosen) }) { Text("A la cola") }
                    OutlinedButton(onClick = { appState.openPlaylistPickerBatch(chosen) }) { Text("A playlist") }
                    OutlinedButton(onClick = { appState.downloadManager.downloadAll(chosen) }) { Text("Descargar") }
                    TextButton(onClick = { selectedIds.clear() }) { Text("Limpiar selección") }
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(songs) { idx, song ->
                SongRowItem(
                    song = song,
                    appState = appState,
                    index = idx,
                    isCurrent = currentSongId == song.id,
                    onPlay = { appState.audioPlayer.playQueue(songs, startIndex = idx) },
                    selectable = selectionMode,
                    selected = song.id in selectedIds,
                    onToggleSelect = {
                        if (song.id in selectedIds) selectedIds.remove(song.id) else selectedIds.add(song.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyLibraryHint(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
