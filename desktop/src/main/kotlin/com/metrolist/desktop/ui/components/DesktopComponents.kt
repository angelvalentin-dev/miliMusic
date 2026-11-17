package com.metrolist.desktop.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import com.metrolist.desktop.ui.icons.Album
import com.metrolist.desktop.ui.icons.Download
import com.metrolist.desktop.ui.icons.MusicNote
import com.metrolist.desktop.ui.icons.PlaylistAdd
import com.metrolist.desktop.ui.icons.PlaylistPlay
import com.metrolist.desktop.ui.icons.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.desktop.auth.WindowsWebView2Auth
import com.metrolist.desktop.db.DesktopSong
import com.metrolist.desktop.db.toDesktopSong
import com.metrolist.desktop.state.DesktopAppState
import com.metrolist.desktop.state.NavDestination
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import kotlinx.coroutines.launch

fun formatTimeSeconds(seconds: Int): String {
    if (seconds <= 0) return "--:--"
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

fun formatTimeMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val m = totalSec / 60L
    val s = totalSec % 60L
    return "%d:%02d".format(m, s)
}

@Composable
fun ArtworkImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    isCircle: Boolean = false,
) {
    val shape = if (isCircle) CircleShape else RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isCircle) Icons.Rounded.Person else Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(28.dp),
        )
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun YTItemCard(
    item: YTItem,
    appState: DesktopAppState,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val isArtist = item is ArtistItem

    val subtitle = when (item) {
        is SongItem -> item.artists.joinToString(", ") { it.name }
        is AlbumItem -> listOfNotNull(item.artists?.joinToString(", ") { it.name }, item.year?.toString()).joinToString(" • ")
        is ArtistItem -> "Artista"
        is PlaylistItem -> item.author?.name ?: item.songCountText ?: "Playlist"
        is PodcastItem -> item.author?.name ?: "Podcast"
        is EpisodeItem -> item.author?.name ?: "Episodio"
    }

    Surface(
        modifier = modifier
            .width(176.dp)
            .hoverable(interactionSource)
            .clip(RoundedCornerShape(14.dp))
            .clickable {
                when (item) {
                    is SongItem -> appState.audioPlayer.playSong(item.toDesktopSong())
                    is AlbumItem -> appState.navigate(NavDestination.Album(item.browseId))
                    is ArtistItem -> appState.navigate(NavDestination.Artist(item.id))
                    is PlaylistItem -> appState.navigate(NavDestination.Playlist(item.id, isLocal = false))
                    is PodcastItem -> appState.navigate(NavDestination.Podcast(item.id))
                    is EpisodeItem -> appState.audioPlayer.playSong(
                        DesktopSong(
                            id = item.id,
                            title = item.title,
                            duration = item.duration ?: -1,
                            thumbnailUrl = item.thumbnail,
                            isEpisode = true,
                        ),
                    )
                }
            },
        color = if (hovered) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                ArtworkImage(
                    url = item.thumbnail,
                    contentDescription = item.title,
                    isCircle = isArtist,
                    modifier = Modifier.fillMaxSize(),
                )
                if (hovered && item !is ArtistItem) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .size(40.dp)
                            .clickable {
                                when (item) {
                                    is SongItem -> appState.audioPlayer.playSong(item.toDesktopSong())
                                    is EpisodeItem -> appState.audioPlayer.playSong(
                                        DesktopSong(
                                            id = item.id,
                                            title = item.title,
                                            duration = item.duration ?: -1,
                                            thumbnailUrl = item.thumbnail,
                                            isEpisode = true,
                                        ),
                                    )
                                    is AlbumItem -> appState.navigate(NavDestination.Album(item.browseId))
                                    is PlaylistItem -> appState.navigate(NavDestination.Playlist(item.id, isLocal = false))
                                    is PodcastItem -> appState.navigate(NavDestination.Podcast(item.id))
                                    else -> Unit
                                }
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = "Reproducir",
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle.ifBlank { "YouTube Music" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun SongRowItem(
    song: DesktopSong,
    appState: DesktopAppState,
    index: Int? = null,
    isCurrent: Boolean = false,
    onPlay: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val downloadingMap by appState.downloadManager.downloading.collectAsState()
    val likedSongs by appState.likedSongs.collectAsState()
    val downloadedSongs by appState.downloadedSongs.collectAsState()

    val isLiked = song.liked || likedSongs.any { it.id == song.id }
    val isDownloaded = song.isDownloaded || downloadedSongs.any { it.id == song.id }
    val downloadProgress = downloadingMap[song.id]
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onPlay),
        color = when {
            isCurrent -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> MaterialTheme.colorScheme.surface.copy(alpha = 0f)
        },
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (index != null) {
                Box(modifier = Modifier.width(30.dp), contentAlignment = Alignment.Center) {
                    if (hovered) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = "Reproducir",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    } else {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
            }

            ArtworkImage(
                url = song.thumbnailUrl,
                contentDescription = song.title,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        song.artistDisplay,
                        song.albumName?.takeIf { it.isNotBlank() },
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(onClick = { appState.toggleLike(song) }, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "Me gusta",
                    tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }

            Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                when {
                    downloadProgress != null -> {
                        CircularProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    isDownloaded -> {
                        IconButton(
                            onClick = { appState.downloadManager.deleteDownload(song) },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = "Descargada",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    else -> {
                        IconButton(
                            onClick = { appState.downloadManager.download(song) },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Download,
                                contentDescription = "Descargar",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            Text(
                text = formatTimeSeconds(song.duration),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(46.dp).padding(start = 8.dp),
            )

            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = "Opciones",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Reproducir a continuación") },
                        leadingIcon = { Icon(Icons.Rounded.PlaylistPlay, null) },
                        onClick = {
                            appState.audioPlayer.playNext(song)
                            appState.showStatus("Añadida a continuación")
                            menuExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Añadir a la cola") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) },
                        onClick = {
                            appState.audioPlayer.addToQueue(song)
                            appState.showStatus("Añadida a la cola")
                            menuExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Añadir a playlist") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) },
                        onClick = {
                            appState.openPlaylistPicker(song)
                            menuExpanded = false
                        },
                    )
                    if (!song.albumId.isNullOrBlank()) {
                        DropdownMenuItem(
                            text = { Text("Ir al álbum") },
                            leadingIcon = { Icon(Icons.Rounded.Album, null) },
                            onClick = {
                                appState.navigate(NavDestination.Album(song.albumId))
                                menuExpanded = false
                            },
                        )
                    }
                    song.artists.firstOrNull()?.id?.takeIf { !it.startsWith("artist_") }?.let { artistId ->
                        DropdownMenuItem(
                            text = { Text("Ir al artista") },
                            leadingIcon = { Icon(Icons.Rounded.Person, null) },
                            onClick = {
                                appState.navigate(NavDestination.Artist(artistId))
                                menuExpanded = false
                            },
                        )
                    }
                    if (onRemoveFromPlaylist != null) {
                        DropdownMenuItem(
                            text = { Text("Quitar de la playlist") },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                            onClick = {
                                onRemoveFromPlaylist()
                                menuExpanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AddToPlaylistDialog(appState: DesktopAppState) {
    val song = appState.playlistPickerSong.collectAsState().value ?: return
    val playlists by appState.playlists.collectAsState()
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = appState::closePlaylistPicker,
        title = { Text("Añadir a playlist") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Nueva playlist") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        enabled = newName.isNotBlank(),
                        onClick = {
                            val created = appState.database.createPlaylist(newName)
                            appState.database.addSongToPlaylist(created.id, song)
                            appState.showStatus("Añadida a ${created.name}")
                            appState.closePlaylistPicker()
                        },
                    ) {
                        Text("Crear")
                    }
                }
                Spacer(Modifier.height(12.dp))
                LazyColumn(modifier = Modifier.height(220.dp)) {
                    items(playlists.filter { it.isEditable }) { pl ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    appState.database.addSongToPlaylist(pl.id, song)
                                    appState.showStatus("Añadida a ${pl.name}")
                                    appState.closePlaylistPicker()
                                }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(pl.name, fontWeight = FontWeight.Medium)
                                Text("${pl.songCount} canciones", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = appState::closePlaylistPicker) { Text("Cerrar") }
        },
    )
}

@Composable
fun LoginDialog(appState: DesktopAppState) {
    val open by appState.showLoginDialog.collectAsState()
    if (!open) return

    val scope = rememberCoroutineScope()
    val webView2Available = remember { WindowsWebView2Auth.isAvailable() }
    var manualMode by remember { mutableStateOf(!webView2Available) }
    var cookieInput by remember { mutableStateOf("") }
    var visitorDataInput by remember { mutableStateOf("") }
    var dataSyncIdInput by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!loading) appState.closeLoginDialog() },
        title = { Text("Iniciar sesión en YouTube Music") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (webView2Available) {
                    Text(
                        "Usa la ventana integrada de Edge WebView2 para iniciar sesión directamente con tu cuenta de Google.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        enabled = !loading,
                        onClick = {
                            loading = true
                            error = null
                            scope.launch {
                                val res = appState.loginWithWebView2()
                                loading = false
                                if (res.isSuccess) {
                                    appState.closeLoginDialog()
                                } else {
                                    error = res.exceptionOrNull()?.message ?: "Error al iniciar sesión"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (loading) "Esperando inicio de sesión..." else "Abrir WebView2 (Edge integrado)")
                    }
                    OutlinedButton(
                        enabled = !loading,
                        onClick = { manualMode = !manualMode },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (manualMode) "Ocultar pegado manual" else "O pegar cookies manualmente")
                    }
                }

                if (manualMode) {
                    Text(
                        "Pega las cookies de music.youtube.com (debe incluir SAPISID).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = cookieInput,
                        onValueChange = { cookieInput = it },
                        label = { Text("Cookie") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = visitorDataInput,
                        onValueChange = { visitorDataInput = it },
                        label = { Text("Visitor Data (opcional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = dataSyncIdInput,
                        onValueChange = { dataSyncIdInput = it },
                        label = { Text("DataSync ID (opcional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (error != null) {
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            if (manualMode) {
                Button(
                    enabled = !loading && cookieInput.isNotBlank(),
                    onClick = {
                        loading = true
                        error = null
                        scope.launch {
                            val res = appState.applySessionCredentials(
                                cookie = cookieInput.trim(),
                                visitorData = visitorDataInput.trim(),
                                dataSyncId = dataSyncIdInput.trim(),
                                authUser = "0",
                            )
                            loading = false
                            if (res.isSuccess) {
                                appState.closeLoginDialog()
                            } else {
                                error = res.exceptionOrNull()?.message ?: "Cookies inválidas"
                            }
                        }
                    },
                ) {
                    Text("Guardar sesión")
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !loading, onClick = appState::closeLoginDialog) {
                Text("Cancelar")
            }
        },
    )
}
