package com.metrolist.desktop.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.db.DesktopFormatInfo
import com.metrolist.desktop.db.DesktopSong
import com.metrolist.desktop.lyrics.DesktopLyricsCardRenderer
import com.metrolist.desktop.lyrics.LyricsSearchCandidate
import com.metrolist.desktop.lyrics.ShareCardStyle
import com.metrolist.desktop.state.DesktopAppState
import com.metrolist.desktop.state.LyricsToolMode
import com.metrolist.desktop.util.CsvPreview
import com.metrolist.desktop.util.DesktopReleaseInfo
import com.metrolist.desktop.util.DesktopUpdater
import com.metrolist.desktop.util.PlaylistTransferService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

fun copyTextToClipboard(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}

fun openExternalUrl(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}

@Composable
fun SongDetailsDialog(appState: DesktopAppState) {
    val song by appState.songDetailsTarget.collectAsState()
    val target = song ?: return
    var formatInfo by remember(target.id) { mutableStateOf<DesktopFormatInfo?>(null) }
    var dbSong by remember(target.id) { mutableStateOf(target) }

    LaunchedEffect(target.id) {
        withContext(Dispatchers.IO) {
            formatInfo = appState.database.getFormat(target.id)
            appState.database.getSong(target.id)?.let { dbSong = it }
        }
    }

    val watchUrl = "https://music.youtube.com/watch?v=${target.id}"

    AlertDialog(
        onDismissRequest = appState::closeSongDetails,
        title = { Text("Detalles de pista", modifier = Modifier.semantics { heading() }) },
        text = {
            Column(
                modifier = Modifier.width(460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArtworkImage(target.thumbnailUrl, target.title, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(dbSong.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(dbSong.artistDisplay, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        dbSong.albumName?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider()
                DetailRow("ID de YouTube", target.id)
                DetailRow("Duración", formatTimeSeconds(dbSong.duration))
                DetailRow("Tiempo total escuchado", "${dbSong.totalPlayTime / 60_000L} min")
                DetailRow("Desfase de letras", "${dbSong.lyricsOffset} ms")
                DetailRow("Descargada", if (dbSong.isDownloaded) "Sí" else "No")
                if (formatInfo != null) {
                    HorizontalDivider()
                    DetailRow("MIME / Códec", listOf(formatInfo!!.mimeType, formatInfo!!.codecs).filter { it.isNotBlank() }.joinToString(" • "))
                    if (formatInfo!!.bitrate > 0) DetailRow("Bitrate", "${formatInfo!!.bitrate / 1000} kbps")
                    formatInfo!!.sampleRate?.let { DetailRow("Frecuencia", "$it Hz") }
                    if (formatInfo!!.contentLength > 0L) {
                        DetailRow("Tamaño stream", "${"%.2f".format(formatInfo!!.contentLength / (1024.0 * 1024.0))} MB")
                    }
                    formatInfo!!.loudnessDb?.let { DetailRow("Volumen (Loudness)", "${"%.2f".format(it)} dB") }
                    if (formatInfo!!.itag > 0) DetailRow("Itag", formatInfo!!.itag.toString())
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    copyTextToClipboard(watchUrl)
                    appState.showStatus("Enlace copiado al portapapeles")
                }) { Text("Copiar enlace") }
                Button(onClick = appState::closeSongDetails) { Text("Cerrar") }
            }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SongMetadataEditDialog(appState: DesktopAppState) {
    val target by appState.songEditTarget.collectAsState()
    val song = target ?: return
    val scope = rememberCoroutineScope()
    var title by remember(song.id) { mutableStateOf(song.title) }
    var artistAlias by remember(song.id) { mutableStateOf(song.artistDisplay) }

    AlertDialog(
        onDismissRequest = appState::closeSongEdit,
        title = { Text("Editar metadatos locales", modifier = Modifier.semantics { heading() }) },
        text = {
            Column(modifier = Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Título de la canción") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = artistAlias,
                    onValueChange = { artistAlias = it },
                    label = { Text("Alias del artista principal") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Los cambios se guardan localmente y se incluyen en tus archivos .backup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        appState.database.updateSongMetadata(song.id, title, artistAlias)
                    }
                    appState.showStatus("Metadatos actualizados")
                    appState.closeSongEdit()
                },
                enabled = title.isNotBlank(),
            ) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = appState::closeSongEdit) { Text("Cancelar") }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LyricsToolsDialog(appState: DesktopAppState) {
    val mode by appState.lyricsToolMode.collectAsState()
    val activeMode = mode ?: return
    val pb by appState.playbackState.collectAsState()
    val song = pb.currentSong ?: run {
        appState.closeLyricsTool()
        return
    }
    val lyrics by appState.currentLyrics.collectAsState()
    val scope = rememberCoroutineScope()
    val primaryArgb = MaterialTheme.colorScheme.primary.toArgb()

    when (activeMode) {
        LyricsToolMode.EDIT -> {
            var text by remember(song.id, lyrics?.rawLyrics) { mutableStateOf(lyrics?.rawLyrics.orEmpty()) }
            AlertDialog(
                onDismissRequest = appState::closeLyricsTool,
                title = { Text("Editar o importar letras (.lrc)", modifier = Modifier.semantics { heading() }) },
                text = {
                    Column(modifier = Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val chooser = JFileChooser().apply {
                                    fileFilter = FileNameExtensionFilter("Archivos de letras (*.lrc, *.txt)", "lrc", "txt")
                                }
                                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                    runCatching { text = chooser.selectedFile.readText(Charsets.UTF_8) }
                                }
                            }) { Text("Importar archivo .lrc") }
                        }
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            label = { Text("Contenido LRC o texto plano") },
                            minLines = 10,
                            maxLines = 16,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            appState.saveManualLyrics(text, "Manual")
                            appState.closeLyricsTool()
                        },
                        enabled = text.isNotBlank(),
                    ) { Text("Guardar") }
                },
                dismissButton = { TextButton(onClick = appState::closeLyricsTool) { Text("Cancelar") } },
            )
        }

        LyricsToolMode.SEARCH -> {
            var title by remember(song.id) { mutableStateOf(song.title) }
            var artist by remember(song.id) { mutableStateOf(song.artistDisplay) }
            var searching by remember { mutableStateOf(false) }
            var results by remember { mutableStateOf<List<LyricsSearchCandidate>>(emptyList()) }

            LaunchedEffect(song.id) {
                searching = true
                results = appState.lyricsService.searchAllProviders(title, artist, song.albumName, song.duration, song.id)
                searching = false
            }

            AlertDialog(
                onDismissRequest = appState::closeLyricsTool,
                title = { Text("Buscar letras en todos los proveedores", modifier = Modifier.semantics { heading() }) },
                text = {
                    Column(modifier = Modifier.width(540.dp).heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Título") }, singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedTextField(value = artist, onValueChange = { artist = it }, label = { Text("Artista") }, singleLine = true, modifier = Modifier.weight(1f))
                            Button(
                                onClick = {
                                    scope.launch {
                                        searching = true
                                        results = appState.lyricsService.searchAllProviders(title, artist, song.albumName, song.duration, song.id)
                                        searching = false
                                    }
                                },
                                enabled = !searching,
                            ) { Text("Buscar") }
                        }
                        if (searching) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text("Consultando 8 proveedores de letras...", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(results) { cand ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        appState.saveManualLyrics(cand.rawLyrics, cand.provider)
                                        appState.closeLyricsTool()
                                    },
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                            Text(cand.provider, fontWeight = FontWeight.Bold)
                                            Text(
                                                if (cand.isSynced) "Sincronizada" else "Texto plano",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                        Text(
                                            cand.preview,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            if (!searching && results.isEmpty()) {
                                item {
                                    Text("No se encontraron candidatos.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = appState::closeLyricsTool) { Text("Cerrar") } },
            )
        }

        LyricsToolMode.OFFSET -> {
            var offsetMs by remember(song.id) { mutableStateOf(song.lyricsOffset) }
            LaunchedEffect(song.id) {
                withContext(Dispatchers.IO) {
                    appState.database.getSong(song.id)?.let { offsetMs = it.lyricsOffset }
                }
            }
            AlertDialog(
                onDismissRequest = appState::closeLyricsTool,
                title = { Text("Desfase de sincronización", modifier = Modifier.semantics { heading() }) },
                text = {
                    Column(modifier = Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "${if (offsetMs >= 0) "+" else ""}$offsetMs ms",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "Adelanta (+) o retrasa (-) la sincronización para esta canción.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { offsetMs = (offsetMs - 500).coerceAtLeast(-10_000) }) { Text("-500") }
                            OutlinedButton(onClick = { offsetMs = (offsetMs - 100).coerceAtLeast(-10_000) }) { Text("-100") }
                            OutlinedButton(onClick = { offsetMs = 0 }) { Text("0") }
                            OutlinedButton(onClick = { offsetMs = (offsetMs + 100).coerceAtMost(10_000) }) { Text("+100") }
                            OutlinedButton(onClick = { offsetMs = (offsetMs + 500).coerceAtMost(10_000) }) { Text("+500") }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        appState.setCurrentSongLyricsOffset(offsetMs)
                        appState.showStatus("Desfase guardado: ${offsetMs}ms")
                        appState.closeLyricsTool()
                    }) { Text("Aplicar") }
                },
                dismissButton = { TextButton(onClick = appState::closeLyricsTool) { Text("Cancelar") } },
            )
        }

        LyricsToolMode.SHARE_IMAGE -> {
            val entries = lyrics?.entries.orEmpty().filter { it.text.isNotBlank() }
            val selectedIndices = remember(song.id) { mutableStateListOf<Int>() }
            var style by remember { mutableStateOf(ShareCardStyle.GRADIENT) }

            AlertDialog(
                onDismissRequest = appState::closeLyricsTool,
                title = { Text("Compartir tarjeta de letras (máx. 5 líneas)", modifier = Modifier.semantics { heading() }) },
                text = {
                    Column(modifier = Modifier.width(480.dp).heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ShareCardStyle.entries.forEach { st ->
                                FilterChip(
                                    selected = style == st,
                                    onClick = { style = st },
                                    label = { Text(st.label) },
                                )
                            }
                        }
                        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            itemsIndexed(entries) { idx, entry ->
                                val isSel = idx in selectedIndices
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        if (isSel) selectedIndices.remove(idx)
                                        else if (selectedIndices.size < 5) selectedIndices.add(idx)
                                    },
                                ) {
                                    Checkbox(
                                        checked = isSel,
                                        onCheckedChange = { checked ->
                                            if (!checked) selectedIndices.remove(idx)
                                            else if (selectedIndices.size < 5) selectedIndices.add(idx)
                                        },
                                    )
                                    Text(entry.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    val chosenLines = selectedIndices.sorted().mapNotNull { entries.getOrNull(it)?.text }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = chosenLines.isNotEmpty(),
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val img = DesktopLyricsCardRenderer.renderLyricsCard(
                                        chosenLines, song.title, song.artistDisplay, song.thumbnailUrl, primaryArgb, style,
                                    )
                                    DesktopLyricsCardRenderer.copyToClipboard(img)
                                    appState.showStatus("Tarjeta de letras copiada al portapapeles")
                                }
                                appState.closeLyricsTool()
                            },
                        ) { Text("Copiar imagen") }
                        Button(
                            enabled = chosenLines.isNotEmpty(),
                            onClick = {
                                val chooser = JFileChooser().apply {
                                    selectedFile = File("${song.title.replace(Regex("[^A-Za-z0-9 _-]"), "")}_lyrics.png")
                                }
                                if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                                    val target = chooser.selectedFile
                                    scope.launch(Dispatchers.IO) {
                                        val img = DesktopLyricsCardRenderer.renderLyricsCard(
                                            chosenLines, song.title, song.artistDisplay, song.thumbnailUrl, primaryArgb, style,
                                        )
                                        DesktopLyricsCardRenderer.savePng(img, target)
                                            .onSuccess { appState.showStatus("Tarjeta PNG guardada en ${it.name}") }
                                            .onFailure { appState.showStatus("Error al guardar PNG: ${it.message}") }
                                    }
                                    appState.closeLyricsTool()
                                }
                            },
                        ) { Text("Guardar PNG") }
                    }
                },
                dismissButton = { TextButton(onClick = appState::closeLyricsTool) { Text("Cancelar") } },
            )
        }
    }
}

@Composable
fun TimeTransferDialog(appState: DesktopAppState) {
    val open by appState.showTimeTransferDialog.collectAsState()
    if (!open) return
    val topSongs by appState.topSongs.collectAsState()
    val librarySongs by appState.librarySongs.collectAsState()
    val allSongs = remember(topSongs, librarySongs) { (topSongs + librarySongs).distinctBy { it.id } }

    var fromQuery by remember { mutableStateOf("") }
    var toQuery by remember { mutableStateOf("") }
    var fromSong by remember { mutableStateOf<DesktopSong?>(null) }
    var toSong by remember { mutableStateOf<DesktopSong?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = appState::closeTimeTransferDialog,
        title = { Text("Transferir tiempo de escucha", modifier = Modifier.semantics { heading() }) },
        text = {
            Column(modifier = Modifier.width(500.dp).heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Combina el historial y minutos escuchados de una canción origen (ej. un video o re-subida) hacia la canción oficial de destino.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = fromQuery,
                    onValueChange = { fromQuery = it },
                    label = { Text("1. Buscar canción origen${fromSong?.let { " (${it.title})" }.orEmpty()}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(modifier = Modifier.height(110.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(allSongs.filter { fromQuery.isBlank() || it.title.contains(fromQuery, true) || it.artistDisplay.contains(fromQuery, true) }.take(20)) { s ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (fromSong?.id == s.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth().clickable { fromSong = s },
                        ) {
                            Text("${s.title} — ${s.artistDisplay} (${s.totalPlayTime / 60_000L}m)", modifier = Modifier.padding(8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                OutlinedTextField(
                    value = toQuery,
                    onValueChange = { toQuery = it },
                    label = { Text("2. Buscar canción destino${toSong?.let { " (${it.title})" }.orEmpty()}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(modifier = Modifier.height(110.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(allSongs.filter { it.id != fromSong?.id && (toQuery.isBlank() || it.title.contains(toQuery, true) || it.artistDisplay.contains(toQuery, true)) }.take(20)) { s ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (toSong?.id == s.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth().clickable { toSong = s },
                        ) {
                            Text("${s.title} — ${s.artistDisplay} (${s.totalPlayTime / 60_000L}m)", modifier = Modifier.padding(8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = fromSong != null && toSong != null && fromSong!!.id != toSong!!.id,
                onClick = {
                    val src = fromSong!!
                    val dst = toSong!!
                    scope.launch(Dispatchers.IO) {
                        val ok = appState.database.transferSongStats(src.id, dst.id)
                        appState.showStatus(if (ok) "Estadísticas transferidas a \"${dst.title}\"" else "No se pudo transferir")
                    }
                    appState.closeTimeTransferDialog()
                },
            ) { Text("Transferir") }
        },
        dismissButton = { TextButton(onClick = appState::closeTimeTransferDialog) { Text("Cancelar") } },
    )
}

@Composable
fun ChangelogAndUpdateDialog(appState: DesktopAppState) {
    val open by appState.showChangelogDialog.collectAsState()
    if (!open) return
    var releases by remember { mutableStateOf<List<DesktopReleaseInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        DesktopUpdater.fetchReleases(forceRefresh = true)
            .onSuccess { releases = it }
            .onFailure { error = it.message }
        loading = false
    }

    AlertDialog(
        onDismissRequest = appState::closeChangelogDialog,
        title = { Text("Actualizaciones y Novedades (v${DesktopUpdater.CURRENT_VERSION})", modifier = Modifier.semantics { heading() }) },
        text = {
            Column(modifier = Modifier.width(540.dp).heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Consultando lanzamientos en GitHub...")
                    }
                } else if (error != null) {
                    Text("Error: $error", color = MaterialTheme.colorScheme.error)
                } else if (releases.isEmpty()) {
                    Text("No se encontraron lanzamientos publicados.")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(releases) { rel ->
                            val isNewer = DesktopUpdater.isUpdateAvailable(latestVersion = rel.displayVersion)
                            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text("${rel.name.ifBlank { rel.tag_name }}", fontWeight = FontWeight.Bold)
                                        if (isNewer) {
                                            Text("NUEVA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    if (rel.body.isNotBlank()) {
                                        Text(rel.body.take(800), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (rel.html_url.isNotBlank()) {
                                        OutlinedButton(onClick = { openExternalUrl(rel.html_url) }) { Text("Abrir en GitHub") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = appState::closeChangelogDialog) { Text("Cerrar") } },
    )
}

@Composable
fun CsvImportDialog(
    file: File,
    appState: DesktopAppState,
    onDismiss: () -> Unit,
) {
    val preview = remember(file) { PlaylistTransferService.previewCsv(file).getOrNull() }
    var titleCol by remember(preview) { mutableStateOf(preview?.suggestedTitleCol ?: 0) }
    var artistCol by remember(preview) { mutableStateOf(preview?.suggestedArtistCol ?: -1) }
    var urlCol by remember(preview) { mutableStateOf(preview?.suggestedUrlCol ?: -1) }
    var hasHeader by remember { mutableStateOf(true) }
    var playlistName by remember(file) { mutableStateOf(file.nameWithoutExtension) }
    var importing by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!importing) onDismiss() },
        title = { Text("Importar playlist CSV", modifier = Modifier.semantics { heading() }) },
        text = {
            Column(modifier = Modifier.width(500.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (preview == null) {
                    Text("No se pudo leer el archivo CSV.", color = MaterialTheme.colorScheme.error)
                } else {
                    OutlinedTextField(
                        value = playlistName,
                        onValueChange = { playlistName = it },
                        label = { Text("Nombre de la nueva playlist") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = hasHeader, onCheckedChange = { hasHeader = it })
                        Text("Primera fila es encabezado (${preview.totalRows} filas detectadas)")
                    }
                    Text("Columnas: ${preview.headers.mapIndexed { i, h -> "$i: $h" }.joinToString(" | ")}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = titleCol.toString(),
                            onValueChange = { titleCol = it.toIntOrNull() ?: 0 },
                            label = { Text("Col. Título") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = artistCol.toString(),
                            onValueChange = { artistCol = it.toIntOrNull() ?: -1 },
                            label = { Text("Col. Artista (-1)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = urlCol.toString(),
                            onValueChange = { urlCol = it.toIntOrNull() ?: -1 },
                            label = { Text("Col. ID/URL (-1)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (progressText.isNotBlank()) {
                        Text(progressText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = preview != null && !importing && playlistName.isNotBlank(),
                onClick = {
                    importing = true
                    scope.launch {
                        val content = withContext(Dispatchers.IO) { file.readText(Charsets.UTF_8) }
                        val candidates = PlaylistTransferService.extractCsvCandidates(content, titleCol, artistCol, urlCol, hasHeader)
                        val songs = PlaylistTransferService.resolveCandidatesOnline(candidates) { idx, total, msg ->
                            progressText = msg
                        }
                        if (songs.isNotEmpty()) {
                            val pl = withContext(Dispatchers.IO) { appState.database.createPlaylist(playlistName.trim()) }
                            withContext(Dispatchers.IO) {
                                songs.forEach { appState.database.addSongToPlaylistAt(pl.id, it) }
                            }
                            appState.showStatus("Playlist \"${pl.name}\" importada con ${songs.size}/${candidates.size} canciones")
                        } else {
                            appState.showStatus("No se pudo resolver ninguna canción del CSV")
                        }
                        importing = false
                        onDismiss()
                    }
                },
            ) { Text(if (importing) "Importando..." else "Importar") }
        },
        dismissButton = {
            TextButton(enabled = !importing, onClick = onDismiss) { Text("Cancelar") }
        },
    )
}
