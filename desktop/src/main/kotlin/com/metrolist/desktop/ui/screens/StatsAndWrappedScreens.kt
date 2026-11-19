package com.metrolist.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Brush
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
import com.metrolist.desktop.audio.DesktopEqPresets
import com.metrolist.desktop.data.DesktopPrefKeys
import com.metrolist.desktop.state.DesktopAppState
import com.metrolist.desktop.state.NavDestination
import com.metrolist.desktop.ui.components.ArtworkImage
import com.metrolist.desktop.ui.components.SongRowItem
import com.metrolist.desktop.ui.components.formatTimeMs
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlin.math.roundToInt

enum class DesktopStatPeriod(val label: String, val durationDays: Long?) {
    WEEK_1("1 Semana", 7),
    MONTH_1("1 Mes", 30),
    MONTH_6("6 Meses", 180),
    YEAR_1("1 Año", 365),
    ALL("Todo el tiempo", null),
    ;

    fun sinceEpochMs(nowMs: Long = System.currentTimeMillis()): Long =
        durationDays?.let { nowMs - it * 86_400_000L } ?: 0L
}

@Composable
fun StatsScreen(appState: DesktopAppState) {
    var selectedPeriod by remember { mutableStateOf(DesktopStatPeriod.ALL) }
    val pb by appState.playbackState.collectAsState()
    val historyTrigger by appState.recentlyPlayed.collectAsState()

    val sinceMs = remember(selectedPeriod, historyTrigger) { selectedPeriod.sinceEpochMs() }
    val summary = remember(sinceMs, historyTrigger) { appState.database.listeningSummary(sinceMs) }
    val topSongs = remember(sinceMs, historyTrigger) { appState.database.mostPlayedSongs(sinceMs = sinceMs, limit = 50) }
    val topArtists = remember(sinceMs, historyTrigger) { appState.database.mostPlayedArtists(sinceMs = sinceMs, limit = 25) }
    val topAlbums = remember(sinceMs, historyTrigger) { appState.database.mostPlayedAlbums(sinceMs = sinceMs, limit = 25) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Estadísticas y Top",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = "Tu actividad musical y artistas más escuchados",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = appState::openWrappedDialog) {
                    Icon(Icons.Rounded.Star, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Metrolist Wrapped")
                }
            }
        }

        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DesktopStatPeriod.entries.forEach { period ->
                    FilterChip(
                        selected = selectedPeriod == period,
                        onClick = { selectedPeriod = period },
                        label = { Text(period.label) },
                    )
                }
            }
        }

        item {
            val totalMinutes = summary.totalPlayTimeMs / 60_000L
            val hours = totalMinutes / 60L
            val mins = totalMinutes % 60L
            val timeFormatted = if (hours > 0) "${hours}h ${mins}m" else "${totalMinutes} min"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatMetricCard("Tiempo escuchado", timeFormatted, "${totalMinutes} minutos en total", Modifier.weight(1f))
                StatMetricCard("Reproducciones", summary.totalPlays.toString(), "Pistas reproducidas", Modifier.weight(1f))
                StatMetricCard("Canciones únicas", summary.uniqueSongs.toString(), "En este periodo", Modifier.weight(1f))
                StatMetricCard("Artistas únicos", summary.uniqueArtists.toString(), "${summary.uniqueAlbums} álbumes", Modifier.weight(1f))
            }
        }

        if (topArtists.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Artistas más escuchados",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        itemsIndexed(topArtists) { idx, stat ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier
                                    .width(155.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .semantics(mergeDescendants = true) {
                                        contentDescription = "Artista número ${idx + 1}: ${stat.artist.name}, ${stat.playCount} reproducciones, ${stat.playTimeMs / 60_000L} minutos"
                                    }
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = "Abrir artista ${stat.artist.name}",
                                    ) { appState.navigate(NavDestination.Artist(stat.artist.id)) },
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    ArtworkImage(
                                        url = stat.artist.thumbnailUrl,
                                        contentDescription = null,
                                        isCircle = true,
                                        modifier = Modifier.size(110.dp),
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "#${idx + 1} ${stat.artist.name}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${stat.playCount} plays • ${stat.playTimeMs / 60_000L} min",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (topAlbums.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Álbumes más escuchados",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        itemsIndexed(topAlbums) { idx, stat ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier
                                    .width(165.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .semantics(mergeDescendants = true) {
                                        contentDescription = "Álbum número ${idx + 1}: ${stat.album.title}, ${stat.playCount} reproducciones, ${stat.playTimeMs / 60_000L} minutos"
                                    }
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = "Abrir álbum ${stat.album.title}",
                                    ) { appState.navigate(NavDestination.Album(stat.album.id)) },
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    ArtworkImage(
                                        url = stat.album.thumbnailUrl,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxWidth().height(135.dp),
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "#${idx + 1} ${stat.album.title}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${stat.playCount} plays • ${stat.playTimeMs / 60_000L} min",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Canciones más escuchadas",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                if (topSongs.isNotEmpty()) {
                    Button(onClick = { appState.audioPlayer.playQueue(topSongs.map { it.song }, 0) }) {
                        Icon(Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Reproducir Top (${topSongs.size})")
                    }
                }
            }
        }

        if (topSongs.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Reproduce música para comenzar a generar tus estadísticas.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            itemsIndexed(topSongs) { idx, stat ->
                val songsList = remember(topSongs) { topSongs.map { it.song } }
                Column {
                    SongRowItem(
                        song = stat.song,
                        appState = appState,
                        index = idx,
                        isCurrent = pb.currentSong?.id == stat.song.id,
                        onPlay = { appState.audioPlayer.playQueue(songsList, idx) },
                    )
                    Text(
                        text = "   #${idx + 1} • ${stat.playCount} reproducciones • ${stat.playTimeMs / 60_000L} min escuchados",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 44.dp, bottom = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatMetricCard(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "$title: $value, $subtitle"
        },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun WrappedDialog(appState: DesktopAppState) {
    val open by appState.showWrappedDialog.collectAsState()
    if (!open) return

    val summary = remember { appState.database.listeningSummary(0L) }
    val topSongs = remember { appState.database.mostPlayedSongs(0L, limit = 25) }
    val topArtists = remember { appState.database.mostPlayedArtists(0L, limit = 10) }
    val topAlbums = remember { appState.database.mostPlayedAlbums(0L, limit = 5) }

    var slideIndex by remember { mutableStateOf(0) }
    val totalSlides = 5
    val totalMinutes = summary.totalPlayTimeMs / 60_000L

    val wrappedMessage = remember(totalMinutes) {
        when {
            totalMinutes < 1000L -> "Calentando motores: dedicaste $totalMinutes minutos a tu música favorita."
            totalMinutes < 5000L -> "Un gran equilibrio musical: $totalMinutes minutos acompañando tus días."
            totalMinutes < 15000L -> "La música es tu banda sonora: $totalMinutes minutos llenos de ritmo."
            else -> "¡Nivel legendario! Viviste con música durante $totalMinutes minutos."
        }
    }

    AlertDialog(
        onDismissRequest = appState::closeWrappedDialog,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Metrolist Wrapped ✨ (${slideIndex + 1}/$totalSlides)",
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.semantics { heading() },
                )
                IconButton(onClick = appState::closeWrappedDialog) {
                    Icon(Icons.Rounded.Close, contentDescription = "Cerrar Metrolist Wrapped")
                }
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .width(560.dp)
                    .height(380.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        ),
                    )
                    .padding(24.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                when (slideIndex) {
                    0 -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "$totalMinutes minutos",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = wrappedMessage,
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(18.dp))
                            Text(
                                text = "${summary.totalPlays} reproducciones • ${summary.uniqueSongs} canciones • ${summary.uniqueArtists} artistas",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    1 -> {
                        val topSong = topSongs.firstOrNull()
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("Tu Canción #1", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(12.dp))
                            if (topSong == null) {
                                Text("Aún no hay suficientes reproducciones registradas.")
                            } else {
                                ArtworkImage(topSong.song.thumbnailUrl, topSong.song.title, modifier = Modifier.size(160.dp))
                                Spacer(Modifier.height(14.dp))
                                Text(topSong.song.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
                                Text(topSong.song.artistDisplay, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                Text("${topSong.playCount} reproducciones • ${topSong.playTimeMs / 60_000L} min", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    2 -> {
                        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Tu Top 5 Canciones", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            if (topSongs.isEmpty()) {
                                Text("Sin datos aún.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                topSongs.take(5).forEachIndexed { idx, stat ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("#${idx + 1}", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(34.dp))
                                        ArtworkImage(stat.song.thumbnailUrl, null, modifier = Modifier.size(44.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(stat.song.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(stat.song.artistDisplay, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                        }
                                        Text("${stat.playCount}x", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                    3 -> {
                        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Tus Artistas Favoritos", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            if (topArtists.isEmpty()) {
                                Text("Sin datos aún.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                topArtists.take(5).forEachIndexed { idx, stat ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("#${idx + 1}", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(34.dp))
                                        ArtworkImage(stat.artist.thumbnailUrl, null, isCircle = true, modifier = Modifier.size(44.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(stat.artist.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                            Text("${stat.playCount} reproducciones • ${stat.playTimeMs / 60_000L} min", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        val topAlbum = topAlbums.firstOrNull()
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (topAlbum != null) {
                                Text("Tu Álbum #1", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(8.dp))
                                ArtworkImage(topAlbum.album.thumbnailUrl, topAlbum.album.title, modifier = Modifier.size(120.dp))
                                Spacer(Modifier.height(10.dp))
                                Text(topAlbum.album.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(16.dp))
                            }
                            if (topSongs.isNotEmpty()) {
                                Button(
                                    onClick = {
                                        val pl = appState.database.createPlaylist("Metrolist Wrapped")
                                        topSongs.forEach { appState.database.addSongToPlaylist(pl.id, it.song) }
                                        appState.audioPlayer.playQueue(topSongs.map { it.song }, 0)
                                        appState.showStatus("Playlist 'Metrolist Wrapped' guardada y reproduciendo")
                                        appState.closeWrappedDialog()
                                    },
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Guardar y Reproducir mi Playlist Wrapped (${topSongs.size})")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (slideIndex > 0) {
                    OutlinedButton(onClick = { slideIndex -= 1 }) { Text("Diapositiva anterior") }
                }
                if (slideIndex < totalSlides - 1) {
                    Button(onClick = { slideIndex += 1 }) { Text("Siguiente diapositiva") }
                } else {
                    Button(onClick = appState::closeWrappedDialog) { Text("Finalizar") }
                }
            }
        },
    )
}

@Composable
fun EqualizerAndSpeedDialog(appState: DesktopAppState) {
    val open by appState.showEqualizerDialog.collectAsState()
    if (!open) return

    val pb by appState.playbackState.collectAsState()
    val speedOptions = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    AlertDialog(
        onDismissRequest = appState::closeEqualizerDialog,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Ecualizador y Velocidad de reproducción",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .toggleable(
                            value = pb.eqEnabled,
                            role = Role.Switch,
                            onValueChange = { appState.audioPlayer.setEqualizer(enabled = it) },
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Text("EQ Activo", style = MaterialTheme.typography.bodySmall)
                    Switch(
                        checked = pb.eqEnabled,
                        onCheckedChange = null,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.width(640.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Playback speed
                Text(
                    text = "Velocidad de reproducción: ${"%.2f".format(pb.playbackSpeed)}x",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    speedOptions.forEach { spd ->
                        FilterChip(
                            selected = kotlin.math.abs(pb.playbackSpeed - spd) < 0.03f,
                            onClick = { appState.audioPlayer.setPlaybackSpeed(spd) },
                            label = { Text("${spd}x") },
                        )
                    }
                }

                HorizontalDivider()

                // EQ Presets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Presets de Ecualizador", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    TextButton(
                        onClick = {
                            appState.audioPlayer.setEqualizer(
                                enabled = pb.eqEnabled,
                                preset = "Flat",
                                preamp = 0f,
                                bands = List(10) { 0f },
                            )
                        },
                    ) {
                        Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Restablecer")
                    }
                }

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DesktopEqPresets.presets.keys.forEach { presetName ->
                        FilterChip(
                            selected = pb.eqPreset == presetName,
                            onClick = { appState.audioPlayer.applyEqualizerPreset(presetName) },
                            label = { Text(presetName) },
                        )
                    }
                }

                // Preamp
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Preamp: ${pb.eqPreamp.roundToInt()} dB", modifier = Modifier.width(110.dp), style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = pb.eqPreamp,
                        onValueChange = {
                            appState.audioPlayer.setEqualizer(
                                enabled = true,
                                preset = pb.eqPreset,
                                preamp = it,
                                bands = pb.eqBands,
                            )
                        },
                        valueRange = -15f..15f,
                        modifier = Modifier
                            .weight(1f)
                            .semantics {
                                contentDescription = "Preamplificador del ecualizador"
                                stateDescription = "${pb.eqPreamp.roundToInt()} decibelios"
                            },
                    )
                }

                // 10 Bands
                LazyColumn(
                    modifier = Modifier.height(230.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(DesktopEqPresets.bandLabels) { idx, freqLabel ->
                        val gain = pb.eqBands.getOrElse(idx) { 0f }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(freqLabel, modifier = Modifier.width(56.dp), style = MaterialTheme.typography.labelMedium)
                            Slider(
                                value = gain,
                                onValueChange = { newDb ->
                                    val updated = pb.eqBands.toMutableList().apply {
                                        if (idx in indices) this[idx] = newDb
                                    }
                                    appState.audioPlayer.setEqualizer(
                                        enabled = true,
                                        preset = "Custom",
                                        preamp = pb.eqPreamp,
                                        bands = updated,
                                    )
                                },
                                valueRange = -15f..15f,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(24.dp)
                                    .semantics {
                                        contentDescription = "Banda $freqLabel del ecualizador"
                                        stateDescription = "${if (gain >= 0f) "+" else ""}${gain.roundToInt()} decibelios"
                                    },
                            )
                            Text(
                                text = "${if (gain >= 0f) "+" else ""}${gain.roundToInt()} dB",
                                modifier = Modifier.width(56.dp),
                                textAlign = TextAlign.End,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = appState::closeEqualizerDialog) { Text("Listo") }
        },
    )
}

@Composable
fun SleepTimerDialog(appState: DesktopAppState) {
    val open by appState.showSleepTimerDialog.collectAsState()
    if (!open) return

    val pb by appState.playbackState.collectAsState()
    val presets = listOf(15, 30, 45, 60, 90)

    AlertDialog(
        onDismissRequest = appState::closeSleepTimerDialog,
        title = {
            Text(
                text = "Temporizador de apagado (Sleep Timer)",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.width(380.dp)) {
                val activeStatus = when {
                    pb.sleepTimerEndOfTrack -> "Activo: Se pausará al terminar la canción actual"
                    pb.sleepTimerRemainingMs != null -> "Activo: Se pausará en ${formatTimeMs(pb.sleepTimerRemainingMs!!)}"
                    else -> "Temporizador desactivado"
                }
                Text(
                    text = activeStatus,
                    color = if (pb.sleepTimerEndOfTrack || pb.sleepTimerRemainingMs != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    presets.forEach { mins ->
                        OutlinedButton(
                            onClick = {
                                appState.audioPlayer.setSleepTimer(mins, endOfTrack = false)
                                appState.showStatus("Temporizador activado: $mins minutos")
                                appState.closeSleepTimerDialog()
                            },
                        ) {
                            Text("$mins min")
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        appState.audioPlayer.setSleepTimer(null, endOfTrack = true)
                        appState.showStatus("La reproducción se detendrá al terminar esta canción")
                        appState.closeSleepTimerDialog()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Al terminar la canción actual")
                }

                if (pb.sleepTimerEndOfTrack || pb.sleepTimerRemainingMs != null) {
                    Button(
                        onClick = {
                            appState.audioPlayer.setSleepTimer(null, endOfTrack = false)
                            appState.showStatus("Temporizador cancelado")
                            appState.closeSleepTimerDialog()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Desactivar temporizador")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = appState::closeSleepTimerDialog) { Text("Cerrar") }
        },
    )
}

@Composable
fun ListenTogetherDialog(appState: DesktopAppState) {
    val open by appState.showListenTogetherDialog.collectAsState()
    if (!open) return

    val settings by appState.settings.collectAsState()
    val ltState by appState.listenTogetherManager.state.collectAsState()
    val scope = rememberCoroutineScope()

    var username by remember(settings.listenTogetherUsername, settings.accountName) {
        mutableStateOf(settings.listenTogetherUsername.ifBlank { settings.accountName.ifBlank { "Usuario Windows" } })
    }
    var serverUrl by remember(settings.listenTogetherServerUrl) {
        mutableStateOf(settings.listenTogetherServerUrl)
    }
    var roomCodeInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = appState::closeListenTogetherDialog,
        title = {
            Text(
                text = "Escuchar Juntos (Listen Together)",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(
                modifier = Modifier.width(460.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Estado: ${ltState.statusText}",
                    color = if (ltState.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                ltState.lastError?.let { err ->
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }

                if (ltState.roomCode.isBlank()) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Tu nombre de usuario") },
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

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            enabled = !ltState.connecting,
                            onClick = {
                                scope.launch {
                                    appState.preferences.set(DesktopPrefKeys.ListenTogetherUsername, username.trim())
                                    appState.preferences.set(DesktopPrefKeys.ListenTogetherServerUrl, serverUrl.trim())
                                }
                                appState.listenTogetherManager.createRoom(serverUrl, username)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Crear sala nueva")
                        }
                    }

                    HorizontalDivider()

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = roomCodeInput,
                            onValueChange = { roomCodeInput = it.uppercase() },
                            label = { Text("Código de sala") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            enabled = roomCodeInput.isNotBlank() && !ltState.connecting,
                            onClick = {
                                scope.launch {
                                    appState.preferences.set(DesktopPrefKeys.ListenTogetherUsername, username.trim())
                                    appState.preferences.set(DesktopPrefKeys.ListenTogetherServerUrl, serverUrl.trim())
                                }
                                appState.listenTogetherManager.joinRoom(serverUrl, roomCodeInput, username)
                            },
                        ) {
                            Text("Unirse")
                        }
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text("Código de sala", style = MaterialTheme.typography.labelSmall)
                                Text(ltState.roomCode, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                                Text(if (ltState.isHost) "Rol: Anfitrión (Host)" else "Rol: Invitado", style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(ltState.roomCode), null)
                                        appState.showStatus("Código ${ltState.roomCode} copiado al portapapeles")
                                    }
                                },
                            ) {
                                Text("Copiar código")
                            }
                        }
                    }

                    if (ltState.pendingJoinRequests.isNotEmpty() && ltState.isHost) {
                        Text("Solicitudes para unirse", fontWeight = FontWeight.Bold)
                        ltState.pendingJoinRequests.forEach { req ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(req.username, fontWeight = FontWeight.Medium)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    IconButton(onClick = { appState.listenTogetherManager.approveJoin(req.userId) }) {
                                        Icon(
                                            Icons.Rounded.Check,
                                            contentDescription = "Aprobar solicitud de ${req.username}",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    IconButton(onClick = { appState.listenTogetherManager.rejectJoin(req.userId) }) {
                                        Icon(
                                            Icons.Rounded.Close,
                                            contentDescription = "Rechazar solicitud de ${req.username}",
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Text("Usuarios conectados (${ltState.users.size})", fontWeight = FontWeight.Bold)
                    ltState.users.forEach { u ->
                        Text("• ${u.username}${if (u.isHost) " (Host)" else ""}", style = MaterialTheme.typography.bodyMedium)
                    }

                    OutlinedButton(
                        onClick = { appState.listenTogetherManager.leaveRoom() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Salir de la sala")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = appState::closeListenTogetherDialog) { Text("Cerrar") }
        },
    )
}

