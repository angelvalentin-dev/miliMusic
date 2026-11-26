package com.metrolist.desktop.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.audio.AutoEqEntry
import com.metrolist.desktop.audio.DesktopEqPresets
import com.metrolist.desktop.audio.DesktopEqProfileStore
import com.metrolist.desktop.audio.DesktopParametricEq
import com.metrolist.desktop.audio.SavedEqProfile
import com.metrolist.desktop.data.DesktopPrefKeys
import com.metrolist.desktop.db.DesktopAlbumStat
import com.metrolist.desktop.db.DesktopArtistStat
import com.metrolist.desktop.db.DesktopSongStat
import com.metrolist.desktop.db.ListeningSummary
import com.metrolist.desktop.lyrics.DesktopLyricsCardRenderer
import com.metrolist.desktop.state.DesktopAppState
import com.metrolist.desktop.state.NavDestination
import com.metrolist.desktop.ui.components.ArtworkImage
import com.metrolist.desktop.ui.components.SongRowItem
import com.metrolist.desktop.ui.components.copyTextToClipboard
import com.metrolist.desktop.ui.components.formatTimeMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.math.roundToInt

private enum class StatsPeriod(val label: String, val daysBack: Long?) {
    WEEK("7 días", 7L),
    MONTH("30 días", 30L),
    QUARTER("3 meses", 90L),
    YEAR("1 año", 365L),
    ALL("Todo el tiempo", null),
}

fun formatDurationHuman(ms: Long): String {
    val totalMin = (ms / 60_000L).coerceAtLeast(0L)
    val hours = totalMin / 60L
    val mins = totalMin % 60L
    return when {
        hours >= 1L -> "${hours}h ${mins}m"
        totalMin >= 1L -> "$totalMin min"
        else -> "${(ms / 1000L).coerceAtLeast(0L)} s"
    }
}

