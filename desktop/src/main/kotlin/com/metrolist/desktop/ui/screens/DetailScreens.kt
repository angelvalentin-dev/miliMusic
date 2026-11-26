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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.db.ArtistRef
import com.metrolist.desktop.db.DesktopPlaylist
import com.metrolist.desktop.db.DesktopSong
import com.metrolist.desktop.db.toDesktopAlbum
import com.metrolist.desktop.db.toDesktopArtist
import com.metrolist.desktop.db.toDesktopPodcast
import com.metrolist.desktop.db.toDesktopSong
import com.metrolist.desktop.state.DesktopAppState
import com.metrolist.desktop.state.NavDestination
import com.metrolist.desktop.ui.components.ArtworkImage
import com.metrolist.desktop.ui.components.SongRowItem
import com.metrolist.desktop.ui.components.YTItemCard
import com.metrolist.desktop.ui.components.formatTimeMs
import com.metrolist.desktop.ui.icons.Bookmark
import com.metrolist.desktop.ui.icons.BookmarkBorder
import com.metrolist.desktop.ui.icons.Download
import com.metrolist.desktop.ui.icons.Shuffle
import com.metrolist.desktop.util.PlaylistTransferService
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.BrowseEndpoint
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.AlbumPage
import com.metrolist.innertube.pages.ArtistPage
import com.metrolist.innertube.pages.BrowseResult
import com.metrolist.innertube.pages.PlaylistPage
import com.metrolist.innertube.pages.PodcastPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser

