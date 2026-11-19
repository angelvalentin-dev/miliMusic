package com.metrolist.desktop.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import com.metrolist.desktop.ui.icons.Bookmark
import com.metrolist.desktop.ui.icons.BookmarkBorder
import com.metrolist.desktop.ui.icons.Download
import com.metrolist.desktop.ui.icons.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.db.ArtistRef
import com.metrolist.desktop.db.DesktopAlbum
import com.metrolist.desktop.db.DesktopArtist
import com.metrolist.desktop.db.DesktopPlaylist
import com.metrolist.desktop.db.DesktopSong
import com.metrolist.desktop.db.toDesktopAlbum
import com.metrolist.desktop.db.toDesktopArtist
import com.metrolist.desktop.db.toDesktopSong
import com.metrolist.desktop.state.DesktopAppState
import com.metrolist.desktop.state.NavDestination
import com.metrolist.desktop.ui.components.ArtworkImage
import com.metrolist.desktop.ui.components.SongRowItem
import com.metrolist.desktop.ui.components.YTItemCard
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.BrowseEndpoint
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.AlbumPage
import com.metrolist.innertube.pages.ArtistPage
import com.metrolist.innertube.pages.BrowseResult
import com.metrolist.innertube.pages.PlaylistPage
import com.metrolist.innertube.pages.PodcastPage
import kotlinx.coroutines.launch

@Composable
fun ArtistScreen(artistId: String, appState: DesktopAppState) {
    val pb by appState.playbackState.collectAsState()
    var page by remember(artistId) { mutableStateOf<ArtistPage?>(null) }
    var loading by remember(artistId) { mutableStateOf(true) }
    var bookmarked by remember(artistId) { mutableStateOf(appState.database.isArtistBookmarked(artistId)) }

    LaunchedEffect(artistId) {
        loading = true
        page = YouTube.artist(artistId).getOrNull()
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                ArtworkImage(data.artist.thumbnail, data.artist.title, isCircle = true, modifier = Modifier.size(140.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = data.artist.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    data.subscriberCountText?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val firstSongs = data.sections.flatMap { it.items }.filterIsInstance<SongItem>().map { it.toDesktopSong() }
                        Button(
                            enabled = firstSongs.isNotEmpty(),
                            onClick = { appState.audioPlayer.playQueue(firstSongs) },
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Reproducir")
                        }
                        OutlinedButton(onClick = {
                            bookmarked = appState.database.toggleArtistBookmark(data.artist.toDesktopArtist())
                        }) {
                            Icon(if (bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (bookmarked) "Siguiendo" else "Seguir")
                        }
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
                    Text(section.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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

@Composable
fun AlbumScreen(albumId: String, appState: DesktopAppState) {
    val pb by appState.playbackState.collectAsState()
    var page by remember(albumId) { mutableStateOf<AlbumPage?>(null) }
    var loading by remember(albumId) { mutableStateOf(true) }
    var bookmarked by remember(albumId) { mutableStateOf(appState.database.isAlbumBookmarked(albumId)) }

    LaunchedEffect(albumId) {
        loading = true
        page = YouTube.album(albumId).getOrNull()
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
                ArtworkImage(data.album.thumbnail, data.album.title, modifier = Modifier.size(160.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(data.album.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(
                            data.album.artists?.joinToString(", ") { it.name },
                            data.album.year?.toString(),
                            "${songs.size} canciones",
                        ).joinToString(" • "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            enabled = songs.isNotEmpty(),
                            onClick = { appState.audioPlayer.playQueue(songs, 0) },
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Reproducir")
                        }
                        OutlinedButton(
                            enabled = songs.isNotEmpty(),
                            onClick = { appState.audioPlayer.playQueue(songs.shuffled(), 0) },
                        ) {
                            Icon(Icons.Rounded.Shuffle, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Aleatorio")
                        }
                        OutlinedButton(onClick = {
                            bookmarked = appState.database.toggleAlbumBookmark(data.album.toDesktopAlbum())
                        }) {
                            Icon(if (bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (bookmarked) "Guardado" else "Guardar")
                        }
                        OutlinedButton(onClick = {
                            songs.forEach { appState.downloadManager.download(it) }
                            appState.showStatus("Descargando álbum...")
                        }) {
                            Icon(Icons.Rounded.Download, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Descargar")
                        }
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
                Text("Otras versiones", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(data.otherVersions) { v -> YTItemCard(v, appState) }
                }
            }
        }
    }
}

@Composable
fun PlaylistScreen(
    playlistId: String,
    isLocal: Boolean,
    appState: DesktopAppState,
) {
    val pb by appState.playbackState.collectAsState()
    val localPlaylists by appState.playlists.collectAsState()
    val scope = rememberCoroutineScope()

    var remotePage by remember(playlistId) { mutableStateOf<PlaylistPage?>(null) }
    var remoteSongs by remember(playlistId) { mutableStateOf<List<DesktopSong>>(emptyList()) }
    var loading by remember(playlistId) { mutableStateOf(!isLocal) }
    var renameOpen by remember { mutableStateOf(false) }

    val localPlaylist: DesktopPlaylist? = localPlaylists.find { it.id == playlistId }
    val localSongs = remember(playlistId, localPlaylists, appState.likedSongs.collectAsState().value) {
        if (isLocal) appState.database.playlistSongs(playlistId) else emptyList()
    }

    LaunchedEffect(playlistId, isLocal) {
        if (!isLocal) {
            loading = true
            val res = YouTube.playlist(playlistId).getOrNull()
            remotePage = res
            remoteSongs = res?.songs?.map { it.toDesktopSong() }.orEmpty()
            loading = false
        }
    }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val title = if (isLocal) localPlaylist?.name ?: "Playlist" else remotePage?.playlist?.title ?: "Playlist"
    val thumbnail = if (isLocal) localPlaylist?.thumbnailUrl ?: localSongs.firstOrNull()?.thumbnailUrl else remotePage?.playlist?.thumbnail
    val songs = if (isLocal) localSongs else remoteSongs

    if (renameOpen && localPlaylist != null) {
        var newName by remember { mutableStateOf(localPlaylist.name) }
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("Renombrar playlist") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("Nombre") },
                )
            },
            confirmButton = {
                Button(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        appState.database.renamePlaylist(localPlaylist.id, newName)
                        renameOpen = false
                    },
                ) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("Cancelar") } },
        )
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
                ArtworkImage(thumbnail, title, modifier = Modifier.size(160.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${songs.size} canciones",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            enabled = songs.isNotEmpty(),
                            onClick = { appState.audioPlayer.playQueue(songs, 0) },
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Reproducir")
                        }
                        OutlinedButton(
                            enabled = songs.isNotEmpty(),
                            onClick = { appState.audioPlayer.playQueue(songs.shuffled(), 0) },
                        ) {
                            Icon(Icons.Rounded.Shuffle, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Aleatorio")
                        }
                        if (isLocal && localPlaylist != null) {
                            IconButton(onClick = { renameOpen = true }) {
                                Icon(Icons.Rounded.Edit, "Renombrar")
                            }
                            IconButton(onClick = {
                                appState.database.deletePlaylist(localPlaylist.id)
                                appState.goBack()
                            }) {
                                Icon(Icons.Rounded.Delete, "Eliminar playlist")
                            }
                        }
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
                onRemoveFromPlaylist = if (isLocal) {
                    { appState.database.removeSongFromPlaylist(playlistId, song.id) }
                } else null,
            )
        }

        val cont = remotePage?.songsContinuation ?: remotePage?.continuation
        if (!isLocal && !cont.isNullOrBlank()) {
            item {
                Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            YouTube.playlistContinuation(cont).onSuccess { more ->
                                remoteSongs = remoteSongs + more.songs.map { it.toDesktopSong() }
                                remotePage = remotePage?.copy(songsContinuation = more.continuation, continuation = null)
                            }
                        }
                    }) {
                        Text("Cargar más canciones")
                    }
                }
            }
        }
    }
}

