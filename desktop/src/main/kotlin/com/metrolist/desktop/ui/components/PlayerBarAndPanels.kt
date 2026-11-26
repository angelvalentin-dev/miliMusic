package com.metrolist.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.desktop.audio.RepeatMode
import com.metrolist.desktop.data.DesktopLyricsPosition
import com.metrolist.desktop.data.DesktopPlayerBackground
import com.metrolist.desktop.data.DesktopSliderStyle
import com.metrolist.desktop.state.DesktopAppState
import com.metrolist.desktop.state.LyricsToolMode
import com.metrolist.desktop.state.NavDestination
import com.metrolist.desktop.ui.icons.ArrowDownward
import com.metrolist.desktop.ui.icons.ArrowUpward
import com.metrolist.desktop.ui.icons.Lyrics
import com.metrolist.desktop.ui.icons.Pause
import com.metrolist.desktop.ui.icons.QueueMusic
import com.metrolist.desktop.ui.icons.Repeat
import com.metrolist.desktop.ui.icons.RepeatOne
import com.metrolist.desktop.ui.icons.Shuffle
import com.metrolist.desktop.ui.icons.SkipNext
import com.metrolist.desktop.ui.icons.SkipPrevious
import com.metrolist.desktop.ui.icons.VolumeOff
import com.metrolist.desktop.ui.icons.VolumeUp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun DesktopPlayerBar(appState: DesktopAppState) {
    val pb by appState.playbackState.collectAsState()
    val settings by appState.settings.collectAsState()
    val showLyrics by appState.showLyricsPanel.collectAsState()
    val showQueue by appState.showQueuePanel.collectAsState()
    val likedSongs by appState.likedSongs.collectAsState()
    val ringingAlarm by appState.alarmScheduler.ringingAlarm.collectAsState()
    val song = pb.currentSong

    var draggingPosition by remember { mutableStateOf<Float?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Ringing alarm snooze/dismiss banner
        if (ringingAlarm != null) {
            val alarm = ringingAlarm!!
            Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Alarma musical (${"%02d:%02d".format(alarm.hour, alarm.minute)})${alarm.label.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()}",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { appState.alarmScheduler.snoozeRingingAlarm(5) }) {
                            Text("Posponer 5 min")
                        }
                        Button(onClick = { appState.alarmScheduler.dismissRingingAlarm() }) {
                            Text("Descartar")
                        }
                    }
                }
            }
        }

        Surface(
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp)
                .semantics { contentDescription = "Barra del reproductor multimedia" },
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
                            contentDescription = song?.let { "Carátula de ${it.title}" } ?: "Sin carátula",
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(
                                    enabled = song != null,
                                    role = Role.Button,
                                    onClickLabel = "Abrir reproductor a pantalla completa",
                                ) { appState.toggleFullPlayer() },
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song?.title ?: "Sin reproducción",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable(
                                    enabled = song != null,
                                    role = Role.Button,
                                    onClickLabel = "Abrir reproductor a pantalla completa",
                                ) { appState.toggleFullPlayer() },
                            )
                            Text(
                                text = song?.artistDisplay ?: "Elige una canción para comenzar",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable(
                                    enabled = song?.artists?.firstOrNull()?.id?.startsWith("artist_") == false,
                                    role = Role.Button,
                                    onClickLabel = "Ir a la página del artista ${song?.artistDisplay.orEmpty()}",
                                ) {
                                    song?.artists?.firstOrNull()?.id?.let { appState.navigate(NavDestination.Artist(it)) }
                                },
                            )
                        }
                        if (song != null) {
                            val isLiked = remember(likedSongs, song.id) { likedSongs.any { it.id == song.id } }
                            IconButton(onClick = { appState.toggleLike(song) }) {
                                Icon(
                                    imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    contentDescription = if (isLiked) "Quitar ${song.title} de Me gusta" else "Añadir ${song.title} a Me gusta",
                                    tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // Center: transport controls + seek slider
                    Column(
                        modifier = Modifier.weight(0.40f).padding(horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            IconButton(onClick = { appState.audioPlayer.toggleShuffle() }, modifier = Modifier.size(34.dp)) {
                                Icon(
                                    Icons.Rounded.Shuffle,
                                    contentDescription = if (pb.shuffle) "Modo aleatorio activado" else "Modo aleatorio desactivado",
                                    tint = if (pb.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            IconButton(onClick = { appState.audioPlayer.previous() }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Canción anterior (Ctrl+Izquierda)")
                            }
                            FilledIconButton(
                                onClick = { appState.audioPlayer.togglePlayPause() },
                                enabled = song != null,
                                modifier = Modifier.size(40.dp),
                            ) {
                                if (pb.isBuffering) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp).semantics { contentDescription = "Cargando audio" },
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (pb.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = if (pb.isPlaying) "Pausar (Espacio)" else "Reproducir (Espacio)",
                                    )
                                }
                            }
                            IconButton(onClick = { appState.audioPlayer.next() }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Rounded.SkipNext, contentDescription = "Siguiente canción (Ctrl+Derecha)")
                            }
                            val repeatDesc = when (pb.repeatMode) {
                                RepeatMode.OFF -> "Repetir desactivado"
                                RepeatMode.ALL -> "Repetir toda la cola activado"
                                RepeatMode.ONE -> "Repetir canción actual activado"
                            }
                            IconButton(onClick = { appState.audioPlayer.cycleRepeatMode() }, modifier = Modifier.size(34.dp)) {
                                Icon(
                                    imageVector = if (pb.repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                                    contentDescription = repeatDesc,
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
                            val sliderHeight = if (settings.sliderStyle == DesktopSliderStyle.SLIM) 14.dp else 20.dp
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
                                modifier = Modifier
                                    .weight(1f)
                                    .height(sliderHeight)
                                    .semantics {
                                        contentDescription = "Posición de reproducción"
                                        stateDescription = "${formatTimeMs(displayPos)} de ${formatTimeMs(pb.durationMs)}"
                                    },
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

                    // Right: EQ/speed/pitch, sleep timer, lyrics, queue, volume
                    Row(
                        modifier = Modifier.weight(0.34f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) {
                        val eqOrSpeedActive = pb.eqEnabled ||
                            kotlin.math.abs(pb.playbackSpeed - 1.0f) >= 0.03f ||
                            kotlin.math.abs(pb.playbackPitch - 1.0f) >= 0.03f
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (eqOrSpeedActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = "Abrir ecualizador, tono y velocidad",
                                ) { appState.openEqualizerDialog() },
                        ) {
                            Text(
                                text = if (kotlin.math.abs(pb.playbackSpeed - 1.0f) >= 0.03f) {
                                    "EQ • ${"%.2f".format(pb.playbackSpeed).trimEnd('0').trimEnd('.')}x"
                                } else "EQ",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (eqOrSpeedActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        val sleepActive = pb.sleepTimerEndOfTrack || pb.sleepTimerRemainingMs != null
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (sleepActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = "Configurar temporizador de apagado",
                                ) { appState.openSleepTimerDialog() },
                        ) {
                            Text(
                                text = when {
                                    pb.sleepTimerRemainingMs != null -> "⏱ ${formatTimeMs(pb.sleepTimerRemainingMs!!)}"
                                    pb.sleepTimerEndOfTrack -> "⏱ Fin"
                                    else -> "⏱"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (sleepActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            )
                        }
                        IconButton(onClick = appState::toggleLyricsPanel) {
                            Icon(
                                Icons.Rounded.Lyrics,
                                contentDescription = if (showLyrics) "Ocultar letras (Ctrl+L)" else "Mostrar letras (Ctrl+L)",
                                tint = if (showLyrics) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = appState::toggleQueuePanel) {
                            Icon(
                                Icons.AutoMirrored.Rounded.QueueMusic,
                                contentDescription = if (showQueue) "Ocultar cola de reproducción (Ctrl+Q)" else "Mostrar cola de reproducción (Ctrl+Q)",
                                tint = if (showQueue) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { appState.audioPlayer.toggleMute() }) {
                            Icon(
                                imageVector = if (pb.isMuted || pb.volume == 0f) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                                contentDescription = if (pb.isMuted || pb.volume == 0f) "Activar sonido (Ctrl+M)" else "Silenciar (Ctrl+M)",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val effectiveVolPercent = ((if (pb.isMuted) 0f else pb.volume) * 100f).roundToInt()
                        Slider(
                            value = if (pb.isMuted) 0f else pb.volume,
                            onValueChange = { appState.audioPlayer.setVolume(it) },
                            modifier = Modifier
                                .width(88.dp)
                                .semantics {
                                    contentDescription = "Volumen"
                                    stateDescription = "$effectiveVolPercent por ciento"
                                },
                        )
                    }
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
            modifier = Modifier.width(360.dp).fillMaxHeight(),
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
                            modifier = Modifier.semantics { heading() },
                        )
                        IconButton(onClick = {
                            if (showLyrics) appState.toggleLyricsPanel() else appState.toggleQueuePanel()
                        }) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = if (showLyrics) "Cerrar panel de letras" else "Cerrar panel de cola",
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LyricsContentView(appState: DesktopAppState, centered: Boolean) {
    val pb by appState.playbackState.collectAsState()
    val settings by appState.settings.collectAsState()
    val payload by appState.currentLyrics.collectAsState()
    val loading by appState.lyricsLoading.collectAsState()
    val translating by appState.lyricsTranslating.collectAsState()
    val showTranslation by appState.showLyricsTranslation.collectAsState()
    val listState = rememberLazyListState()
    val song = pb.currentSong

    var songOffsetMs by remember(song?.id) { mutableStateOf(0) }
    LaunchedEffect(song?.id, payload) {
        if (song != null) {
            songOffsetMs = withContext(Dispatchers.IO) {
                appState.database.getSong(song.id)?.lyricsOffset ?: 0
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Lyrics quick-tools toolbar
        if (song != null) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                FilterChip(
                    selected = showTranslation && payload?.translatedLines?.isNotEmpty() == true,
                    onClick = {
                        if (payload?.translatedLines.isNullOrEmpty()) appState.translateCurrentLyrics()
                        else appState.toggleLyricsTranslationVisibility()
                    },
                    label = { Text(if (translating) "Traduciendo..." else "IA Traducir", style = MaterialTheme.typography.labelSmall) },
                    enabled = !translating && payload != null,
                )
                FilterChip(
                    selected = false,
                    onClick = { appState.toggleCurrentSongRomanization() },
                    label = { Text("Romanizar", style = MaterialTheme.typography.labelSmall) },
                )
                FilterChip(
                    selected = false,
                    onClick = { appState.openLyricsTool(LyricsToolMode.SEARCH) },
                    label = { Text("Buscar", style = MaterialTheme.typography.labelSmall) },
                )
                FilterChip(
                    selected = false,
                    onClick = { appState.openLyricsTool(LyricsToolMode.EDIT) },
                    label = { Text("Editar", style = MaterialTheme.typography.labelSmall) },
                )
                FilterChip(
                    selected = songOffsetMs != 0,
                    onClick = { appState.openLyricsTool(LyricsToolMode.OFFSET) },
                    label = { Text(if (songOffsetMs == 0) "Desfase" else "${songOffsetMs}ms", style = MaterialTheme.typography.labelSmall) },
                )
                if (payload != null) {
                    FilterChip(
                        selected = false,
                        onClick = { appState.openLyricsTool(LyricsToolMode.SHARE_IMAGE) },
                        label = { Text("Tarjeta PNG", style = MaterialTheme.typography.labelSmall) },
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            copyTextToClipboard(payload!!.entries.joinToString("\n") { it.text })
                            appState.showStatus("Letra copiada al portapapeles")
                        },
                        label = { Text("Copiar", style = MaterialTheme.typography.labelSmall) },
                    )
                }
                IconButton(onClick = { appState.refetchCurrentLyrics() }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Recargar letras", modifier = Modifier.size(16.dp))
                }
            }
        }

        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = "Cargando letras" })
            }
            return
        }
        if (payload == null || payload!!.entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (song == null) "Reproduce una canción para ver la letra" else "No se encontraron letras automáticamente",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (song != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { appState.openLyricsTool(LyricsToolMode.SEARCH) }) { Text("Buscar manual") }
                            OutlinedButton(onClick = { appState.openLyricsTool(LyricsToolMode.EDIT) }) { Text("Pegar / .lrc") }
                        }
                    }
                }
            }
            return
        }

        val lyrics = payload!!
        val adjustedPosMs = pb.positionMs + songOffsetMs
        val activeIndex = if (lyrics.isSynced) {
            lyrics.entries.indexOfLast { it.timeMs <= adjustedPosMs + 200L }.coerceAtLeast(0)
        } else -1

        LaunchedEffect(activeIndex, settings.lyricsScroll) {
            if (lyrics.isSynced && settings.lyricsScroll && activeIndex >= 0) {
                listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
            }
        }

        Text(
            text = "Fuente: ${lyrics.provider}${if (lyrics.hasWordTimings) " • Karaoke palabra por palabra" else ""}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))

        val baseAlignment = when {
            centered -> TextAlign.Center
            settings.lyricsPosition == DesktopLyricsPosition.LEFT -> TextAlign.Start
            settings.lyricsPosition == DesktopLyricsPosition.RIGHT -> TextAlign.End
            else -> TextAlign.Center
        }
        val primaryColor = MaterialTheme.colorScheme.primary
        val dimColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
        val activeLineSize = (settings.lyricsTextSize + 2f).sp
        val inactiveLineSize = settings.lyricsTextSize.sp

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy((10f * settings.lyricsLineSpacing).dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(lyrics.entries) { idx, line ->
                val active = idx == activeIndex
                // Instrumental break dots before long gap (> 6 seconds)
                val prevTime = lyrics.entries.getOrNull(idx - 1)?.timeMs
                if (settings.showIntervalIndicator && lyrics.isSynced && prevTime != null && line.timeMs - prevTime > 6_000L && idx == activeIndex + 1) {
                    Text(
                        text = "•  •  •",
                        style = MaterialTheme.typography.titleMedium,
                        color = primaryColor.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }

                val lineAlignment = if (settings.respectAgentPositioning && line.agent != null) {
                    when (line.agent.lowercase()) {
                        "v2", "v1000" -> TextAlign.End
                        "v1" -> TextAlign.Start
                        else -> baseAlignment
                    }
                } else baseAlignment

                val mainText = if (settings.lyricsRomanizeAsMain && !line.romanizedText.isNullOrBlank()) {
                    line.romanizedText
                } else {
                    line.text.ifBlank { "♪" }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            enabled = lyrics.isSynced && settings.lyricsClick,
                            role = Role.Button,
                            onClickLabel = "Saltar al segundo ${formatTimeMs(line.timeMs)}",
                        ) {
                            appState.audioPlayer.seekTo((line.timeMs - songOffsetMs).coerceAtLeast(0L))
                        }
                        .padding(vertical = 4.dp, horizontal = 6.dp),
                ) {
                    if (active && line.words.isNotEmpty() && !settings.lyricsRomanizeAsMain) {
                        val annotated = buildAnnotatedString {
                            line.words.forEachIndexed { wIdx, w ->
                                val sung = adjustedPosMs >= w.startTimeMs
                                withStyle(
                                    SpanStyle(
                                        color = if (sung) primaryColor else dimColor,
                                        fontWeight = if (sung) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    ),
                                ) {
                                    append(w.text)
                                }
                                if (wIdx < line.words.lastIndex) append(" ")
                            }
                        }
                        Text(
                            text = annotated,
                            fontSize = if (line.isBackground) (settings.lyricsTextSize - 2f).sp else activeLineSize,
                            fontStyle = if (line.isBackground) FontStyle.Italic else FontStyle.Normal,
                            textAlign = lineAlignment,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            text = mainText,
                            fontSize = when {
                                line.isBackground -> (settings.lyricsTextSize - 3f).sp
                                active -> activeLineSize
                                else -> inactiveLineSize
                            },
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (line.isBackground) FontStyle.Italic else FontStyle.Normal,
                            color = if (active) primaryColor else dimColor,
                            textAlign = lineAlignment,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (!settings.lyricsRomanizeAsMain && !line.romanizedText.isNullOrBlank()) {
                        Text(
                            text = line.romanizedText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (active) primaryColor.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = lineAlignment,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        )
                    }

                    if (showTranslation && !line.translatedText.isNullOrBlank()) {
                        Text(
                            text = line.translatedText,
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.tertiary,
                            textAlign = lineAlignment,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun QueueContentView(appState: DesktopAppState) {
    val pb by appState.playbackState.collectAsState()
    var editLocked by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${pb.queue.size} en cola", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(
                    selected = editLocked,
                    onClick = { editLocked = !editLocked },
                    label = { Text(if (editLocked) "Bloqueada" else "Editar", style = MaterialTheme.typography.labelSmall) },
                )
                if (pb.queue.isNotEmpty()) {
                    TextButton(onClick = { appState.audioPlayer.clearQueue() }) {
                        Text("Limpiar", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        if (pb.queue.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("La cola está vacía", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
            itemsIndexed(pb.queue) { idx, song ->
                val current = idx == pb.currentIndex
                Surface(
                    color = if (current) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "Reproducir ${song.title} de la cola",
                        ) { appState.audioPlayer.jumpToIndex(idx) },
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
                        if (!editLocked) {
                            if (idx > 0) {
                                IconButton(onClick = { appState.audioPlayer.reorderQueue(idx, idx - 1) }, modifier = Modifier.size(26.dp)) {
                                    Icon(Icons.Rounded.ArrowUpward, contentDescription = "Subir ${song.title}", modifier = Modifier.size(16.dp))
                                }
                            }
                            if (idx < pb.queue.lastIndex) {
                                IconButton(onClick = { appState.audioPlayer.reorderQueue(idx, idx + 1) }, modifier = Modifier.size(26.dp)) {
                                    Icon(Icons.Rounded.ArrowDownward, contentDescription = "Bajar ${song.title}", modifier = Modifier.size(16.dp))
                                }
                            }
                            IconButton(onClick = { appState.audioPlayer.removeFromQueue(idx) }, modifier = Modifier.size(26.dp)) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Quitar ${song.title}", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            if (pb.automixQueue.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Contenido similar (Automix)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                items(pb.automixQueue) { rec ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ArtworkImage(rec.thumbnailUrl, rec.title, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(rec.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(rec.artistDisplay, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(
                            onClick = {
                                appState.audioPlayer.addToQueue(rec)
                                appState.showStatus("Añadida a la cola: ${rec.title}")
                            },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "Añadir ${rec.title} a la cola", modifier = Modifier.size(16.dp))
                        }
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
    val settings by appState.settings.collectAsState()
    val song = pb.currentSong

    AnimatedVisibility(visible = open && song != null) {
        if (song == null) return@AnimatedVisibility
        val bgModifier = when (settings.playerBackground) {
            DesktopPlayerBackground.DEFAULT -> Modifier.background(MaterialTheme.colorScheme.surface)
            DesktopPlayerBackground.BLUR -> Modifier.background(
                Brush.radialGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                        MaterialTheme.colorScheme.surfaceContainerLow,
                        MaterialTheme.colorScheme.surface,
                    ),
                ),
            )
            DesktopPlayerBackground.GRADIENT -> Modifier.background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        MaterialTheme.colorScheme.surface,
                    ),
                ),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(bgModifier)
                .padding(28.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Reproduciendo ahora",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { appState.openSongDetails(song) }) {
                            Icon(Icons.Rounded.Info, contentDescription = "Detalles técnicos de ${song.title}")
                        }
                        IconButton(onClick = appState::toggleFullPlayer) {
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Minimizar reproductor a pantalla completa")
                        }
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
                            contentDescription = "Carátula de ${song.title}",
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
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { appState.openEqualizerDialog() }) { Text("EQ / Tono") }
                            OutlinedButton(onClick = { appState.openSleepTimerDialog() }) { Text("Temporizador") }
                            OutlinedButton(onClick = { appState.openLyricsTool(LyricsToolMode.SHARE_IMAGE) }) { Text("Compartir letra") }
                        }
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.72f),
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