private fun exportSongsDialog(defaultName: String, songs: List<DesktopSong>, asCsv: Boolean, appState: DesktopAppState) {
    val ext = if (asCsv) "csv" else "m3u"
    val safeName = defaultName.replace(Regex("""[^A-Za-z0-9 _-]"""), "").ifBlank { "playlist" }
    val chooser = JFileChooser().apply { selectedFile = File("$safeName.$ext") }
    if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
        val target = chooser.selectedFile
        val res = if (asCsv) PlaylistTransferService.exportToCsv(target, songs) else PlaylistTransferService.exportToM3u(target, songs)
        res.onSuccess { appState.showStatus("Exportado a ${it.name}") }
            .onFailure { appState.showStatus("Error al exportar: ${it.message}") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArtistScreen(artistId: String, appState: DesktopAppState) {
    val pb by appState.playbackState.collectAsState()
    val settings by appState.settings.collectAsState()
    val bookmarkedArtists by appState.bookmarkedArtists.collectAsState()
    var page by remember(artistId) { mutableStateOf<ArtistPage?>(null) }
    var localSongs by remember(artistId) { mutableStateOf<List<DesktopSong>>(emptyList()) }
    var loading by remember(artistId) { mutableStateOf(true) }
    var expandBio by remember(artistId) { mutableStateOf(false) }

    val isSubscribed = remember(bookmarkedArtists, artistId) { bookmarkedArtists.any { it.id == artistId } }

    LaunchedEffect(artistId) {
        loading = true
        page = YouTube.artist(artistId).getOrNull()
        localSongs = withContext(Dispatchers.IO) { appState.database.artistLocalSongs(artistId) }
        loading = false
    }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = "Cargando artista" })
        }
        return
    }
    val data = page ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No se pudo cargar el artista") }
        return
    }

    val firstSongs = remember(data) {
        data.sections.flatMap { it.items }.filterIsInstance<SongItem>().map { it.toDesktopSong() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                ArtworkImage(data.artist.thumbnail, data.artist.title, isCircle = true, modifier = Modifier.size(148.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    Text(
                        text = data.artist.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    val metaLine = buildList {
                        if (settings.showArtistSubscriberCount) data.subscriberCountText?.let(::add)
                        if (settings.showMonthlyListeners) data.monthlyListenerCount?.let(::add)
                    }.joinToString(" • ")
                    if (metaLine.isNotBlank()) {
                        Text(metaLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = firstSongs.isNotEmpty(), onClick = { appState.audioPlayer.playQueue(firstSongs) }) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Reproducir")
                        }
                        OutlinedButton(enabled = firstSongs.isNotEmpty(), onClick = { appState.audioPlayer.playQueue(firstSongs.shuffled(), shuffled = true) }) {
                            Icon(Icons.Rounded.Shuffle, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Aleatorio")
                        }
                        if (firstSongs.isNotEmpty()) {
                            OutlinedButton(onClick = {
                                appState.audioPlayer.playSong(firstSongs.first(), loadRadio = true)
                                appState.showStatus("Radio de ${data.artist.title} iniciada")
                            }) { Text("Radio") }
                        }
                        OutlinedButton(onClick = { appState.toggleArtistSubscribe(data.artist.toDesktopArtist()) }) {
                            Icon(if (isSubscribed) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (isSubscribed) "Suscrito" else "Suscribirse")
                        }
                        OutlinedButton(onClick = { appState.toggleSpeedDialArtist(data.artist.toDesktopArtist()) }) {
                            Icon(Icons.Rounded.Star, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Fijar")
                        }
                    }
                }
            }
        }

        if (settings.showArtistDescription && !data.description.isNullOrBlank()) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().clickable { expandBio = !expandBio },
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Acerca de ${data.artist.title}", fontWeight = FontWeight.Bold)
                        Text(
                            text = data.description!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (expandBio) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (localSongs.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "En tu biblioteca (${localSongs.size})",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    localSongs.take(6).forEachIndexed { idx, s ->
                        SongRowItem(
                            song = s,
                            appState = appState,
                            index = idx,
                            isCurrent = pb.currentSong?.id == s.id,
                            onPlay = { appState.audioPlayer.playQueue(localSongs, startIndex = idx) },
                        )
                    }
                }
            }
        }

        items(data.sections) { section ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(section.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    section.moreEndpoint?.let { ep ->
                        TextButton(onClick = {
                            appState.navigate(NavDestination.Browse(ep.browseId, ep.params, section.title))
                        }) { Text("Ver todo") }
                    }
                }
                val songs = section.items.filterIsInstance<SongItem>()
                if (songs.size == section.items.size) {
                    val desktopSongs = songs.map { it.toDesktopSong() }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        desktopSongs.forEachIndexed { idx, s ->
                            SongRowItem(
                                song = s,
                                appState = appState,
                                index = idx,
                                isCurrent = pb.currentSong?.id == s.id,
                                onPlay = { appState.audioPlayer.playQueue(desktopSongs, startIndex = idx) },
                            )
                        }
                    }
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(section.items) { yt -> YTItemCard(yt, appState) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlbumScreen(albumId: String, appState: DesktopAppState) {
    val pb by appState.playbackState.collectAsState()
    val scope = rememberCoroutineScope()
    var page by remember(albumId) { mutableStateOf<AlbumPage?>(null) }
    var loading by remember(albumId) { mutableStateOf(true) }
    var bookmarked by remember(albumId) { mutableStateOf(false) }

    LaunchedEffect(albumId) {
        loading = true
        page = YouTube.album(albumId).getOrNull()
        bookmarked = withContext(Dispatchers.IO) { appState.database.isAlbumBookmarked(albumId) }
        loading = false
    }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val data = page ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No se pudo cargar el álbum") }
        return
    }
    val songs = remember(data) { data.songs.map { it.toDesktopSong() } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.padding(bottom = 16.dp),
            ) {
                ArtworkImage(data.album.thumbnail, data.album.title, modifier = Modifier.size(164.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            data.album.title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics { heading() },
                        )
                        if (data.album.explicit) {
                            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(4.dp)) {
                                Text("E", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        data.album.artists.orEmpty().forEachIndexed { idx, artist ->
                            Text(
                                text = artist.name + if (idx < data.album.artists!!.lastIndex) "," else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (artist.id != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable(enabled = artist.id != null) {
                                    artist.id?.let { appState.navigate(NavDestination.Artist(it)) }
                                },
                            )
                        }
                        data.album.year?.let {
                            Text("• $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("• ${songs.size} canciones", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = songs.isNotEmpty(), onClick = { appState.audioPlayer.playQueue(songs, 0) }) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Reproducir")
                        }
                        OutlinedButton(enabled = songs.isNotEmpty(), onClick = { appState.audioPlayer.playQueue(songs.shuffled(), 0, shuffled = true) }) {
                            Icon(Icons.Rounded.Shuffle, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Aleatorio")
                        }
                        OutlinedButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                bookmarked = appState.database.toggleAlbumBookmark(data.album.toDesktopAlbum())
                            }
                        }) {
                            Icon(if (bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (bookmarked) "Guardado" else "Guardar")
                        }
                        OutlinedButton(onClick = {
                            appState.downloadManager.downloadAll(songs)
                            appState.showStatus("Descargando álbum...")
                        }) {
                            Icon(Icons.Rounded.Download, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Descargar")
                        }
                        OutlinedButton(onClick = { appState.toggleSpeedDialAlbum(data.album.toDesktopAlbum()) }) { Text("Fijar") }
                        OutlinedButton(onClick = { exportSongsDialog(data.album.title, songs, asCsv = true, appState) }) { Text("CSV") }
                        OutlinedButton(onClick = { exportSongsDialog(data.album.title, songs, asCsv = false, appState) }) { Text("M3U") }
                    }
                }
            }
        }

        itemsIndexed(songs) { idx, song ->
            SongRowItem(
                song = song,
                appState = appState,
                index = idx,
                isCurrent = pb.currentSong?.id == song.id,
                onPlay = { appState.audioPlayer.playQueue(songs, startIndex = idx) },
            )
        }

        if (data.otherVersions.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                Text("Otras versiones", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(data.otherVersions) { ver -> YTItemCard(ver, appState) }
                }
            }
        }
    }
}

private enum class PlaylistSort(val label: String) {
    CUSTOM("Personalizado"),
    NAME("Nombre"),
    ARTIST("Artista"),
    DURATION("Duración"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaylistScreen(playlistId: String, isLocal: Boolean, appState: DesktopAppState) {
    val pb by appState.playbackState.collectAsState()
    val allPlaylists by appState.playlists.collectAsState()
    val scope = rememberCoroutineScope()

    var localSongs by remember(playlistId) { mutableStateOf<List<DesktopSong>>(emptyList()) }
    var remotePage by remember(playlistId) { mutableStateOf<PlaylistPage?>(null) }
    var remoteSongs by remember(playlistId) { mutableStateOf<List<DesktopSong>>(emptyList()) }
    var remoteContinuation by remember(playlistId) { mutableStateOf<String?>(null) }
    var loading by remember(playlistId) { mutableStateOf(true) }
    var renameOpen by remember { mutableStateOf(false) }
    var coverOpen by remember { mutableStateOf(false) }
    var filterQuery by remember(playlistId) { mutableStateOf("") }
    var sortMode by remember(playlistId) { mutableStateOf(PlaylistSort.CUSTOM) }
    var editLocked by remember(playlistId) { mutableStateOf(false) }
    var selectionMode by remember(playlistId) { mutableStateOf(false) }
    val selectedIds = remember(playlistId) { mutableStateListOf<String>() }

    DisposableEffect(renameOpen, coverOpen) {
        appState.setLocalModalOpen(renameOpen || coverOpen)
        onDispose { appState.setLocalModalOpen(false) }
    }

    val localPlaylist: DesktopPlaylist? = allPlaylists.firstOrNull { it.id == playlistId }

    LaunchedEffect(playlistId, isLocal, allPlaylists) {
        loading = true
        if (isLocal) {
            localSongs = withContext(Dispatchers.IO) { appState.database.playlistSongs(playlistId) }
        } else {
            YouTube.playlist(playlistId).onSuccess { p ->
                remotePage = p
                remoteSongs = p.songs.map { it.toDesktopSong() }
                remoteContinuation = p.songsContinuation
            }
        }
        loading = false
    }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val title = if (isLocal) (localPlaylist?.name ?: "Playlist") else (remotePage?.playlist?.title ?: "Playlist")
    val thumb = if (isLocal) (localPlaylist?.thumbnailUrl ?: localSongs.firstOrNull()?.thumbnailUrl) else remotePage?.playlist?.thumbnail
    val rawSongs = if (isLocal) localSongs else remoteSongs
    val displayedSongs = remember(rawSongs, filterQuery, sortMode) {
        val filtered = if (filterQuery.isBlank()) rawSongs else rawSongs.filter {
            it.title.contains(filterQuery, true) || it.artistDisplay.contains(filterQuery, true)
        }
        when (sortMode) {
            PlaylistSort.CUSTOM -> filtered
            PlaylistSort.NAME -> filtered.sortedBy { it.title.lowercase() }
            PlaylistSort.ARTIST -> filtered.sortedBy { it.artistDisplay.lowercase() }
            PlaylistSort.DURATION -> filtered.sortedByDescending { it.duration }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                ArtworkImage(thumb, title, modifier = Modifier.size(160.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    Text(
                        "${rawSongs.size} canciones • ${if (isLocal) "Playlist local" else "YouTube Music"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = displayedSongs.isNotEmpty(), onClick = { appState.audioPlayer.playQueue(displayedSongs, 0) }) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Reproducir")
                        }
                        OutlinedButton(enabled = displayedSongs.isNotEmpty(), onClick = { appState.audioPlayer.playQueue(displayedSongs.shuffled(), 0, shuffled = true) }) {
                            Icon(Icons.Rounded.Shuffle, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Aleatorio")
                        }
                        OutlinedButton(enabled = displayedSongs.isNotEmpty(), onClick = {
                            appState.downloadManager.downloadAll(displayedSongs)
                            appState.showStatus("Descargando playlist...")
                        }) {
                            Icon(Icons.Rounded.Download, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Descargar")
                        }
                        OutlinedButton(onClick = {
                            val pl = localPlaylist ?: DesktopPlaylist(id = playlistId, name = title, browseId = playlistId, thumbnailUrl = thumb, isLocal = isLocal)
                            appState.toggleSpeedDialPlaylist(pl)
                        }) { Text("Fijar") }
                        OutlinedButton(onClick = { exportSongsDialog(title, displayedSongs, asCsv = true, appState) }) { Text("CSV") }
                        OutlinedButton(onClick = { exportSongsDialog(title, displayedSongs, asCsv = false, appState) }) { Text("M3U") }
                        if (isLocal && localPlaylist != null) {
                            OutlinedButton(onClick = { renameOpen = true }) {
                                Icon(Icons.Rounded.Edit, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Renombrar")
                            }
                            OutlinedButton(onClick = { coverOpen = true }) { Text("Portada") }
                            IconButton(onClick = {
                                appState.deletePlaylist(localPlaylist)
                                appState.goBack()
                            }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Eliminar playlist")
                            }
                        }
                    }
                }
            }
        }

        // Search + Sort + Edit Lock + Batch Selection toolbar
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = filterQuery,
                        onValueChange = { filterQuery = it },
                        placeholder = { Text("Filtrar canciones en esta playlist...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = selectionMode,
                        onClick = {
                            selectionMode = !selectionMode
                            if (!selectionMode) selectedIds.clear()
                        },
                        label = { Text(if (selectionMode) "Selección (${selectedIds.size})" else "Seleccionar") },
                    )
                    if (isLocal) {
                        FilterChip(
                            selected = editLocked,
                            onClick = { editLocked = !editLocked },
                            label = { Text(if (editLocked) "Bloqueada" else "Reordenar") },
                        )
                    }
                }
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlaylistSort.entries.forEach { mode ->
                        FilterChip(selected = sortMode == mode, onClick = { sortMode = mode }, label = { Text(mode.label) })
                    }
                }
                if (selectionMode && selectedIds.isNotEmpty()) {
                    val chosen = displayedSongs.filter { it.id in selectedIds }
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                        FlowRow(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { appState.audioPlayer.playQueue(chosen, 0) }) { Text("Reproducir (${chosen.size})") }
                            OutlinedButton(onClick = { appState.audioPlayer.addAllToQueue(chosen) }) { Text("A la cola") }
                            OutlinedButton(onClick = { appState.openPlaylistPickerBatch(chosen) }) { Text("A playlist") }
                            OutlinedButton(onClick = { appState.downloadManager.downloadAll(chosen) }) { Text("Descargar") }
                            TextButton(onClick = { selectedIds.clear(); selectionMode = false }) { Text("Cancelar") }
                        }
                    }
                }
            }
        }

        itemsIndexed(displayedSongs) { idx, song ->
            val canReorder = isLocal && !editLocked && sortMode == PlaylistSort.CUSTOM && filterQuery.isBlank()
            SongRowItem(
                song = song,
                appState = appState,
                index = idx,
                isCurrent = pb.currentSong?.id == song.id,
                onPlay = { appState.audioPlayer.playQueue(displayedSongs, startIndex = idx) },
                selectable = selectionMode,
                selected = song.id in selectedIds,
                onToggleSelect = {
                    if (song.id in selectedIds) selectedIds.remove(song.id) else selectedIds.add(song.id)
                },
                onMoveUp = if (canReorder && idx > 0) {
                    {
                        scope.launch(Dispatchers.IO) {
                            appState.database.reorderPlaylistSongs(playlistId, idx, idx - 1)
                            localSongs = appState.database.playlistSongs(playlistId)
                        }
                    }
                } else null,
                onMoveDown = if (canReorder && idx < displayedSongs.lastIndex) {
                    {
                        scope.launch(Dispatchers.IO) {
                            appState.database.reorderPlaylistSongs(playlistId, idx, idx + 1)
                            localSongs = appState.database.playlistSongs(playlistId)
                        }
                    }
                } else null,
                onRemoveFromPlaylist = if (isLocal && localPlaylist != null && !editLocked) {
                    {
                        appState.removeSongFromPlaylist(localPlaylist, song.id)
                        scope.launch(Dispatchers.IO) {
                            localSongs = appState.database.playlistSongs(playlistId)
                        }
                    }
                } else null,
            )
        }

        if (!isLocal && !remoteContinuation.isNullOrBlank()) {
            item {
                Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                    OutlinedButton(onClick = {
                        val cont = remoteContinuation ?: return@OutlinedButton
                        scope.launch {
                            YouTube.playlistContinuation(cont).onSuccess { res ->
                                remoteSongs = remoteSongs + res.songs.map { it.toDesktopSong() }
                                remoteContinuation = res.continuation
                            }
                        }
                    }) { Text("Cargar más canciones") }
                }
            }
        }
    }

    if (renameOpen && localPlaylist != null) {
        var newName by remember { mutableStateOf(localPlaylist.name) }
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("Renombrar playlist") },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true, label = { Text("Nombre") })
            },
            confirmButton = {
                Button(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        appState.renamePlaylist(localPlaylist, newName)
                        renameOpen = false
                    },
                ) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("Cancelar") } },
        )
    }

    if (coverOpen && localPlaylist != null) {
        var coverUrl by remember { mutableStateOf(localPlaylist.thumbnailUrl.orEmpty()) }
        AlertDialog(
            onDismissRequest = { coverOpen = false },
            title = { Text("Portada personalizada") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = coverUrl,
                        onValueChange = { coverUrl = it },
                        label = { Text("URL o ruta local de imagen") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = {
                        val chooser = JFileChooser()
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            coverUrl = chooser.selectedFile.toURI().toString()
                        }
                    }) { Text("Elegir imagen del equipo") }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        scope.launch(Dispatchers.IO) { appState.database.updatePlaylistThumbnail(localPlaylist.id, null) }
                        coverOpen = false
                    }) { Text("Quitar") }
                    Button(onClick = {
                        scope.launch(Dispatchers.IO) { appState.database.updatePlaylistThumbnail(localPlaylist.id, coverUrl.ifBlank { null }) }
                        coverOpen = false
                    }) { Text("Guardar") }
                }
            },
            dismissButton = { TextButton(onClick = { coverOpen = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
fun PodcastScreen(podcastId: String, appState: DesktopAppState) {
    val pb by appState.playbackState.collectAsState()
    val savedPodcasts by appState.bookmarkedPodcasts.collectAsState()
    var page by remember(podcastId) { mutableStateOf<PodcastPage?>(null) }
    var loading by remember(podcastId) { mutableStateOf(true) }
    val isSaved = remember(savedPodcasts, podcastId) { savedPodcasts.any { it.id == podcastId } }

    LaunchedEffect(podcastId) {
        loading = true
        page = YouTube.podcast(podcastId).getOrNull()
        loading = false
    }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val data = page ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No se pudo cargar el podcast") }
        return
    }

    val episodes = remember(data) {
        data.episodes.map { ep ->
            DesktopSong(
                id = ep.id,
                title = ep.title,
                duration = ep.duration ?: -1,
                thumbnailUrl = ep.thumbnail,
                albumName = data.podcast.title,
                isEpisode = true,
                artists = listOfNotNull(ep.author?.let { ArtistRef(it.id ?: "ep_${ep.id}", it.name) }),
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.padding(bottom = 16.dp),
            ) {
                ArtworkImage(data.podcast.thumbnail, data.podcast.title, modifier = Modifier.size(150.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(data.podcast.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    data.podcast.author?.name?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = episodes.isNotEmpty(), onClick = { appState.audioPlayer.playQueue(episodes, 0) }) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Reproducir episodios")
                        }
                        OutlinedButton(onClick = { appState.togglePodcastBookmark(data.podcast.toDesktopPodcast()) }) {
                            Icon(if (isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (isSaved) "Guardado" else "Guardar podcast")
                        }
                        data.podcast.author?.id?.let { chId ->
                            OutlinedButton(onClick = { appState.navigate(NavDestination.Artist(chId)) }) {
                                Text("Ver canal")
                            }
                        }
                    }
                }
            }
        }
        itemsIndexed(episodes) { idx, ep ->
            val savedPos = remember(ep.id) { appState.database.getSong(ep.id)?.playbackPosition }
            Column {
                SongRowItem(
                    song = ep,
                    appState = appState,
                    index = idx,
                    isCurrent = pb.currentSong?.id == ep.id,
                    onPlay = { appState.audioPlayer.playQueue(episodes, startIndex = idx) },
                )
                if (savedPos != null && savedPos > 5_000L) {
                    Text(
                        "Reanudar desde ${formatTimeMs(savedPos)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 72.dp, bottom = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun BrowseScreen(browseId: String, params: String?, title: String, appState: DesktopAppState) {
    val pb by appState.playbackState.collectAsState()
    var result by remember(browseId, params) { mutableStateOf<BrowseResult?>(null) }
    var loading by remember(browseId, params) { mutableStateOf(true) }

    LaunchedEffect(browseId, params) {
        loading = true
        result = YouTube.browse(browseId, params).getOrNull()
        loading = false
    }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val data = result ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No se pudo cargar el contenido") }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Text(
                data.title ?: title.ifBlank { "Explorar" },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
        }
        items(data.items) { section ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                section.title?.let { Text(it, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() }) }
                val songs = section.items.filterIsInstance<SongItem>()
                if (songs.size == section.items.size && songs.isNotEmpty()) {
                    val desktopSongs = songs.map { it.toDesktopSong() }
                    desktopSongs.forEachIndexed { idx, s ->
                        SongRowItem(
                            song = s,
                            appState = appState,
                            index = idx,
                            isCurrent = pb.currentSong?.id == s.id,
                            onPlay = { appState.audioPlayer.playQueue(desktopSongs, startIndex = idx) },
                        )
                    }
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(section.items) { item -> YTItemCard(item, appState) }
                    }
                }
            }
        }
    }
}
