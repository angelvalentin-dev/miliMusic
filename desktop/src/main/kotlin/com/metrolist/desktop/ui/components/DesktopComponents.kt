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
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.desktop.auth.WindowsWebView2Auth
import com.metrolist.desktop.data.DesktopGridSize
import com.metrolist.desktop.data.DesktopPrefKeys
import com.metrolist.desktop.db.DesktopSong
import com.metrolist.desktop.db.toDesktopSong
import com.metrolist.desktop.state.DesktopAppState
import com.metrolist.desktop.state.NavDestination
import com.metrolist.desktop.ui.icons.Album
import com.metrolist.desktop.ui.icons.ArrowDownward
import com.metrolist.desktop.ui.icons.ArrowUpward
import com.metrolist.desktop.ui.icons.Download
import com.metrolist.desktop.ui.icons.LibraryMusic
import com.metrolist.desktop.ui.icons.MusicNote
import com.metrolist.desktop.ui.icons.PlaylistAdd
import com.metrolist.desktop.ui.icons.PlaylistPlay
import com.metrolist.desktop.ui.icons.QueueMusic
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeAccount
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val settings by appState.settings.collectAsState()
    val cardWidth = if (settings.gridSize == DesktopGridSize.SMALL) 144.dp else 176.dp
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
    val actionVerb = if (item is SongItem || item is EpisodeItem) "Reproducir" else "Abrir"

    Surface(
        modifier = modifier
            .width(cardWidth)
            .hoverable(interactionSource)
            .clip(RoundedCornerShape(14.dp))
            .semantics(mergeDescendants = true) {
                contentDescription = "${item.title}, ${subtitle.ifBlank { "YouTube Music" }}"
            }
            .clickable(
                role = Role.Button,
                onClickLabel = "$actionVerb ${item.title}",
            ) {
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
                    contentDescription = null,
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
                            .clickable(
                                role = Role.Button,
                                onClickLabel = "Reproducir ${item.title}",
                            ) {
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
                                    is ArtistItem -> Unit
                                }
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = "Reproducir ${item.title}",
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
    selectable: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val downloadingMap by appState.downloadManager.downloading.collectAsState()
    val likedSongs by appState.likedSongs.collectAsState()
    val downloadedSongs by appState.downloadedSongs.collectAsState()
    val librarySongs by appState.librarySongs.collectAsState()
    val speedDialItems by appState.speedDialItems.collectAsState()
    val ltState by appState.listenTogetherManager.state.collectAsState()

    val isLiked = remember(likedSongs, song.id) { likedSongs.any { it.id == song.id } }
    val isDownloaded = remember(downloadedSongs, song.id) { downloadedSongs.any { it.id == song.id } }
    val isInLibrary = remember(librarySongs, song.id) { librarySongs.any { it.id == song.id } }
    val isPinned = remember(speedDialItems, song.id) { speedDialItems.any { it.id == song.id } }
    val downloadProgress = downloadingMap[song.id]
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = if (selectable) "Seleccionar ${song.title}" else "Reproducir ${song.title} de ${song.artistDisplay}",
                onClick = { if (selectable && onToggleSelect != null) onToggleSelect() else onPlay() },
            ),
        color = when {
            selected -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
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
            if (selectable) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelect?.invoke() })
                Spacer(Modifier.width(4.dp))
            }
            if (index != null) {
                Box(modifier = Modifier.width(30.dp), contentAlignment = Alignment.Center) {
                    if (hovered) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = "Reproducir ${song.title}",
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
                contentDescription = null,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (song.explicit) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                "E",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
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

            if (onMoveUp != null) {
                IconButton(onClick = onMoveUp, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.ArrowUpward, contentDescription = "Subir ${song.title}", modifier = Modifier.size(16.dp))
                }
            }
            if (onMoveDown != null) {
                IconButton(onClick = onMoveDown, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.ArrowDownward, contentDescription = "Bajar ${song.title}", modifier = Modifier.size(16.dp))
                }
            }

            IconButton(onClick = { appState.toggleLike(song) }, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (isLiked) "Quitar ${song.title} de Me gusta" else "Añadir ${song.title} a Me gusta",
                    tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }

            Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                when {
                    downloadProgress != null -> {
                        IconButton(
                            onClick = { appState.downloadManager.cancelDownload(song.id) },
                            modifier = Modifier.size(34.dp),
                        ) {
                            CircularProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier
                                    .size(20.dp)
                                    .semantics {
                                        contentDescription = "Descargando ${song.title}: ${(downloadProgress * 100).toInt()} por ciento. Pulsa para cancelar"
                                    },
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                    isDownloaded -> {
                        IconButton(
                            onClick = { appState.downloadManager.deleteDownload(song) },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = "Descargada: eliminar descarga de ${song.title}",
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
                                contentDescription = "Descargar ${song.title}",
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
                        contentDescription = "Más opciones para ${song.title}",
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
                        text = { Text("Iniciar radio") },
                        leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) },
                        onClick = {
                            appState.audioPlayer.startRadioSeamlessly(song)
                            appState.showStatus("Radio añadida a la cola")
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
                    if (ltState.connected && ltState.roomCode.isNotBlank() && !ltState.isHost) {
                        DropdownMenuItem(
                            text = { Text("Sugerir en Escuchar Juntos") },
                            leadingIcon = { Icon(Icons.Rounded.Share, null) },
                            onClick = {
                                appState.listenTogetherManager.suggestTrack(song)
                                menuExpanded = false
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (isInLibrary) "Quitar de biblioteca" else "Añadir a biblioteca") },
                        leadingIcon = { Icon(Icons.Rounded.LibraryMusic, null) },
                        onClick = {
                            appState.toggleInLibrary(song)
                            menuExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (isPinned) "Quitar de Marcación rápida" else "Fijar en Marcación rápida") },
                        leadingIcon = { Icon(Icons.Rounded.Star, null) },
                        onClick = {
                            appState.toggleSpeedDialSong(song)
                            menuExpanded = false
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Editar metadatos") },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = {
                            appState.openSongEdit(song)
                            menuExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Detalles técnicos") },
                        leadingIcon = { Icon(Icons.Rounded.Info, null) },
                        onClick = {
                            appState.openSongDetails(song)
                            menuExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Copiar enlace de YouTube Music") },
                        leadingIcon = { Icon(Icons.Rounded.Share, null) },
                        onClick = {
                            copyTextToClipboard("https://music.youtube.com/watch?v=${song.id}")
                            appState.showStatus("Enlace copiado")
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
                    song.artists.firstOrNull()?.id?.takeIf { !it.startsWith("artist_") && !it.startsWith("lt_") }?.let { artistId ->
                        DropdownMenuItem(
                            text = { Text("Ir al artista") },
                            leadingIcon = { Icon(Icons.Rounded.Person, null) },
                            onClick = {
                                appState.navigate(NavDestination.Artist(artistId))
                                menuExpanded = false
                            },
                        )
                    }
                    if (song.isUploaded) {
                        DropdownMenuItem(
                            text = { Text("Eliminar canción subida") },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                            onClick = {
                                appState.deleteUploadedSong(song)
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
    val songs by appState.playlistPickerSongs.collectAsState()
    if (songs.isEmpty()) return
    val playlists by appState.playlists.collectAsState()
    val settings by appState.settings.collectAsState()
    var newName by remember { mutableStateOf("") }
    var syncToYtm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = appState::closePlaylistPicker,
        title = {
            Text(
                if (songs.size == 1) "Añadir a playlist" else "Añadir ${songs.size} canciones a playlist",
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().width(420.dp)) {
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
                            val nameToCreate = newName.trim()
                            scope.launch(Dispatchers.IO) {
                                val created = appState.database.createPlaylist(nameToCreate)
                                appState.addSongsToPlaylist(created, songs)
                            }
                            appState.closePlaylistPicker()
                        },
                    ) { Text("Crear") }
                }
                if (settings.isLoggedIn) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = syncToYtm, onCheckedChange = { syncToYtm = it })
                        Text("Crear también en YouTube Music", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(10.dp))
                LazyColumn(modifier = Modifier.height(230.dp)) {
                    items(playlists.filter { it.isEditable }) { pl ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = "Añadir a ${pl.name}",
                                ) {
                                    appState.addSongsToPlaylist(pl, songs)
                                    appState.closePlaylistPicker()
                                }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(pl.name, fontWeight = FontWeight.Medium)
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

    val settings by appState.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val webView2Available = remember { WindowsWebView2Auth.isAvailable() }
    var manualMode by remember { mutableStateOf(!webView2Available) }
    var cookieInput by remember(open) { mutableStateOf(settings.cookie) }
    var visitorDataInput by remember(open) { mutableStateOf(settings.visitorData) }
    var dataSyncIdInput by remember(open) { mutableStateOf(settings.dataSyncId) }
    var authUserInput by remember(open) { mutableStateOf(settings.authUser) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var channels by remember { mutableStateOf<List<YouTubeAccount>>(emptyList()) }
    var loadingChannels by remember { mutableStateOf(false) }

    LaunchedEffect(open, settings.isLoggedIn) {
        if (open && settings.isLoggedIn) {
            loadingChannels = true
            channels = withContext(Dispatchers.IO) {
                runCatching { YouTube.accountsList().getOrNull().orEmpty() }.getOrDefault(emptyList())
            }
            loadingChannels = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!loading) appState.closeLoginDialog() },
        title = {
            Text(
                if (settings.isLoggedIn) "Cuenta de YouTube Music" else "Iniciar sesión en YouTube Music",
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(modifier = Modifier.width(460.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (settings.isLoggedIn) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(settings.accountName.ifBlank { "Cuenta conectada" }, fontWeight = FontWeight.Bold)
                            if (settings.accountEmail.isNotBlank()) {
                                Text(settings.accountEmail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (settings.accountChannelHandle.isNotBlank()) {
                                Text(settings.accountChannelHandle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Usar sesión para explorar (Recomendaciones)", style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = settings.useLoginForBrowse,
                            onCheckedChange = { v -> scope.launch { appState.preferences.set(DesktopPrefKeys.UseLoginForBrowse, v) } },
                        )
                    }

                    if (loadingChannels) {
                        Text("Cargando canales disponibles...", style = MaterialTheme.typography.bodySmall)
                    } else if (channels.size > 1) {
                        Text("Cambiar canal de YouTube", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        channels.forEachIndexed { idx, ch ->
                            val subtitle = ch.channelHandle ?: ch.byline.orEmpty()
                            OutlinedButton(
                                onClick = {
                                    appState.switchChannel(
                                        authUserIndex = idx.toString(),
                                        name = ch.name,
                                        email = ch.byline.orEmpty(),
                                        handle = ch.channelHandle.orEmpty(),
                                        dataSyncId = ch.dataSyncId,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("${if (ch.isSelected) "✓ " else ""}${ch.name} $subtitle".trim())
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { appState.syncLibraryWithYtm() }, modifier = Modifier.weight(1f)) {
                            Text("Sincronizar ahora")
                        }
                        if (webView2Available) {
                            OutlinedButton(onClick = {
                                loading = true
                                scope.launch {
                                    appState.loginWithWebView2(switchChannel = true)
                                    loading = false
                                }
                            }) { Text("Cambiar cuenta") }
                        }
                    }

                    HorizontalDivider()
                    Text("Cerrar sesión", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                appState.logout(clearLocalLibrary = false)
                                appState.closeLoginDialog()
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Conservar datos") }
                        OutlinedButton(
                            onClick = {
                                appState.logout(clearLocalLibrary = true)
                                appState.closeLoginDialog()
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Limpiar biblioteca") }
                    }
                } else if (webView2Available) {
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
                                if (res.isSuccess) appState.closeLoginDialog()
                                else error = res.exceptionOrNull()?.message ?: "Error al iniciar sesión"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (loading) "Esperando inicio de sesión..." else "Abrir WebView2 (Edge integrado)")
                    }
                }

                OutlinedButton(
                    enabled = !loading,
                    onClick = { manualMode = !manualMode },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (manualMode) "Ocultar editor avanzado de tokens" else "Editor avanzado de cookies / tokens")
                }

                if (manualMode) {
                    OutlinedTextField(
                        value = cookieInput,
                        onValueChange = { cookieInput = it },
                        label = { Text("Cookie (SAPISID)") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = visitorDataInput,
                        onValueChange = { visitorDataInput = it },
                        label = { Text("Visitor Data") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = dataSyncIdInput,
                            onValueChange = { dataSyncIdInput = it },
                            label = { Text("DataSync ID") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = authUserInput,
                            onValueChange = { authUserInput = it },
                            label = { Text("AuthUser") },
                            singleLine = true,
                            modifier = Modifier.width(100.dp),
                        )
                    }
                }

                if (error != null) {
                    Text(text = error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
                                authUser = authUserInput.trim().ifBlank { "0" },
                            )
                            loading = false
                            if (res.isSuccess) appState.closeLoginDialog()
                            else error = res.exceptionOrNull()?.message ?: "Cookies inválidas"
                        }
                    },
                ) { Text("Guardar tokens") }
            }
        },
        dismissButton = {
            TextButton(enabled = !loading, onClick = appState::closeLoginDialog) { Text("Cerrar") }
        },
    )
}
