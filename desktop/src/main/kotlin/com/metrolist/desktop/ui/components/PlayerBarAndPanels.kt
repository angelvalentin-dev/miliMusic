package com.metrolist.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.audio.RepeatMode
import com.metrolist.desktop.state.DesktopAppState
import com.metrolist.desktop.state.NavDestination

@Composable
fun DesktopPlayerBar(appState: DesktopAppState) {
    val pb by appState.playbackState.collectAsState()
    val showLyrics by appState.showLyricsPanel.collectAsState()
    val showQueue by appState.showQueuePanel.collectAsState()
    val likedSongs by appState.likedSongs.collectAsState()
    val song = pb.currentSong

    var draggingPosition by remember { mutableStateOf<Float?>(null) }

    Surface(
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().height(88.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left: track info
                Row(
                    modifier = Modifier.weight(0.30f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArtworkImage(
                        url = song?.thumbnailUrl,
                        contentDescription = song?.title,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = song != null) { appState.toggleFullPlayer() },
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song?.title ?: "Sin reproducción",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(enabled = song != null) {
                                appState.toggleFullPlayer()
                            },
                        )
                        Text(
                            text = song?.artistDisplay ?: "Elige una canción para comenzar",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(enabled = song?.artists?.firstOrNull()?.id?.startsWith("artist_") == false) {
                                song?.artists?.firstOrNull()?.id?.let { appState.navigate(NavDestination.Artist(it)) }
                            },
                        )
                    }
                    if (song != null) {
                        val isLiked = song.liked || likedSongs.any { it.id == song.id }
                        IconButton(onClick = { appState.toggleLike(song) }) {
                            Icon(
                                imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = "Me gusta",
                                tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Center: transport controls + seek slider
                Column(
                    modifier = Modifier.weight(0.42f).padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconButton(onClick = { appState.audioPlayer.toggleShuffle() }, modifier = Modifier.size(34.dp)) {
                            Icon(
                                Icons.Rounded.Shuffle,
                                contentDescription = "Aleatorio",
                                tint = if (pb.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        IconButton(onClick = { appState.audioPlayer.previous() }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.SkipPrevious, contentDescription = "Anterior")
                        }
                        FilledIconButton(
                            onClick = { appState.audioPlayer.togglePlayPause() },
                            enabled = song != null,
                            modifier = Modifier.size(40.dp),
                        ) {
                            if (pb.isBuffering) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = if (pb.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (pb.isPlaying) "Pausar" else "Reproducir",
                                )
                            }
                        }
                        IconButton(onClick = { appState.audioPlayer.next() }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.SkipNext, contentDescription = "Siguiente")
                        }
                        IconButton(onClick = { appState.audioPlayer.cycleRepeatMode() }, modifier = Modifier.size(34.dp)) {
                            Icon(
                                imageVector = if (pb.repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                                contentDescription = "Repetir",
                                tint = if (pb.repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val durationMs = pb.durationMs.coerceAtLeast(1L)
                        val displayPos = draggingPosition?.let { (it * durationMs).toLong() } ?: pb.positionMs
                        Text(
                            text = formatTimeMs(displayPos),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(40.dp),
                        )
                        Slider(
                            value = draggingPosition ?: (pb.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f),
                            onValueChange = { draggingPosition = it },
                            onValueChangeFinished = {
                                draggingPosition?.let { frac ->
                                    appState.audioPlayer.seekTo((frac * durationMs).toLong())
                                }
                                draggingPosition = null
                            },
                            enabled = song != null && pb.durationMs > 0L,
                            modifier = Modifier.weight(1f).height(20.dp),
                        )
                        Text(
                            text = formatTimeMs(pb.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(40.dp),
                        )
                    }
                }

                // Right: lyrics, queue, volume
                Row(
                    modifier = Modifier.weight(0.28f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(onClick = appState::toggleLyricsPanel) {
                        Icon(
                            Icons.Rounded.Lyrics,
                            contentDescription = "Letras",
                            tint = if (showLyrics) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = appState::toggleQueuePanel) {
                        Icon(
                            Icons.AutoMirrored.Rounded.QueueMusic,
                            contentDescription = "Cola",
                            tint = if (showQueue) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { appState.audioPlayer.toggleMute() }) {
                        Icon(
                            imageVector = if (pb.isMuted || pb.volume == 0f) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                            contentDescription = "Silenciar",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        value = if (pb.isMuted) 0f else pb.volume,
                        onValueChange = { appState.audioPlayer.setVolume(it) },
                        modifier = Modifier.width(105.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun RightSidePanel(appState: DesktopAppState) {
    val showLyrics by appState.showLyricsPanel.collectAsState()
    val showQueue by appState.showQueuePanel.collectAsState()
    val visible = showLyrics || showQueue

    AnimatedVisibility(visible = visible) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.width(340.dp).fillMaxHeight(),
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                )
                Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = if (showLyrics) "Letras" else "Cola de reproducción",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        IconButton(onClick = {
                            if (showLyrics) appState.toggleLyricsPanel() else appState.toggleQueuePanel()
                        }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Cerrar")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (showLyrics) {
                        LyricsContentView(appState = appState, centered = false)
                    } else {
                        QueueContentView(appState = appState)
                    }
                }
            }
        }
    }
}

@Composable
fun LyricsContentView(appState: DesktopAppState, centered: Boolean) {
    val pb by appState.playbackState.collectAsState()
    val payload by appState.currentLyrics.collectAsState()
    val loading by appState.lyricsLoading.collectAsState()
    val listState = rememberLazyListState()

    if (loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (payload == null || payload!!.entries.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = if (pb.currentSong == null) "Reproduce una canción para ver la letra" else "No se encontraron letras",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val lyrics = payload!!
    val activeIndex = if (lyrics.isSynced) {
        lyrics.entries.indexOfLast { it.timeMs <= pb.positionMs + 200L }.coerceAtLeast(0)
    } else -1

    LaunchedEffect(activeIndex) {
        if (lyrics.isSynced && activeIndex >= 0) {
            listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Fuente: ${lyrics.provider}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(lyrics.entries) { idx, line ->
                val active = idx == activeIndex
                Text(
                    text = line.text.ifBlank { "♪" },
                    style = if (active) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = lyrics.isSynced) {
                            appState.audioPlayer.seekTo(line.timeMs)
                        }
                        .padding(vertical = 4.dp, horizontal = 6.dp),
                )
            }
        }
    }
}

@Composable
fun QueueContentView(appState: DesktopAppState) {
    val pb by appState.playbackState.collectAsState()
    if (pb.queue.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("La cola está vacía", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(pb.queue) { idx, song ->
            val current = idx == pb.currentIndex
            Surface(
                color = if (current) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { appState.audioPlayer.jumpToIndex(idx) },
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArtworkImage(song.thumbnailUrl, song.title, modifier = Modifier.size(38.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = song.artistDisplay,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (idx > 0) {
                        IconButton(onClick = { appState.audioPlayer.reorderQueue(idx, idx - 1) }, modifier = Modifier.size(26.dp)) {
                            Icon(Icons.Rounded.ArrowUpward, null, modifier = Modifier.size(16.dp))
                        }
                    }
                    if (idx < pb.queue.lastIndex) {
                        IconButton(onClick = { appState.audioPlayer.reorderQueue(idx, idx + 1) }, modifier = Modifier.size(26.dp)) {
                            Icon(Icons.Rounded.ArrowDownward, null, modifier = Modifier.size(16.dp))
                        }
                    }
                    IconButton(onClick = { appState.audioPlayer.removeFromQueue(idx) }, modifier = Modifier.size(26.dp)) {
                        Icon(Icons.Rounded.Close, null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun FullPlayerOverlay(appState: DesktopAppState) {
    val open by appState.showFullPlayer.collectAsState()
    val pb by appState.playbackState.collectAsState()
    val song = pb.currentSong

    AnimatedVisibility(visible = open && song != null) {
        if (song == null) return@AnimatedVisibility
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            MaterialTheme.colorScheme.surface,
                        ),
                    ),
                )
                .padding(28.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Reproduciendo ahora", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    IconButton(onClick = appState::toggleFullPlayer) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Minimizar")
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(0.45f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ArtworkImage(
                            url = song.thumbnailUrl,
                            contentDescription = song.title,
                            modifier = Modifier.size(340.dp),
                        )
                        Spacer(Modifier.height(20.dp))
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = song.artistDisplay,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(0.55f).fillMaxHeight(),
                    ) {
                        Box(modifier = Modifier.padding(20.dp)) {
                            LyricsContentView(appState = appState, centered = true)
                        }
                    }
                }
            }
        }
    }
}