@Composable
fun PodcastScreen(podcastId: String, appState: DesktopAppState) {
    val pb by appState.playbackState.collectAsState()
    var page by remember(podcastId) { mutableStateOf<PodcastPage?>(null) }
    var loading by remember(podcastId) { mutableStateOf(true) }

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
                isEpisode = true,
                artists = listOfNotNull(ep.author?.let { ArtistRef(it.id ?: "pod", it.name) }),
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
                ArtworkImage(data.podcast.thumbnail, data.podcast.title, modifier = Modifier.size(160.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(data.podcast.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    data.podcast.author?.name?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        enabled = episodes.isNotEmpty(),
                        onClick = { appState.audioPlayer.playQueue(episodes, 0) },
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Reproducir último")
                    }
                }
            }
        }
        itemsIndexed(episodes) { idx, ep ->
            SongRowItem(
                song = ep,
                appState = appState,
                index = idx,
                isCurrent = pb.currentSong?.id == ep.id,
                onPlay = { appState.audioPlayer.playQueue(episodes, startIndex = idx) },
            )
        }
    }
}

@Composable
fun BrowseScreen(
    browseId: String,
    params: String?,
    title: String,
    appState: DesktopAppState,
) {
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
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Sin contenido") }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Text(
                text = data.title ?: title.ifBlank { "Explorar" },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        items(data.items) { section ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                section.title?.let {
                    Text(it, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                val songs = section.items.filterIsInstance<SongItem>()
                if (songs.size == section.items.size && songs.isNotEmpty()) {
                    val desktopSongs = songs.map { it.toDesktopSong() }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        desktopSongs.forEachIndexed { idx, s ->
                            SongRowItem(
                                song = s,
                                appState = appState,
                                index = idx,
                                isCurrent = pb.currentSong?.id == s.id,
                                onPlay = { appState.audioPlayer.playQueue(desktopSongs, idx) },
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