@Composable
fun StatsScreen(appState: DesktopAppState) {
    val pb by appState.playbackState.collectAsState()
    val recentlyPlayed by appState.recentlyPlayed.collectAsState()
    var period by remember { mutableStateOf(StatsPeriod.MONTH) }
    var artistFilter by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf(ListeningSummary()) }
    var topSongs by remember { mutableStateOf<List<DesktopSongStat>>(emptyList()) }
    var topArtists by remember { mutableStateOf<List<DesktopArtistStat>>(emptyList()) }
    var topAlbums by remember { mutableStateOf<List<DesktopAlbumStat>>(emptyList()) }

    LaunchedEffect(period, recentlyPlayed.size) {
        val now = System.currentTimeMillis()
        val fromTs = period.daysBack?.let { now - it * 24L * 60L * 60L * 1000L } ?: 0L
        withContext(Dispatchers.IO) {
            summary = appState.database.listeningSummary(fromTs, now)
            topSongs = appState.database.mostPlayedSongs(fromTs, now, 30)
            topArtists = appState.database.mostPlayedArtists(fromTs, now, 20)
            topAlbums = appState.database.mostPlayedAlbums(fromTs, now, 20)
        }
    }

    val filteredSongs = remember(topSongs, artistFilter) {
        if (artistFilter.isBlank()) topSongs else topSongs.filter { it.song.artistDisplay.contains(artistFilter, true) }
    }
    val filteredArtists = remember(topArtists, artistFilter) {
        if (artistFilter.isBlank()) topArtists else topArtists.filter { it.artist.displayName.contains(artistFilter, true) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Estadísticas",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = "Tu historial real de escucha almacenado localmente",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { appState.openTimeTransferDialog() }) {
                        Text("Transferir tiempo")
                    }
                    Button(onClick = { appState.openWrappedDialog() }) {
                        Text("Abrir Wrapped")
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatsPeriod.entries.forEach { p ->
                        FilterChip(selected = period == p, onClick = { period = p }, label = { Text(p.label) })
                    }
                }
                OutlinedTextField(
                    value = artistFilter,
                    onValueChange = { artistFilter = it },
                    placeholder = { Text("Filtrar por artista...") },
                    singleLine = true,
                    modifier = Modifier.width(220.dp),
                )
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatSummaryCard("Tiempo escuchado", formatDurationHuman(summary.totalPlayTimeMs), Modifier.weight(1f))
                StatSummaryCard("Reproducciones", summary.totalPlays.toString(), Modifier.weight(1f))
                StatSummaryCard("Canciones únicas", summary.uniqueSongs.toString(), Modifier.weight(1f))
                StatSummaryCard("Artistas únicos", summary.uniqueArtists.toString(), Modifier.weight(1f))
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Canciones más escuchadas",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                if (filteredSongs.isNotEmpty()) {
                    OutlinedButton(onClick = { appState.audioPlayer.playQueue(filteredSongs.map { it.song }, 0) }) {
                        Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reproducir Top")
                    }
                }
            }
        }

        if (filteredSongs.isEmpty()) {
            item {
                Text(
                    "Aún no hay suficientes reproducciones en este periodo. Reproduce canciones y volverán a aparecer aquí.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            itemsIndexed(filteredSongs) { idx, stat ->
                Column {
                    SongRowItem(
                        song = stat.song,
                        appState = appState,
                        index = idx,
                        isCurrent = pb.currentSong?.id == stat.song.id,
                        onPlay = { appState.audioPlayer.playQueue(filteredSongs.map { it.song }, startIndex = idx) },
                    )
                    Text(
                        text = "${stat.playCount} reproducciones • ${formatDurationHuman(stat.playTimeMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 104.dp, bottom = 4.dp),
                    )
                }
            }
        }

        if (filteredArtists.isNotEmpty()) {
            item {
                Text(
                    "Artistas más escuchados",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
            }
            itemsIndexed(filteredArtists) { idx, stat ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(
                            enabled = !stat.artist.id.startsWith("artist_"),
                            role = Role.Button,
                            onClickLabel = "Ver artista ${stat.artist.displayName}",
                        ) { appState.navigate(NavDestination.Artist(stat.artist.id)) },
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("#${idx + 1}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(32.dp))
                        ArtworkImage(stat.artist.thumbnailUrl, stat.artist.displayName, isCircle = true, modifier = Modifier.size(46.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stat.artist.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${stat.playCount} reproducciones • ${formatDurationHuman(stat.playTimeMs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        if (topAlbums.isNotEmpty()) {
            item {
                Text(
                    "Álbumes más escuchados",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
            }
            itemsIndexed(topAlbums) { idx, stat ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClickLabel = "Ver álbum ${stat.album.title}") {
                            appState.navigate(NavDestination.Album(stat.album.id))
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("#${idx + 1}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(32.dp))
                        ArtworkImage(stat.album.thumbnailUrl, stat.album.title, modifier = Modifier.size(46.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stat.album.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${stat.playCount} reproducciones • ${formatDurationHuman(stat.playTimeMs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatSummaryCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun WrappedDialog(appState: DesktopAppState) {
    val open by appState.showWrappedDialog.collectAsState()
    if (!open) return

    val currentYear = remember { LocalDate.now().year }
    val yearOptions = remember { listOf<Int?>(null, currentYear, currentYear - 1) }
    var selectedYear by remember { mutableStateOf<Int?>(null) }
    var slideIndex by remember { mutableStateOf(0) }
    var summary by remember { mutableStateOf(ListeningSummary()) }
    var topSongs by remember { mutableStateOf<List<DesktopSongStat>>(emptyList()) }
    var topArtists by remember { mutableStateOf<List<DesktopArtistStat>>(emptyList()) }
    var topAlbums by remember { mutableStateOf<List<DesktopAlbumStat>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val primaryArgb = MaterialTheme.colorScheme.primary.toArgb()

    LaunchedEffect(selectedYear) {
        val zone = ZoneId.systemDefault()
        val (fromTs, toTs) = if (selectedYear == null) {
            0L to System.currentTimeMillis()
        } else {
            val start = LocalDate.of(selectedYear!!, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = LocalDate.of(selectedYear!! + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
            start to end
        }
        withContext(Dispatchers.IO) {
            summary = appState.database.listeningSummary(fromTs, toTs)
            topSongs = appState.database.mostPlayedSongs(fromTs, toTs, 15)
            topArtists = appState.database.mostPlayedArtists(fromTs, toTs, 5)
            topAlbums = appState.database.mostPlayedAlbums(fromTs, toTs, 5)
        }
    }

    val totalSlides = 7
    val yearLabel = selectedYear?.toString() ?: "Histórico"

    AlertDialog(
        onDismissRequest = appState::closeWrappedDialog,
        title = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("miliMusic Wrapped • $yearLabel", modifier = Modifier.semantics { heading() })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    yearOptions.forEach { yr ->
                        FilterChip(
                            selected = selectedYear == yr,
                            onClick = { selectedYear = yr; slideIndex = 0 },
                            label = { Text(yr?.toString() ?: "Todo") },
                        )
                    }
                }
            }
        },
        text = {
            Surface(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.width(500.dp).height(330.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                                    MaterialTheme.colorScheme.surfaceContainer,
                                ),
                            ),
                        )
                        .padding(24.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    contentAlignment = Alignment.Center,
                ) {
                    when (slideIndex) {
                        0 -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Tu resumen musical ($yearLabel)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(
                                text = "${(summary.totalPlayTimeMs / 60_000L).coerceAtLeast(0L)} minutos",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "${summary.totalPlays} reproducciones • ${summary.uniqueSongs} canciones • ${summary.uniqueArtists} artistas",
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        1 -> {
                            val top = topSongs.firstOrNull()
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Tu canción #1", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                if (top == null) {
                                    Text("Reproduce más canciones para desbloquear tu #1")
                                } else {
                                    ArtworkImage(top.song.thumbnailUrl, top.song.title, modifier = Modifier.size(130.dp))
                                    Text(top.song.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
                                    Text("${top.song.artistDisplay} • ${top.playCount} reproducciones", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        2 -> Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Tu Top 5 canciones", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            topSongs.take(5).forEachIndexed { idx, stat ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("#${idx + 1}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    ArtworkImage(stat.song.thumbnailUrl, stat.song.title, modifier = Modifier.size(34.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stat.song.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${stat.song.artistDisplay} • ${stat.playCount}x", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        3 -> {
                            val topArt = topArtists.firstOrNull()
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Tu artista #1", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                if (topArt == null) {
                                    Text("Sin datos de artistas todavía")
                                } else {
                                    ArtworkImage(topArt.artist.thumbnailUrl, topArt.artist.displayName, isCircle = true, modifier = Modifier.size(130.dp))
                                    Text(topArt.artist.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text("${topArt.playCount} reproducciones • ${formatDurationHuman(topArt.playTimeMs)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        4 -> Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Tu Top 5 artistas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            topArtists.take(5).forEachIndexed { idx, stat ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("#${idx + 1}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    ArtworkImage(stat.artist.thumbnailUrl, stat.artist.displayName, isCircle = true, modifier = Modifier.size(34.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stat.artist.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        Text("${stat.playCount} reproducciones • ${formatDurationHuman(stat.playTimeMs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        5 -> Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Tu Top 5 álbumes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            if (topAlbums.isEmpty()) {
                                Text("Sin datos de álbumes todavía", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                topAlbums.take(5).forEachIndexed { idx, stat ->
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("#${idx + 1}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                        ArtworkImage(stat.album.thumbnailUrl, stat.album.title, modifier = Modifier.size(34.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(stat.album.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${stat.playCount} reproducciones", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                        else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Guarda y comparte tu Wrapped", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(
                                "Crea una playlist local con tus canciones más escuchadas o exporta tu tarjeta resumen en PNG.",
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = topSongs.isNotEmpty(),
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            val pl = appState.database.createPlaylist("miliMusic Wrapped $yearLabel")
                                            topSongs.forEach { appState.database.addSongToPlaylist(pl.id, it.song) }
                                            appState.showStatus("Playlist \"${pl.name}\" creada")
                                        }
                                    },
                                ) { Text("Crear playlist") }
                                OutlinedButton(
                                    enabled = topSongs.isNotEmpty(),
                                    onClick = {
                                        val chooser = JFileChooser().apply { selectedFile = File("miliMusic_Wrapped_$yearLabel.png") }
                                        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                                            val target = chooser.selectedFile
                                            scope.launch(Dispatchers.IO) {
                                                val img = DesktopLyricsCardRenderer.renderWrappedCard(
                                                    yearLabel = yearLabel,
                                                    totalMinutes = summary.totalPlayTimeMs / 60_000L,
                                                    topSong = topSongs.firstOrNull()?.song?.title.orEmpty(),
                                                    topArtist = topArtists.firstOrNull()?.artist?.displayName.orEmpty(),
                                                    topSongsList = topSongs.drop(1).take(3).map { it.song.title },
                                                    accentColorArgb = primaryArgb,
                                                )
                                                DesktopLyricsCardRenderer.savePng(img, target)
                                                    .onSuccess { appState.showStatus("Tarjeta Wrapped guardada en ${it.name}") }
                                            }
                                        }
                                    },
                                ) { Text("Exportar PNG") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = slideIndex > 0, onClick = { slideIndex -= 1 }) { Text("Anterior") }
                if (slideIndex < totalSlides - 1) {
                    Button(onClick = { slideIndex += 1 }) { Text("Siguiente (${slideIndex + 1}/$totalSlides)") }
                } else {
                    Button(onClick = appState::closeWrappedDialog) { Text("Finalizar") }
                }
            }
        },
        dismissButton = { TextButton(onClick = appState::closeWrappedDialog) { Text("Cerrar") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqualizerAndSpeedDialog(appState: DesktopAppState) {
    val open by appState.showEqualizerDialog.collectAsState()
    if (!open) return

    val pb by appState.playbackState.collectAsState()
    val settings by appState.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var activeTab by remember { mutableStateOf(0) } // 0=Gráfico+Tono, 1=Perfiles & APO, 2=Asistente AutoEQ
    var savedProfiles by remember(open) { mutableStateOf(DesktopEqProfileStore.loadAll()) }
    var autoEqQuery by remember { mutableStateOf("") }
    var autoEqLoading by remember { mutableStateOf(false) }
    var autoEqIndexed by remember { mutableStateOf(false) }
    var autoEqResults by remember { mutableStateOf<List<AutoEqEntry>>(emptyList()) }

    AlertDialog(
        onDismissRequest = appState::closeEqualizerDialog,
        title = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Ecualizador, AutoEQ, Velocidad y Tono", modifier = Modifier.semantics { heading() })
            }
        },
        text = {
            Column(
                modifier = Modifier.width(600.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = activeTab == 0, onClick = { activeTab = 0 }, label = { Text("EQ Gráfico & Tono") })
                    FilterChip(selected = activeTab == 1, onClick = { activeTab = 1 }, label = { Text("ParametricEQ / APO (${savedProfiles.size})") })
                    FilterChip(
                        selected = activeTab == 2,
                        onClick = {
                            activeTab = 2
                            if (!autoEqIndexed && !autoEqLoading) {
                                autoEqLoading = true
                                scope.launch {
                                    appState.autoEqSearch.ensureIndexLoaded()
                                        .onSuccess {
                                            autoEqIndexed = true
                                            autoEqResults = appState.autoEqSearch.search(autoEqQuery)
                                        }
                                        .onFailure { appState.showStatus("Error AutoEQ: ${it.message}") }
                                    autoEqLoading = false
                                }
                            }
                        },
                        label = { Text("Asistente AutoEQ") },
                    )
                }

                when (activeTab) {
                    0 -> {
                        // Speed, Pitch & Varispeed
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Velocidad: ${"%.2f".format(pb.playbackSpeed)}x", fontWeight = FontWeight.SemiBold)
                            Text("Tono (Pitch): ${"%.2f".format(pb.playbackPitch)}x", fontWeight = FontWeight.SemiBold)
                        }
                        Slider(
                            value = pb.playbackSpeed,
                            onValueChange = { appState.audioPlayer.setPlaybackSpeed(it) },
                            valueRange = 0.5f..2.0f,
                            steps = 29,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                                FilterChip(
                                    selected = kotlin.math.abs(pb.playbackSpeed - spd) < 0.02f,
                                    onClick = { appState.audioPlayer.setPlaybackSpeed(spd) },
                                    label = { Text("${spd}x") },
                                )
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Varispeed (vincular velocidad y tono)", style = MaterialTheme.typography.bodySmall)
                            Switch(
                                checked = settings.varispeed,
                                onCheckedChange = { v -> scope.launch { appState.preferences.set(DesktopPrefKeys.Varispeed, v) } },
                            )
                        }
                        if (!settings.varispeed) {
                            Slider(
                                value = pb.playbackPitch,
                                onValueChange = { appState.audioPlayer.setPlaybackPitch(it) },
                                valueRange = 0.5f..2.0f,
                                steps = 29,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(0.8f, 0.9f, 1.0f, 1.1f, 1.2f).forEach { p ->
                                    FilterChip(
                                        selected = kotlin.math.abs(pb.playbackPitch - p) < 0.02f,
                                        onClick = { appState.audioPlayer.setPlaybackPitch(p) },
                                        label = { Text("${p}x") },
                                    )
                                }
                            }
                        }

                        HorizontalDivider()

                        // EQ Toggle + Real-Time Frequency Response Graph
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("Ecualizador 10 bandas (libVLC)", fontWeight = FontWeight.SemiBold)
                                Text("Activo: ${pb.eqPreset}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = pb.eqEnabled,
                                onCheckedChange = { appState.audioPlayer.setEqualizer(enabled = it) },
                            )
                        }

                        EqFrequencyResponseCanvas(preamp = pb.eqPreamp, bands = pb.eqBands, enabled = pb.eqEnabled)

                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            DesktopEqPresets.presets.keys.forEach { preset ->
                                FilterChip(
                                    selected = pb.eqEnabled && pb.eqPreset == preset,
                                    onClick = { appState.audioPlayer.applyEqualizerPreset(preset) },
                                    label = { Text(preset) },
                                )
                            }
                        }

                        Text("Preamplificador: ${"%.1f".format(pb.eqPreamp)} dB", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        Slider(
                            value = pb.eqPreamp,
                            onValueChange = { appState.audioPlayer.setEqualizer(enabled = true, preset = "Custom", preamp = it) },
                            valueRange = -15f..15f,
                        )

                        DesktopEqPresets.bandLabels.forEachIndexed { idx, label ->
                            val value = pb.eqBands.getOrNull(idx) ?: 0f
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(46.dp))
                                Slider(
                                    value = value,
                                    onValueChange = { newDb ->
                                        val updated = pb.eqBands.toMutableList().apply { this[idx] = newDb }
                                        appState.audioPlayer.setEqualizer(enabled = true, preset = "Custom", bands = updated)
                                    },
                                    valueRange = -15f..15f,
                                    modifier = Modifier.weight(1f),
                                )
                                Text("${if (value >= 0) "+" else ""}${value.roundToInt()}dB", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(42.dp), textAlign = TextAlign.End)
                            }
                        }
                    }

                    1 -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val chooser = JFileChooser().apply {
                                    fileFilter = FileNameExtensionFilter("Equalizer APO ParametricEQ (*.txt)", "txt")
                                }
                                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                    val file = chooser.selectedFile
                                    runCatching {
                                        val parsed = DesktopParametricEq.parseApoText(file.readText(Charsets.UTF_8))
                                        val (preamp, projected) = DesktopParametricEq.projectToVlc10Bands(parsed)
                                        val name = file.nameWithoutExtension
                                        val profile = SavedEqProfile(
                                            id = UUID.randomUUID().toString(),
                                            name = name,
                                            source = "APO",
                                            preamp = preamp.toDouble(),
                                            bands = parsed.bands,
                                            projectedVlcBands = projected,
                                        )
                                        savedProfiles = DesktopEqProfileStore.save(profile)
                                        appState.audioPlayer.setEqualizer(enabled = true, preset = name, preamp = preamp, bands = projected)
                                        appState.showStatus("Perfil ParametricEQ aplicado: $name (${parsed.bands.size} filtros)")
                                    }.onFailure { appState.showStatus("Error al leer APO .txt: ${it.message}") }
                                }
                            }) { Text("Importar ParametricEQ (.txt)") }

                            OutlinedButton(onClick = {
                                val profile = SavedEqProfile(
                                    id = UUID.randomUUID().toString(),
                                    name = "Perfil ${savedProfiles.size + 1}",
                                    source = "Manual",
                                    preamp = pb.eqPreamp.toDouble(),
                                    projectedVlcBands = pb.eqBands,
                                )
                                savedProfiles = DesktopEqProfileStore.save(profile)
                                appState.showStatus("Perfil guardado: ${profile.name}")
                            }) { Text("Guardar curva actual") }
                        }

                        if (savedProfiles.isEmpty()) {
                            Text(
                                "No hay perfiles ParametricEQ o AutoEQ guardados aún. Importa un archivo APO .txt o busca tus audífonos en el Asistente AutoEQ.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            savedProfiles.forEach { prof ->
                                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(prof.name, fontWeight = FontWeight.SemiBold)
                                            Text("${prof.source} • Preamp ${"%.1f".format(prof.preamp)} dB • ${prof.bands.size} filtros", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Button(onClick = {
                                                appState.audioPlayer.setEqualizer(enabled = true, preset = prof.name, preamp = prof.preamp.toFloat(), bands = prof.projectedVlcBands)
                                            }) { Text("Aplicar") }
                                            IconButton(onClick = { savedProfiles = DesktopEqProfileStore.delete(prof.id) }) {
                                                Icon(Icons.Rounded.Delete, contentDescription = "Eliminar perfil ${prof.name}")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        Text(
                            "Busca tu modelo de audífonos en la base de datos oficial AutoEQ de GitHub para ecualizarlos al objetivo Harman.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = autoEqQuery,
                            onValueChange = {
                                autoEqQuery = it
                                if (autoEqIndexed) autoEqResults = appState.autoEqSearch.search(it)
                            },
                            label = { Text("Buscar audífonos (ej. Sony WH-1000XM4, AirPods, HD 600)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (autoEqLoading) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text("Descargando índice AutoEQ desde GitHub...")
                            }
                        } else {
                            autoEqResults.take(25).forEach { entry ->
                                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(entry.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${entry.source} • ${entry.form} • ${entry.rig}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Button(onClick = {
                                            scope.launch {
                                                appState.autoEqSearch.loadProfile(entry)
                                                    .onSuccess { parsed ->
                                                        val (preamp, projected) = DesktopParametricEq.projectToVlc10Bands(parsed)
                                                        val saved = SavedEqProfile(
                                                            id = "autoeq_${entry.label}_${entry.source}",
                                                            name = entry.label,
                                                            source = "AutoEQ (${entry.source})",
                                                            preamp = preamp.toDouble(),
                                                            bands = parsed.bands,
                                                            projectedVlcBands = projected,
                                                        )
                                                        savedProfiles = DesktopEqProfileStore.save(saved)
                                                        appState.audioPlayer.applyParametricProfile(parsed, entry.label)
                                                        appState.showStatus("Perfil AutoEQ aplicado: ${entry.label}")
                                                    }
                                                    .onFailure { appState.showStatus("Error al cargar AutoEQ: ${it.message}") }
                                            }
                                        }) { Text("Aplicar") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    appState.audioPlayer.setPlaybackSpeed(1.0f)
                    appState.audioPlayer.setPlaybackPitch(1.0f)
                    appState.audioPlayer.setEqualizer(enabled = false, preset = "Flat", preamp = 0f, bands = List(10) { 0f })
                }) { Text("Restablecer") }
                Button(onClick = appState::closeEqualizerDialog) { Text("Listo") }
            }
        },
    )
}

@Composable
private fun EqFrequencyResponseCanvas(preamp: Float, bands: List<Float>, enabled: Boolean) {
    val curve = remember(preamp, bands) { DesktopParametricEq.evaluateGraphicCurve(preamp, bands, points = 64) }
    val lineColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth().height(110.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .semantics { contentDescription = "Gráfica de respuesta en frecuencia del ecualizador" },
        ) {
            val w = size.width
            val h = size.height
            val midY = h / 2f
            drawLine(gridColor, Offset(0f, midY), Offset(w, midY), strokeWidth = 1.5f)
            drawLine(gridColor.copy(alpha = 0.3f), Offset(0f, h * 0.25f), Offset(w, h * 0.25f), strokeWidth = 1f)
            drawLine(gridColor.copy(alpha = 0.3f), Offset(0f, h * 0.75f), Offset(w, h * 0.75f), strokeWidth = 1f)

            if (curve.size > 1) {
                val path = Path()
                curve.forEachIndexed { idx, (_, db) ->
                    val x = (idx.toFloat() / (curve.size - 1)) * w
                    val y = (midY - (db.toFloat() / 18f) * (h * 0.45f)).coerceIn(2f, h - 2f)
                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = lineColor, style = Stroke(width = 3f, cap = StrokeCap.Round))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SleepTimerDialog(appState: DesktopAppState) {
    val open by appState.showSleepTimerDialog.collectAsState()
    if (!open) return

    val pb by appState.playbackState.collectAsState()
    val settings by appState.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var customMinutes by remember { mutableStateOf(settings.sleepTimerDefaultMinutes.toString()) }
    var stopAfterSong by remember { mutableStateOf(settings.sleepTimerStopAfterCurrentSong) }

    AlertDialog(
        onDismissRequest = appState::closeSleepTimerDialog,
        title = { Text("Temporizador de apagado", modifier = Modifier.semantics { heading() }) },
        text = {
            Column(modifier = Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val status = when {
                    pb.sleepTimerRemainingMs != null -> "Activo: quedan ${formatTimeMs(pb.sleepTimerRemainingMs!!)}"
                    pb.sleepTimerEndOfTrack -> "Activo: se pausará al terminar esta canción"
                    else -> "Desactivado"
                }
                Text(
                    status,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 45, 60, 90).forEach { min ->
                        OutlinedButton(onClick = {
                            appState.audioPlayer.setSleepTimer(min, stopAfterCurrentSong = stopAfterSong)
                            appState.showStatus("Temporizador configurado a $min min")
                            appState.closeSleepTimerDialog()
                        }) { Text("$min min") }
                    }
                    OutlinedButton(onClick = {
                        appState.audioPlayer.setSleepTimer(null, endOfTrack = true)
                        appState.showStatus("Se pausará al finalizar la canción actual")
                        appState.closeSleepTimerDialog()
                    }) { Text("Fin de canción") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = customMinutes,
                        onValueChange = { customMinutes = it.filter { c -> c.isDigit() } },
                        label = { Text("Minutos personalizados") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        enabled = (customMinutes.toIntOrNull() ?: 0) > 0,
                        onClick = {
                            val m = customMinutes.toInt().coerceIn(1, 360)
                            appState.audioPlayer.setSleepTimer(m, stopAfterCurrentSong = stopAfterSong)
                            scope.launch { appState.preferences.set(DesktopPrefKeys.SleepTimerDefault, m) }
                            appState.showStatus("Temporizador configurado a $m min")
                            appState.closeSleepTimerDialog()
                        },
                    ) { Text("Iniciar") }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Terminar canción actual al expirar", style = MaterialTheme.typography.bodySmall)
                    Switch(
                        checked = stopAfterSong,
                        onCheckedChange = { v ->
                            stopAfterSong = v
                            scope.launch { appState.preferences.set(DesktopPrefKeys.SleepTimerStopAfterCurrentSong, v) }
                        },
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Desvanecimiento gradual de volumen (60s)", style = MaterialTheme.typography.bodySmall)
                    Switch(
                        checked = settings.sleepTimerFadeOut,
                        onCheckedChange = { v -> scope.launch { appState.preferences.set(DesktopPrefKeys.SleepTimerFadeOut, v) } },
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pb.sleepTimerEndOfTrack || pb.sleepTimerRemainingMs != null) {
                    OutlinedButton(onClick = {
                        appState.audioPlayer.setSleepTimer(null, false)
                        appState.showStatus("Temporizador cancelado")
                        appState.closeSleepTimerDialog()
                    }) { Text("Cancelar temporizador") }
                }
                Button(onClick = appState::closeSleepTimerDialog) { Text("Cerrar") }
            }
        },
    )
}

@Composable
fun ListenTogetherDialog(appState: DesktopAppState) {
    val open by appState.showListenTogetherDialog.collectAsState()
    if (!open) return

    val settings by appState.settings.collectAsState()
    val lt by appState.listenTogetherManager.state.collectAsState()
    val scope = rememberCoroutineScope()

    var username by remember(settings.listenTogetherUsername) {
        mutableStateOf(settings.listenTogetherUsername.ifBlank { settings.accountName.ifBlank { "Usuario Windows" } })
    }
    var roomCodeInput by remember { mutableStateOf("") }
    var serverUrl by remember(settings.listenTogetherServerUrl) { mutableStateOf(settings.listenTogetherServerUrl) }

    AlertDialog(
        onDismissRequest = appState::closeListenTogetherDialog,
        title = { Text("Escuchar Juntos (Listen Together)", modifier = Modifier.semantics { heading() }) },
        text = {
            Column(
                modifier = Modifier.width(500.dp).heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Estado: ${lt.statusText}",
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                            if (lt.roomCode.isNotBlank()) {
                                Text(
                                    "Código: ${lt.roomCode} • ${if (lt.isHost) "Host" else "Invitado"} • Reloj: ${lt.clockOffsetMs}ms",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            lt.lastError?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (lt.roomCode.isNotBlank()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick = {
                                    copyTextToClipboard(lt.roomCode)
                                    appState.showStatus("Código ${lt.roomCode} copiado")
                                }) { Text("Copiar") }
                                if (!lt.isHost) {
                                    OutlinedButton(onClick = { appState.listenTogetherManager.requestResync() }) { Text("Resync") }
                                }
                            }
                        }
                    }
                }

                if (lt.roomCode.isBlank()) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Tu nombre en la sala") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("Servidor WebSocket") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        enabled = !lt.connecting,
                        onClick = {
                            scope.launch {
                                appState.preferences.set(DesktopPrefKeys.ListenTogetherUsername, username)
                                appState.preferences.set(DesktopPrefKeys.ListenTogetherServerUrl, serverUrl)
                            }
                            appState.listenTogetherManager.createRoom(serverUrl, username)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (lt.connecting) "Conectando..." else "Crear sala nueva") }

                    HorizontalDivider()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = roomCodeInput,
                            onValueChange = { roomCodeInput = it.uppercase() },
                            label = { Text("Código de sala") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            enabled = !lt.connecting && roomCodeInput.isNotBlank(),
                            onClick = {
                                scope.launch {
                                    appState.preferences.set(DesktopPrefKeys.ListenTogetherUsername, username)
                                    appState.preferences.set(DesktopPrefKeys.ListenTogetherServerUrl, serverUrl)
                                }
                                appState.listenTogetherManager.joinRoom(serverUrl, roomCodeInput, username)
                            },
                        ) { Text("Unirse") }
                    }
                } else {
                    if (lt.pendingJoinRequests.isNotEmpty() && lt.isHost) {
                        Text("Solicitudes pendientes", fontWeight = FontWeight.Bold)
                        lt.pendingJoinRequests.forEach { req ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(req.username, fontWeight = FontWeight.Medium)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(onClick = { appState.listenTogetherManager.approveJoin(req.userId) }) { Text("Aprobar") }
                                    OutlinedButton(onClick = { appState.listenTogetherManager.rejectJoin(req.userId) }) { Text("Rechazar") }
                                }
                            }
                        }
                    }

                    if (lt.pendingSuggestions.isNotEmpty() && lt.isHost) {
                        HorizontalDivider()
                        Text("Sugerencias de canciones (${lt.pendingSuggestions.size})", fontWeight = FontWeight.Bold)
                        lt.pendingSuggestions.forEach { sug ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(sug.trackInfo.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("Por ${sug.fromUsername} • ${sug.trackInfo.artist}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(onClick = { appState.listenTogetherManager.approveSuggestion(sug) }) { Text("Añadir") }
                                    OutlinedButton(onClick = { appState.listenTogetherManager.rejectSuggestion(sug.suggestionId) }) { Text("No") }
                                }
                            }
                        }
                    }

                    HorizontalDivider()
                    Text("Participantes (${lt.users.size})", fontWeight = FontWeight.Bold)
                    lt.users.forEach { u ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("• ${u.username}${if (u.isHost) " (Host)" else ""}", fontWeight = if (u.isHost) FontWeight.SemiBold else FontWeight.Normal)
                            if (lt.isHost && u.userId != lt.userId && !u.isHost) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { appState.listenTogetherManager.transferHost(u.userId) }) { Text("Hacer host") }
                                    TextButton(onClick = { appState.listenTogetherManager.kickUser(u.userId, u.username, permanentBlock = false) }) { Text("Expulsar") }
                                    TextButton(onClick = { appState.listenTogetherManager.kickUser(u.userId, u.username, permanentBlock = true) }) { Text("Bloquear") }
                                }
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { appState.listenTogetherManager.leaveRoom() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Salir de la sala") }
                }
            }
        },
        confirmButton = { Button(onClick = appState::closeListenTogetherDialog) { Text("Cerrar") } },
    )
}
