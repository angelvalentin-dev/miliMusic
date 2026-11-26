package com.metrolist.desktop.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.recognition.RecognitionState
import com.metrolist.desktop.state.DesktopAppState
import com.metrolist.desktop.state.NavDestination
import com.metrolist.desktop.ui.components.ArtworkImage
import com.metrolist.desktop.ui.components.openExternalUrl
import com.metrolist.desktop.ui.icons.MusicNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun RecognitionScreen(appState: DesktopAppState) {
    val state by appState.musicRecognizer.state.collectAsState()
    val history by appState.recognitionHistory.collectAsState()
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Text(
                "Reconocer música (Shazam)",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Usa el micrófono de tu PC para identificar la canción que suena a tu alrededor.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    val listening = state as? RecognitionState.Listening
                    val pulseSize by animateDpAsState(
                        targetValue = if (listening != null) (112 + (listening.amplitude * 44)).dp else 112.dp,
                    )

                    Box(
                        modifier = Modifier
                            .size(160.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(pulseSize)
                                .clip(CircleShape)
                                .background(
                                    if (listening != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                                    else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                ),
                        )
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            shadowElevation = 6.dp,
                            modifier = Modifier
                                .size(92.dp)
                                .clip(CircleShape)
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = if (listening != null) "Cancelar escucha" else "Empezar a reconocer música",
                                ) {
                                    when (state) {
                                        is RecognitionState.Listening, RecognitionState.Processing -> appState.musicRecognizer.cancel()
                                        else -> appState.musicRecognizer.startListening(10)
                                    }
                                },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (state is RecognitionState.Processing) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 3.dp,
                                        modifier = Modifier.size(34.dp),
                                    )
                                } else {
                                    Icon(
                                        Icons.Rounded.MusicNote,
                                        contentDescription = "Botón reconocer canción",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(40.dp),
                                    )
                                }
                            }
                        }
                    }

                    when (val st = state) {
                        RecognitionState.Idle -> {
                            Text("Pulsa el botón para escuchar (10 segundos)", style = MaterialTheme.typography.titleMedium)
                            Button(onClick = { appState.musicRecognizer.startListening(10) }) {
                                Text("Escuchar ahora")
                            }
                        }
                        is RecognitionState.Listening -> {
                            Text(
                                "Escuchando... ${"%.1f".format(st.elapsedSec)}s / 10.0s",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            OutlinedButton(onClick = { appState.musicRecognizer.cancel() }) {
                                Text("Cancelar")
                            }
                        }
                        RecognitionState.Processing -> {
                            Text("Generando huella acústica y consultando Shazam...", style = MaterialTheme.typography.titleMedium)
                        }
                        is RecognitionState.NoMatch -> {
                            Text(st.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = { appState.musicRecognizer.startListening(10) }) {
                                Text("Intentar de nuevo")
                            }
                        }
                        is RecognitionState.Error -> {
                            Text(st.message, color = MaterialTheme.colorScheme.error)
                            Button(onClick = { appState.musicRecognizer.startListening(10) }) {
                                Text("Reintentar")
                            }
                        }
                        is RecognitionState.Success -> {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    ArtworkImage(st.item.coverArtHqUrl ?: st.item.coverArtUrl, st.item.title, modifier = Modifier.size(72.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(st.item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Text(st.item.artist, style = MaterialTheme.typography.bodyMedium)
                                        st.item.album?.let {
                                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (st.matchedSong != null) {
                                            Button(onClick = { appState.audioPlayer.playSong(st.matchedSong) }) {
                                                Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Reproducir")
                                            }
                                        }
                                        OutlinedButton(onClick = {
                                            appState.navigate(NavDestination.Search("${st.item.title} ${st.item.artist}"))
                                        }) {
                                            Icon(Icons.Rounded.Search, null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Buscar")
                                        }
                                        st.item.shazamUrl?.let { url ->
                                            TextButton(onClick = { openExternalUrl(url) }) { Text("Shazam") }
                                        }
                                    }
                                }
                            }
                            OutlinedButton(onClick = { appState.musicRecognizer.startListening(10) }) {
                                Text("Escuchar otra canción")
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
                    "Historial de reconocimiento (${history.size})",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                if (history.isNotEmpty()) {
                    TextButton(onClick = {
                        scope.launch(Dispatchers.IO) { appState.database.clearRecognitionHistory() }
                        appState.showStatus("Historial de reconocimiento limpiado")
                    }) { Text("Limpiar todo") }
                }
            }
        }

        if (history.isEmpty()) {
            item {
                Text(
                    "Aún no has reconocido canciones. Las coincidencias se guardan automáticamente aquí.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(history, key = { it.id }) { item ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ArtworkImage(item.coverArtUrl, item.title, modifier = Modifier.size(48.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(item.artist, item.album).joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = {
                            appState.navigate(NavDestination.Search("${item.title} ${item.artist}"))
                        }) {
                            Icon(Icons.Rounded.Search, contentDescription = "Buscar ${item.title}")
                        }
                        IconButton(onClick = {
                            scope.launch(Dispatchers.IO) { appState.database.deleteRecognitionHistory(item.id) }
                        }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Eliminar ${item.title} del historial")
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
            }
        }
    }
}
