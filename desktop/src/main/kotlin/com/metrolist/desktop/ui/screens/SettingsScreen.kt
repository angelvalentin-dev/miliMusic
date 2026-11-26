package com.metrolist.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.AppPaths
import com.metrolist.desktop.audio.StreamProxyServer
import com.metrolist.desktop.backup.BackupPreview
import com.metrolist.desktop.data.AllRomanizationLanguages
import com.metrolist.desktop.data.DesktopAudioQuality
import com.metrolist.desktop.data.DesktopDarkMode
import com.metrolist.desktop.data.DesktopGridSize
import com.metrolist.desktop.data.DesktopLoudnessLevel
import com.metrolist.desktop.data.DesktopLyricsPosition
import com.metrolist.desktop.data.DesktopPlayerBackground
import com.metrolist.desktop.data.DesktopPlaylistAddPosition
import com.metrolist.desktop.data.DesktopPrefKeys
import com.metrolist.desktop.data.DesktopProxyType
import com.metrolist.desktop.data.DesktopQuickPicksMode
import com.metrolist.desktop.data.DesktopSliderStyle
import com.metrolist.desktop.db.DesktopAlarmEntry
import com.metrolist.desktop.integrations.DesktopLastFmService
import com.metrolist.desktop.integrations.ListenTogetherPresetServers
import com.metrolist.desktop.lyrics.DesktopAiProviders
import com.metrolist.desktop.state.DesktopAppState
import com.metrolist.desktop.ui.components.CsvImportDialog
import com.metrolist.desktop.ui.components.copyTextToClipboard
import com.metrolist.desktop.ui.components.openExternalUrl
import com.metrolist.desktop.ui.icons.ArrowDownward
import com.metrolist.desktop.ui.icons.ArrowUpward
import com.metrolist.desktop.util.DesktopUpdater
import com.metrolist.desktop.util.PlaylistTransferService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.math.roundToInt

private enum class SettingsSection(val label: String) {
    APPEARANCE("Apariencia"),
    PLAYER("Reproductor y Audio"),
    CONTENT("Contenido y Letras"),
    AI("Traducción IA"),
    INTEGRATIONS("Integraciones"),
    PRIVACY("Privacidad"),
    STORAGE("Almacenamiento"),
    BACKUP("Respaldo e Importar"),
    DESKTOP("Windows"),
    ABOUT("Actualizaciones y Acerca de"),
}

private val AccentPalettes = listOf(
    "Rosa Metrolist" to 0xFFED5564.toInt(),
    "Azul Índigo" to 0xFF4285F4.toInt(),
    "Verde Esmeralda" to 0xFF22C55E.toInt(),
    "Ámbar Dorado" to 0xFFF59E0B.toInt(),
    "Violeta" to 0xFF8B5CF6.toInt(),
    "Turquesa" to 0xFF06B6D4.toInt(),
    "Coral" to 0xFFF97316.toInt(),
    "Carmesí" to 0xFFDC2626.toInt(),
    "Fucsia" to 0xFFD946EF.toInt(),
    "Cielo" to 0xFF0EA5E9.toInt(),
    "Lima" to 0xFF84CC16.toInt(),
    "Menta" to 0xFF10B981.toInt(),
    "Teal" to 0xFF14B8A6.toInt(),
    "Púrpura" to 0xFFA855F7.toInt(),
    "Rosa Suave" to 0xFFEC4899.toInt(),
    "Ocre" to 0xFFD97706.toInt(),
    "Cobalto" to 0xFF2563EB.toInt(),
    "Bosque" to 0xFF16A34A.toInt(),
    "Pizarra" to 0xFF64748B.toInt(),
    "Grafito" to 0xFF52525B.toInt(),
)

private val ContentLanguages = listOf(
    "SYSTEM_DEFAULT" to "Sistema",
    "es" to "Español",
    "en" to "English",
    "pt" to "Português",
    "fr" to "Français",
    "de" to "Deutsch",
    "it" to "Italiano",
    "ja" to "日本語",
    "ko" to "한국어",
    "zh-CN" to "中文",
    "ru" to "Русский",
    "hi" to "हिन्दी",
)

private val ContentCountries = listOf(
    "SYSTEM_DEFAULT" to "Sistema",
    "US" to "Estados Unidos",
    "MX" to "México",
    "ES" to "España",
    "AR" to "Argentina",
    "CO" to "Colombia",
    "CL" to "Chile",
    "PE" to "Perú",
    "BR" to "Brasil",
    "GB" to "Reino Unido",
    "JP" to "Japón",
    "KR" to "Corea del Sur",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(appState: DesktopAppState) {
    val settings by appState.settings.collectAsState()
    val alarms by appState.alarmScheduler.alarms.collectAsState()
    val discordConnected by appState.discordRpc.connected.collectAsState()
    val ltLogs by appState.listenTogetherManager.logs.collectAsState()
    val playlists by appState.playlists.collectAsState()
    val latestRelease by appState.latestRelease.collectAsState()
    val updateAvailable by appState.updateAvailable.collectAsState()
    val scope = rememberCoroutineScope()

    var section by remember { mutableStateOf(SettingsSection.APPEARANCE) }
    var addAlarmOpen by remember { mutableStateOf(false) }
    var csvImportFile by remember { mutableStateOf<File?>(null) }
    var backupPreviewFile by remember { mutableStateOf<Pair<File, BackupPreview>?>(null) }

    // Storage live sizes
    var downloadsBytes by remember { mutableStateOf(0L) }
    var songCacheBytes by remember { mutableStateOf(0L) }
    var imageCacheBytes by remember { mutableStateOf(0L) }

    DisposableEffect(addAlarmOpen, csvImportFile, backupPreviewFile) {
        appState.setLocalModalOpen(addAlarmOpen || csvImportFile != null || backupPreviewFile != null)
        onDispose { appState.setLocalModalOpen(false) }
    }

    LaunchedEffect(section, settings.downloadsDir) {
        if (section == SettingsSection.STORAGE) {
            withContext(Dispatchers.IO) {
                downloadsBytes = StreamProxyServer.directorySizeBytes(File(settings.downloadsDir))
                songCacheBytes = StreamProxyServer.directorySizeBytes(AppPaths.songCacheDir)
                imageCacheBytes = StreamProxyServer.directorySizeBytes(AppPaths.imageCacheDir)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            "Ajustes",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsSection.entries.forEach { sec ->
                FilterChip(
                    selected = section == sec,
                    onClick = { section = sec },
                    label = { Text(sec.label) },
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (section) {
                SettingsSection.APPEARANCE -> {
                    item {
                        SettingsCard("Tema y colores") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DesktopDarkMode.entries.forEach { mode ->
                                    FilterChip(
                                        selected = settings.darkMode == mode,
                                        onClick = { scope.launch { appState.preferences.set(DesktopPrefKeys.DarkMode, mode.name) } },
                                        label = {
                                            Text(when (mode) {
                                                DesktopDarkMode.SYSTEM -> "Sistema"
                                                DesktopDarkMode.LIGHT -> "Claro"
                                                DesktopDarkMode.DARK -> "Oscuro"
                                            })
                                        },
                                    )
                                }
                            }
                            SettingsSwitch("Negro puro (OLED)", settings.pureBlack) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.PureBlack, it) }
                            }
                            SettingsSwitch("Tema dinámico según carátula", settings.dynamicTheme) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.DynamicTheme, it) }
                            }
                            Text("Paleta de acento (20 colores Material 3)", style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                AccentPalettes.forEach { (name, argb) ->
                                    val selected = settings.selectedThemeColor == argb
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .background(Color(argb))
                                            .border(
                                                width = if (selected) 3.dp else 1.dp,
                                                color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                                shape = CircleShape,
                                            )
                                            .semantics {
                                                contentDescription = "Color $name"
                                                stateDescription = if (selected) "Seleccionado" else "No seleccionado"
                                            }
                                            .clickable(role = Role.RadioButton) {
                                                scope.launch { appState.preferences.set(DesktopPrefKeys.SelectedThemeColor, argb) }
                                            },
                                    )
                                }
                            }
                        }
                    }

                    item {
                        SettingsCard("Reproductor y diseño") {
                            Text("Fondo del reproductor expandido", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DesktopPlayerBackground.entries.forEach { bg ->
                                    FilterChip(
                                        selected = settings.playerBackground == bg,
                                        onClick = { scope.launch { appState.preferences.set(DesktopPrefKeys.PlayerBackgroundStyle, bg.name) } },
                                        label = { Text(when (bg) { DesktopPlayerBackground.DEFAULT -> "Predeterminado"; DesktopPlayerBackground.GRADIENT -> "Degradado"; DesktopPlayerBackground.BLUR -> "Difuminado" }) },
                                    )
                                }
                            }
                            Text("Estilo de barra de progreso", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DesktopSliderStyle.entries.forEach { st ->
                                    FilterChip(
                                        selected = settings.sliderStyle == st,
                                        onClick = { scope.launch { appState.preferences.set(DesktopPrefKeys.SliderStyle, st.name) } },
                                        label = { Text(when (st) { DesktopSliderStyle.DEFAULT -> "Estándar"; DesktopSliderStyle.WAVY -> "Ondulado"; DesktopSliderStyle.SLIM -> "Delgado" }) },
                                    )
                                }
                            }
                            Text("Tamaño de tarjetas", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = settings.gridSize == DesktopGridSize.BIG,
                                    onClick = { scope.launch { appState.preferences.set(DesktopPrefKeys.GridItemsSize, DesktopGridSize.BIG.name) } },
                                    label = { Text("Grande") },
                                )
                                FilterChip(
                                    selected = settings.gridSize == DesktopGridSize.SMALL,
                                    onClick = { scope.launch { appState.preferences.set(DesktopPrefKeys.GridItemsSize, DesktopGridSize.SMALL.name) } },
                                    label = { Text("Compacto") },
                                )
                            }
                            Text("Pestaña inicial al abrir la app", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("HOME" to "Inicio", "EXPLORE" to "Explorar", "SEARCH" to "Buscar", "LIBRARY" to "Biblioteca").forEach { (id, lbl) ->
                                    FilterChip(
                                        selected = settings.defaultOpenTab.equals(id, true),
                                        onClick = { scope.launch { appState.preferences.set(DesktopPrefKeys.DefaultOpenTab, id) } },
                                        label = { Text(lbl) },
                                    )
                                }
                            }
                        }
                    }

                    item {
                        SettingsCard("Apariencia de letras") {
                            Text("Tamaño de texto: ${settings.lyricsTextSize.roundToInt()} sp", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = settings.lyricsTextSize,
                                onValueChange = { scope.launch { appState.preferences.set(DesktopPrefKeys.LyricsTextSize, it) } },
                                valueRange = 12f..36f,
                            )
                            Text("Espaciado entre líneas: ${"%.1f".format(settings.lyricsLineSpacing)}x", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = settings.lyricsLineSpacing,
                                onValueChange = { scope.launch { appState.preferences.set(DesktopPrefKeys.LyricsLineSpacing, it) } },
                                valueRange = 1.0f..2.2f,
                            )
                            Text("Alineación de letras", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DesktopLyricsPosition.entries.forEach { pos ->
                                    FilterChip(
                                        selected = settings.lyricsPosition == pos,
                                        onClick = { scope.launch { appState.preferences.set(DesktopPrefKeys.LyricsTextPosition, pos.name) } },
                                        label = { Text(when (pos) { DesktopLyricsPosition.LEFT -> "Izquierda"; DesktopLyricsPosition.CENTER -> "Centro"; DesktopLyricsPosition.RIGHT -> "Derecha" }) },
                                    )
                                }
                            }
                            SettingsSwitch("Alineación de dúos / vocalistas (TTML)", settings.respectAgentPositioning) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.RespectAgentPositioning, it) }
                            }
                            SettingsSwitch("Mostrar puntos en pausas instrumentales", settings.showIntervalIndicator) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.ShowIntervalIndicator, it) }
                            }
                            SettingsSwitch("Clic en línea para saltar al tiempo", settings.lyricsClick) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.LyricsClick, it) }
                            }
                            SettingsSwitch("Desplazamiento automático de letras", settings.lyricsScroll) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.LyricsScroll, it) }
                            }
                        }
                    }

                    item {
                        SettingsCard("Visibilidad de listas automáticas y Wrapped") {
                            SettingsSwitch("Mostrar tarjeta Wrapped en Inicio", settings.showWrappedCard) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.ShowWrappedCard, it) }
                            }
                            SettingsSwitch("Mostrar Favoritas", settings.showLikedPlaylist) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.ShowLikedPlaylist, it) }
                            }
                            SettingsSwitch("Mostrar Descargas", settings.showDownloadedPlaylist) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.ShowDownloadedPlaylist, it) }
                            }
                            SettingsSwitch("Mostrar Mi Top", settings.showTopPlaylist) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.ShowTopPlaylist, it) }
                            }
                            SettingsSwitch("Mostrar En caché", settings.showCachedPlaylist) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.ShowCachedPlaylist, it) }
                            }
                            SettingsSwitch("Mostrar Subidas", settings.showUploadedPlaylist) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.ShowUploadedPlaylist, it) }
                            }
                        }
                    }
                }

                SettingsSection.PLAYER -> {
                    item {
                        SettingsCard("Calidad, Normalización y Audio") {
                            Text("Calidad de audio", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DesktopAudioQuality.entries.forEach { q ->
                                    FilterChip(
                                        selected = settings.audioQuality == q,
                                        onClick = { scope.launch { appState.preferences.set(DesktopPrefKeys.AudioQuality, q.name) } },
                                        label = { Text(when (q) { DesktopAudioQuality.AUTO -> "Automática"; DesktopAudioQuality.LOW -> "Baja"; DesktopAudioQuality.HIGH -> "Alta" }) },
                                    )
                                }
                            }
                            SettingsSwitch("Normalización de volumen (LUFS)", settings.audioNormalization) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.AudioNormalization, it) }
                            }
                            if (settings.audioNormalization) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    DesktopLoudnessLevel.entries.forEach { lvl ->
                                        FilterChip(
                                            selected = settings.loudnessLevel == lvl,
                                            onClick = { scope.launch { appState.preferences.set(DesktopPrefKeys.LoudnessLevel, lvl.name) } },
                                            label = { Text(lvl.label) },
                                        )
                                    }
                                }
                            }
                            SettingsSwitch("Crossfade (transición suave entre pistas)", settings.crossfadeEnabled) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.CrossfadeEnabled, it) }
                            }
                            if (settings.crossfadeEnabled) {
                                Text("Duración Crossfade: ${settings.crossfadeDurationSec.roundToInt()} s", style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = settings.crossfadeDurationSec,
                                    onValueChange = { scope.launch { appState.preferences.set(DesktopPrefKeys.CrossfadeDuration, it) } },
                                    valueRange = 1f..15f,
                                    steps = 13,
                                )
                                SettingsSwitch("Modo sin pausas (omitir crossfade en el mismo álbum)", settings.crossfadeGapless) {
                                    scope.launch { appState.preferences.set(DesktopPrefKeys.CrossfadeGapless, it) }
                                }
                            }
                            SettingsSwitch("Omitir silencios / Compresor suave", settings.skipSilence) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.SkipSilence, it) }
                            }
                            SettingsSwitch("Varispeed (vincular velocidad y tono)", settings.varispeed) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.Varispeed, it) }
                            }
                            Text("Umbral para registrar historial: ${settings.historyDurationSec.roundToInt()} s", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = settings.historyDurationSec,
                                onValueChange = { scope.launch { appState.preferences.set(DesktopPrefKeys.HistoryDuration, it) } },
                                valueRange = 3f..60f,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { appState.openEqualizerDialog() }) { Text("Abrir Ecualizador / AutoEQ") }
                                OutlinedButton(onClick = { appState.openSleepTimerDialog() }) { Text("Temporizador manual") }
                            }
                        }
                    }

                    item {
                        SettingsCard("Comportamiento de cola") {
                            SettingsSwitch("Cola persistente al reiniciar", settings.persistentQueue) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.PersistentQueue, it) }
                            }
                            SettingsSwitch("Reproducción automática (Radio infinita)", settings.autoplay) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.Autoplay, it) }
                            }
                            SettingsSwitch("Cargar más pistas automáticamente", settings.autoLoadMore) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.AutoLoadMore, it) }
                            }
                            SettingsSwitch("Desactivar carga automática con Repetir todo", settings.disableLoadMoreWhenRepeatAll) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.DisableLoadMoreWhenRepeatAll, it) }
                            }
                            SettingsSwitch("Mostrar contenido similar (Automix) en cola", settings.similarContent) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.SimilarContent, it) }
                            }
                            SettingsSwitch("Evitar pistas duplicadas en la cola", settings.preventDuplicateTracksInQueue) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.PreventDuplicateTracksInQueue, it) }
                            }
                            SettingsSwitch("Recordar modo aleatorio y repetición", settings.rememberShuffleAndRepeat) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.RememberShuffleAndRepeat, it) }
                            }
                            SettingsSwitch("Mantener aleatorio entre colas", settings.persistentShuffleAcrossQueues) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.PersistentShuffleAcrossQueues, it) }
                            }
                            SettingsSwitch("Mezclar playlist al iniciar aleatorio", settings.shufflePlaylistFirst) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.ShufflePlaylistFirst, it) }
                            }
                            SettingsSwitch("Descargar automáticamente al dar Me gusta", settings.autoDownloadOnLike) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.AutoDownloadOnLike, it) }
                            }
                            SettingsSwitch("Saltar automáticamente si una pista falla", settings.autoSkipNextOnError) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.AutoSkipNextOnError, it) }
                            }
                            SettingsSwitch("Pausar al silenciar el volumen", settings.pauseOnMute) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.PauseOnMute, it) }
                            }
                        }
                    }

                    item {
                        SettingsCard("Temporizador programado automático") {
                            SettingsSwitch("Activar ventana nocturna automática", settings.sleepTimerEnabled) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.SleepTimerEnabled, it) }
                            }
                            if (settings.sleepTimerEnabled) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = settings.sleepTimerRepeat == "DAILY",
                                        onClick = { scope.launch { appState.preferences.set(DesktopPrefKeys.SleepTimerRepeat, "DAILY") } },
                                        label = { Text("Todos los días") },
                                    )
                                    FilterChip(
                                        selected = settings.sleepTimerRepeat == "CUSTOM",
                                        onClick = { scope.launch { appState.preferences.set(DesktopPrefKeys.SleepTimerRepeat, "CUSTOM") } },
                                        label = { Text("Días personalizados") },
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = settings.sleepTimerStartTime,
                                        onValueChange = { v -> scope.launch { appState.preferences.set(DesktopPrefKeys.SleepTimerStartTime, v) } },
                                        label = { Text("Inicio (HH:mm)") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    OutlinedTextField(
                                        value = settings.sleepTimerEndTime,
                                        onValueChange = { v -> scope.launch { appState.preferences.set(DesktopPrefKeys.SleepTimerEndTime, v) } },
                                        label = { Text("Fin (HH:mm)") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (settings.sleepTimerRepeat == "CUSTOM") {
                                    val dayNames = listOf(1 to "L", 2 to "M", 3 to "X", 4 to "J", 5 to "V", 6 to "S", 7 to "D")
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        dayNames.forEach { (d, lbl) ->
                                            val active = d in settings.sleepTimerCustomDays
                                            FilterChip(
                                                selected = active,
                                                onClick = {
                                                    val next = if (active) settings.sleepTimerCustomDays - d else settings.sleepTimerCustomDays + d
                                                    if (next.isNotEmpty()) {
                                                        scope.launch { appState.preferences.set(DesktopPrefKeys.SleepTimerCustomDays, next.sorted().joinToString(",")) }
                                                    }
                                                },
                                                label = { Text(lbl) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        SettingsCard("Alarmas musicales") {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Programa música con recurrencia semanal, rampa de volumen y posponer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                Button(onClick = { addAlarmOpen = true }) {
                                    Icon(Icons.Rounded.Add, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Nueva alarma")
                                }
                            }
                            if (alarms.isEmpty()) {
                                Text("No hay alarmas configuradas.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                alarms.forEach { alarm ->
                                    val dayLabels = listOf(1 to "L", 2 to "M", 3 to "X", 4 to "J", 5 to "V", 6 to "S", 7 to "D")
                                        .filter { it.first in alarm.daysOfWeek }.joinToString("") { it.second }.ifBlank { "Todos" }
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("${"%02d:%02d".format(alarm.hour, alarm.minute)}${alarm.label.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()}", fontWeight = FontWeight.Bold)
                                            Text(
                                                "Días: $dayLabels • ${if (alarm.randomSong) "Aleatorio" else "En orden"}${if (alarm.volumeRamp) " • Rampa vol." else ""}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            IconButton(onClick = { appState.alarmScheduler.triggerAlarm(alarm) }) {
                                                Icon(Icons.Rounded.PlayArrow, contentDescription = "Probar alarma")
                                            }
                                            Switch(
                                                checked = alarm.enabled,
                                                onCheckedChange = { enabled ->
                                                    appState.saveAlarms(alarms.map { if (it.id == alarm.id) it.copy(enabled = enabled) else it })
                                                },
                                            )
                                            IconButton(onClick = { appState.saveAlarms(alarms.filterNot { it.id == alarm.id }) }) {
                                                Icon(Icons.Rounded.Delete, contentDescription = "Eliminar alarma")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                SettingsSection.CONTENT -> {
                    item {
                        SettingsCard("Idioma, Región y Filtros de contenido") {
                            Text("Idioma de contenido YouTube Music", style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                ContentLanguages.forEach { (code, lbl) ->
                                    FilterChip(
                                        selected = settings.contentLanguage == code,
                                        onClick = { scope.launch { appState.preferences.set(DesktopPrefKeys.ContentLanguage, code) } },
                                        label = { Text(lbl) },
                                    )
                                }
                            }
                            Text("País de contenido YouTube Music", style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                ContentCountries.forEach { (code, lbl) ->
                                    FilterChip(
                                        selected = settings.contentCountry == code,
                                        onClick = { scope.launch { appState.preferences.set(DesktopPrefKeys.ContentCountry, code) } },
                                        label = { Text(lbl) },
                                    )
                                }
                            }
                            SettingsSwitch("Ocultar contenido explícito", settings.hideExplicit) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.HideExplicit, it) }
                            }
                            SettingsSwitch("Ocultar videos musicales", settings.hideVideoSongs) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.HideVideoSongs, it) }
                            }
                            SettingsSwitch("Ocultar YouTube Shorts", settings.hideYoutubeShorts) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.HideYoutubeShorts, it) }
                            }
                            SettingsSwitch("Orden aleatorio de secciones en Inicio", settings.randomizeHomeOrder) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.RandomizeHomeOrder, it) }
                            }
                            Text("Posición al añadir a playlist", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = settings.addToPlaylistPosition == DesktopPlaylistAddPosition.BEGINNING,
                                    onClick = { scope.launch { appState.preferences.set(DesktopPrefKeys.AddToPlaylistPosition, DesktopPlaylistAddPosition.BEGINNING.name) } },
                                    label = { Text("Al principio") },
                                )
                                FilterChip(
                                    selected = settings.addToPlaylistPosition == DesktopPlaylistAddPosition.END,
                                    onClick = { scope.launch { appState.preferences.set(DesktopPrefKeys.AddToPlaylistPosition, DesktopPlaylistAddPosition.END.name) } },
                                    label = { Text("Al final") },
                                )
                            }
                            Text("Modo de Selección rápida", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = settings.quickPicksMode == DesktopQuickPicksMode.QUICK_PICKS,
                                    onClick = { scope.launch { appState.preferences.set(DesktopPrefKeys.QuickPicks, DesktopQuickPicksMode.QUICK_PICKS.name) } },
                                    label = { Text("Selección rápida") },
                                )
                                FilterChip(
                                    selected = settings.quickPicksMode == DesktopQuickPicksMode.LAST_LISTEN,
                                    onClick = { scope.launch { appState.preferences.set(DesktopPrefKeys.QuickPicks, DesktopQuickPicksMode.LAST_LISTEN.name) } },
                                    label = { Text("Última escucha") },
                                )
                            }
                            Text("Tamaño de Mi Top: ${settings.topSize} canciones", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = settings.topSize.toFloat(),
                                onValueChange = { scope.launch { appState.preferences.set(DesktopPrefKeys.TopSize, it.roundToInt().toString()) } },
                                valueRange = 10f..100f,
                            )
                            SettingsSwitch("Mostrar biografía en páginas de artista", settings.showArtistDescription) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.ShowArtistDescription, it) }
                            }
                            SettingsSwitch("Mostrar número de suscriptores del artista", settings.showArtistSubscriberCount) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.ShowArtistSubscriberCount, it) }
                            }
                            SettingsSwitch("Mostrar oyentes mensuales del artista", settings.showMonthlyListeners) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.ShowMonthlyListeners, it) }
                            }
                        }
                    }

                    item {
                        SettingsCard("Proxy de red (HTTP / SOCKS)") {
                            SettingsSwitch("Activar Proxy para YouTube Music", settings.proxyEnabled) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.ProxyEnabled, it) }
                            }
                            if (settings.proxyEnabled) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    DesktopProxyType.entries.forEach { t ->
                                        FilterChip(
                                            selected = settings.proxyType == t,
                                            onClick = { scope.launch { appState.preferences.set(DesktopPrefKeys.ProxyType, t.name) } },
                                            label = { Text(t.name) },
                                        )
                                    }
                                }
                                OutlinedTextField(
                                    value = settings.proxyUrl,
                                    onValueChange = { v -> scope.launch { appState.preferences.set(DesktopPrefKeys.ProxyUrl, v) } },
                                    label = { Text("Host:Puerto (ej. 127.0.0.1:1080)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = settings.proxyUsername,
                                        onValueChange = { v -> scope.launch { appState.preferences.set(DesktopPrefKeys.ProxyUsername, v) } },
                                        label = { Text("Usuario (opcional)") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    OutlinedTextField(
                                        value = settings.proxyPassword,
                                        onValueChange = { v -> scope.launch { appState.preferences.set(DesktopPrefKeys.ProxyPassword, v) } },
                                        label = { Text("Contraseña (opcional)") },
                                        visualTransformation = PasswordVisualTransformation(),
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }

                    item {
                        SettingsCard("Proveedores de letras y prioridad (8 fuentes)") {
                            settings.lyricsProviderOrder.forEachIndexed { idx, provider ->
                                val enabled = when (provider) {
                                    "BetterLyrics" -> settings.enableBetterLyrics
                                    "LrcLib" -> settings.enableLrcLib
                                    "Paxsenix" -> settings.enablePaxsenix
                                    "LyricsPlus" -> settings.enableLyricsPlus
                                    "KuGou" -> settings.enableKugou
                                    "Zemer" -> settings.enableZemer
                                    else -> true
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text("${idx + 1}. $provider", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (idx > 0) {
                                            IconButton(onClick = {
                                                val list = settings.lyricsProviderOrder.toMutableList()
                                                val moved = list.removeAt(idx)
                                                list.add(idx - 1, moved)
                                                scope.launch { appState.preferences.set(DesktopPrefKeys.LyricsProviderOrder, list.joinToString(",")) }
                                            }, modifier = Modifier.size(28.dp)) {
                                                Icon(Icons.Rounded.ArrowUpward, contentDescription = "Subir prioridad $provider", modifier = Modifier.size(16.dp))
                                            }
                                        }
                                        if (idx < settings.lyricsProviderOrder.lastIndex) {
                                            IconButton(onClick = {
                                                val list = settings.lyricsProviderOrder.toMutableList()
                                                val moved = list.removeAt(idx)
                                                list.add(idx + 1, moved)
                                                scope.launch { appState.preferences.set(DesktopPrefKeys.LyricsProviderOrder, list.joinToString(",")) }
                                            }, modifier = Modifier.size(28.dp)) {
                                                Icon(Icons.Rounded.ArrowDownward, contentDescription = "Bajar prioridad $provider", modifier = Modifier.size(16.dp))
                                            }
                                        }
                                        if (provider !in setOf("YouTube", "YouTubeSubtitle")) {
                                            Switch(
                                                checked = enabled,
                                                onCheckedChange = { v ->
                                                    val key = when (provider) {
                                                        "BetterLyrics" -> DesktopPrefKeys.EnableBetterLyrics
                                                        "LrcLib" -> DesktopPrefKeys.EnableLrcLib
                                                        "Paxsenix" -> DesktopPrefKeys.EnablePaxsenix
                                                        "LyricsPlus" -> DesktopPrefKeys.EnableLyricsPlus
                                                        "KuGou" -> DesktopPrefKeys.EnableKugou
                                                        "Zemer" -> DesktopPrefKeys.EnableZemer
                                                        else -> null
                                                    }
                                                    key?.let { k -> scope.launch { appState.preferences.set(k, v) } }
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        SettingsCard("Romanización sin conexión (12 idiomas)") {
                            SettingsSwitch("Mostrar texto romanizado como línea principal", settings.lyricsRomanizeAsMain) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.LyricsRomanizeAsMain, it) }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                AllRomanizationLanguages.forEach { (code, label) ->
                                    val selected = code in settings.lyricsRomanizeLanguages
                                    FilterChip(
                                        selected = selected,
                                        onClick = {
                                            val next = if (selected) settings.lyricsRomanizeLanguages - code else settings.lyricsRomanizeLanguages + code
                                            scope.launch { appState.preferences.set(DesktopPrefKeys.LyricsRomanizeList, next.joinToString(",")) }
                                        },
                                        label = { Text(label) },
                                    )
                                }
                            }
                        }
                    }
                }

                SettingsSection.AI -> {
                    item {
                        val preset = DesktopAiProviders.presetFor(settings.aiProvider)
                        SettingsCard("Traducción IA de letras (10 proveedores)") {
                            Text("Proveedor de traducción", style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                DesktopAiProviders.all.forEach { p ->
                                    FilterChip(
                                        selected = settings.aiProvider.equals(p.id, true),
                                        onClick = {
                                            scope.launch {
                                                appState.preferences.set(DesktopPrefKeys.AiProvider, p.id)
                                                if (p.baseUrl.isNotBlank()) appState.preferences.set(DesktopPrefKeys.OpenRouterBaseUrl, p.baseUrl)
                                                if (p.defaultModel.isNotBlank()) appState.preferences.set(DesktopPrefKeys.OpenRouterModel, p.defaultModel)
                                            }
                                        },
                                        label = { Text(p.displayName) },
                                    )
                                }
                            }

                            if (settings.aiProvider.equals("DeepL", true)) {
                                OutlinedTextField(
                                    value = settings.deeplApiKey,
                                    onValueChange = { v -> scope.launch { appState.preferences.set(DesktopPrefKeys.DeeplApiKey, v) } },
                                    label = { Text("DeepL API Key (:fx para gratis)") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("default" to "Predeterminado", "more" to "Formal", "less" to "Informal").forEach { (k, lbl) ->
                                        FilterChip(
                                            selected = settings.deeplFormality == k,
                                            onClick = { scope.launch { appState.preferences.set(DesktopPrefKeys.DeeplFormality, k) } },
                                            label = { Text(lbl) },
                                        )
                                    }
                                }
                            } else {
                                if (settings.aiProvider.equals("Custom", true)) {
                                    OutlinedTextField(
                                        value = settings.openRouterBaseUrl,
                                        onValueChange = { v -> scope.launch { appState.preferences.set(DesktopPrefKeys.OpenRouterBaseUrl, v) } },
                                        label = { Text("URL Base OpenAI-compatible") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                OutlinedTextField(
                                    value = settings.openRouterApiKey,
                                    onValueChange = { v -> scope.launch { appState.preferences.set(DesktopPrefKeys.OpenRouterApiKey, v) } },
                                    label = { Text("API Key de ${preset.displayName}") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (preset.models.isNotEmpty()) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        preset.models.forEach { m ->
                                            FilterChip(
                                                selected = settings.openRouterModel == m,
                                                onClick = { scope.launch { appState.preferences.set(DesktopPrefKeys.OpenRouterModel, m) } },
                                                label = { Text(m) },
                                            )
                                        }
                                    }
                                }
                                OutlinedTextField(
                                    value = settings.openRouterModel,
                                    onValueChange = { v -> scope.launch { appState.preferences.set(DesktopPrefKeys.OpenRouterModel, v) } },
                                    label = { Text("Modelo IA") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("Literal" to "Traducción literal", "Transcribed" to "Transcripción fonética", "Romanized" to "Romanización IA").forEach { (m, lbl) ->
                                    FilterChip(
                                        selected = settings.translateMode == m,
                                        onClick = { scope.launch { appState.preferences.set(DesktopPrefKeys.TranslateMode, m) } },
                                        label = { Text(lbl) },
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = settings.translateLanguage,
                                onValueChange = { v -> scope.launch { appState.preferences.set(DesktopPrefKeys.TranslateLanguage, v) } },
                                label = { Text("Idioma destino (ej. es, en, fr, pt, ja)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = settings.aiSystemPrompt,
                                onValueChange = { v -> scope.launch { appState.preferences.set(DesktopPrefKeys.AiSystemPrompt, v) } },
                                label = { Text("Prompt del sistema personalizado (opcional, usa {lineCount})") },
                                minLines = 2,
                                maxLines = 4,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (settings.aiSystemPrompt.isNotBlank()) {
                                TextButton(onClick = { scope.launch { appState.preferences.set(DesktopPrefKeys.AiSystemPrompt, "") } }) {
                                    Text("Restablecer prompt predeterminado")
                                }
                            }
                        }
                    }
                }

                SettingsSection.INTEGRATIONS -> {
                    item {
                        SettingsCard("Discord Rich Presence") {
                            SettingsSwitch(
                                title = "Activar Discord RPC (${if (discordConnected) "Conectado" else "Desconectado"})",
                                checked = settings.enableDiscordRpc,
                            ) { scope.launch { appState.preferences.set(DesktopPrefKeys.EnableDiscordRPC, it) } }

                            Text("Tipo de actividad", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("LISTENING" to "Escuchando", "PLAYING" to "Jugando", "WATCHING" to "Viendo", "COMPETING" to "Compitiendo").forEach { (k, lbl) ->
                                    FilterChip(
                                        selected = settings.discordActivityType == k,
                                        onClick = { scope.launch { appState.preferences.set(DesktopPrefKeys.DiscordActivityType, k) } },
                                        label = { Text(lbl) },
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = settings.discordActivityName,
                                onValueChange = { v -> scope.launch { appState.preferences.set(DesktopPrefKeys.DiscordActivityName, v) } },
                                label = { Text("Nombre de actividad") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = settings.discordDetailsTemplate,
                                    onValueChange = { v -> scope.launch { appState.preferences.set(DesktopPrefKeys.DiscordDetailsTemplate, v) } },
                                    label = { Text("Detalles ({song.name}, {artist.name})") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = settings.discordStateTemplate,
                                    onValueChange = { v -> scope.launch { appState.preferences.set(DesktopPrefKeys.DiscordStateTemplate, v) } },
                                    label = { Text("Estado ({artist.name}, {album.name})") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            SettingsSwitch("Botón personalizado 1", settings.discordButton1Enabled) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.DiscordButton1Enabled, it) }
                            }
                            if (settings.discordButton1Enabled) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = settings.discordButton1Text,
                                        onValueChange = { v -> scope.launch { appState.preferences.set(DesktopPrefKeys.DiscordButton1Text, v) } },
                                        label = { Text("Etiqueta Botón 1") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    OutlinedTextField(
                                        value = settings.discordButton1Url,
                                        onValueChange = { v -> scope.launch { appState.preferences.set(DesktopPrefKeys.DiscordButton1Url, v) } },
                                        label = { Text("URL Botón 1 ({song.id})") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            SettingsSwitch("Botón personalizado 2", settings.discordButton2Enabled) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.DiscordButton2Enabled, it) }
                            }
                            if (settings.discordButton2Enabled) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = settings.discordButton2Text,
                                        onValueChange = { v -> scope.launch { appState.preferences.set(DesktopPrefKeys.DiscordButton2Text, v) } },
                                        label = { Text("Etiqueta Botón 2") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    OutlinedTextField(
                                        value = settings.discordButton2Url,
                                        onValueChange = { v -> scope.launch { appState.preferences.set(DesktopPrefKeys.DiscordButton2Url, v) } },
                                        label = { Text("URL Botón 2") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = settings.discordToken,
                                onValueChange = { v -> scope.launch { appState.preferences.set(DesktopPrefKeys.DiscordToken, v) } },
                                label = { Text("Token Discord Gateway (opcional si Discord desktop está abierto)") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            // Live Preview Card
                            val previewSong = appState.playbackState.value.currentSong
                            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("Vista previa Rich Presence", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    Text("${settings.discordActivityType} ${settings.discordActivityName}", fontWeight = FontWeight.Bold)
                                    Text(
                                        if (previewSong != null) appState.discordRpc.renderTemplate(settings.discordDetailsTemplate, previewSong) else settings.discordDetailsTemplate,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        if (previewSong != null) appState.discordRpc.renderTemplate(settings.discordStateTemplate, previewSong) else settings.discordStateTemplate,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    item {
                        SettingsCard("Last.fm Scrobbling") {
                            SettingsSwitch("Activar Scrobbling Last.fm", settings.enableLastFmScrobbling) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.EnableLastFMScrobbling, it) }
                            }
                            SettingsSwitch("Enviar 'Escuchando ahora' (Now Playing)", settings.lastFmUseNowPlaying) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.LastFMUseNowPlaying, it) }
                            }
                            SettingsSwitch("Sincronizar 'Me gusta' (Love/Unlove)", settings.lastFmUseSendLikes) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.LastFMUseSendLikes, it) }
                            }
                            Text("Duración mínima de canción: ${settings.scrobbleMinSongDurationSec} s", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = settings.scrobbleMinSongDurationSec.toFloat(),
                                onValueChange = { scope.launch { appState.preferences.set(DesktopPrefKeys.ScrobbleMinSongDuration, it.roundToInt()) } },
                                valueRange = 10f..60f,
                            )
                            Text("Porcentaje para scrobble: ${(settings.scrobbleDelayPercent * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = settings.scrobbleDelayPercent,
                                onValueChange = { scope.launch { appState.preferences.set(DesktopPrefKeys.ScrobbleDelayPercent, it) } },
                                valueRange = 0.3f..0.95f,
                            )
                            Text("Máximo retraso scrobble: ${settings.scrobbleDelaySeconds} s", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = settings.scrobbleDelaySeconds.toFloat(),
                                onValueChange = { scope.launch { appState.preferences.set(DesktopPrefKeys.ScrobbleDelaySeconds, it.roundToInt()) } },
                                valueRange = 30f..360f,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = settings.lastFmApiKey,
                                    onValueChange = { v -> scope.launch { appState.preferences.set(DesktopPrefKeys.LastFMApiKey, v) } },
                                    label = { Text("API Key") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = settings.lastFmSecret,
                                    onValueChange = { v -> scope.launch { appState.preferences.set(DesktopPrefKeys.LastFMSecret, v) } },
                                    label = { Text("API Secret") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (settings.lastFmSession.isNotBlank()) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Conectado como ${settings.lastFmUsername.ifBlank { "Usuario Last.fm" }}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                    OutlinedButton(onClick = {
                                        scope.launch {
                                            appState.preferences.set(DesktopPrefKeys.LastFMSession, "")
                                            appState.preferences.set(DesktopPrefKeys.LastFMUsername, "")
                                        }
                                    }) { Text("Cerrar sesión") }
                                }
                            } else {
                                var lfmUser by remember { mutableStateOf("") }
                                var lfmPass by remember { mutableStateOf("") }
                                var lfmBusy by remember { mutableStateOf(false) }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(value = lfmUser, onValueChange = { lfmUser = it }, label = { Text("Usuario") }, singleLine = true, modifier = Modifier.weight(1f))
                                    OutlinedTextField(value = lfmPass, onValueChange = { lfmPass = it }, label = { Text("Contraseña") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.weight(1f))
                                    Button(
                                        enabled = !lfmBusy && lfmUser.isNotBlank() && lfmPass.isNotBlank(),
                                        onClick = {
                                            lfmBusy = true
                                            scope.launch {
                                                val res = withContext(Dispatchers.IO) {
                                                    DesktopLastFmService.authenticateMobileSession(lfmUser, lfmPass, settings.lastFmApiKey, settings.lastFmSecret)
                                                }
                                                lfmBusy = false
                                                res.onSuccess { (name, sk) ->
                                                    appState.preferences.set(DesktopPrefKeys.LastFMUsername, name)
                                                    appState.preferences.set(DesktopPrefKeys.LastFMSession, sk)
                                                    appState.preferences.set(DesktopPrefKeys.EnableLastFMScrobbling, true)
                                                    appState.showStatus("Last.fm conectado como $name")
                                                }.onFailure { appState.showStatus("Error Last.fm: ${it.message}") }
                                            }
                                        },
                                    ) { Text(if (lfmBusy) "..." else "Conectar") }
                                }
                            }
                        }
                    }

                    item {
                        SettingsCard("Escuchar Juntos (Listen Together)") {
                            Text("Servidores predefinidos", style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                ListenTogetherPresetServers.servers.forEach { (name, url) ->
                                    FilterChip(
                                        selected = settings.listenTogetherServerUrl == url,
                                        onClick = { scope.launch { appState.preferences.set(DesktopPrefKeys.ListenTogetherServerUrl, url) } },
                                        label = { Text(name) },
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = settings.listenTogetherServerUrl,
                                onValueChange = { v -> scope.launch { appState.preferences.set(DesktopPrefKeys.ListenTogetherServerUrl, v) } },
                                label = { Text("Servidor WebSocket personalizado") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            SettingsSwitch("Aprobar solicitudes de unión automáticamente", settings.listenTogetherAutoApproval) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.ListenTogetherAutoApproval, it) }
                            }
                            SettingsSwitch("Aprobar sugerencias de canciones automáticamente", settings.listenTogetherAutoApproveSuggestions) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.ListenTogetherAutoApproveSuggestions, it) }
                            }
                            SettingsSwitch("Sincronizar volumen del host", settings.listenTogetherSyncVolume) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.ListenTogetherSyncVolume, it) }
                            }
                            if (settings.listenTogetherBlockedUsers.isNotEmpty()) {
                                Text("Usuarios bloqueados", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    settings.listenTogetherBlockedUsers.forEach { blocked ->
                                        FilterChip(
                                            selected = true,
                                            onClick = {
                                                val next = settings.listenTogetherBlockedUsers - blocked
                                                scope.launch { appState.preferences.set(DesktopPrefKeys.ListenTogetherBlockedUsers, next.joinToString(",")) }
                                            },
                                            label = { Text("$blocked ×") },
                                        )
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { appState.openListenTogetherDialog() }) { Text("Abrir sala") }
                                if (ltLogs.isNotEmpty()) {
                                    OutlinedButton(onClick = {
                                        copyTextToClipboard(ltLogs.joinToString("\n"))
                                        appState.showStatus("Logs de Listen Together copiados")
                                    }) { Text("Copiar logs (${ltLogs.size})") }
                                    TextButton(onClick = { appState.listenTogetherManager.clearLogs() }) { Text("Limpiar logs") }
                                }
                            }
                        }
                    }
                }

                SettingsSection.PRIVACY -> {
                    item {
                        SettingsCard("Privacidad e Historial") {
                            SettingsSwitch("Pausar historial de reproducción", settings.pauseListenHistory) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.PauseListenHistory, it) }
                            }
                            SettingsSwitch("Pausar historial de búsqueda", settings.pauseSearchHistory) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.PauseSearchHistory, it) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = {
                                    scope.launch(Dispatchers.IO) { appState.database.clearListenHistory() }
                                    appState.showStatus("Historial de reproducción borrado")
                                }) { Text("Borrar historial de escucha") }
                                OutlinedButton(onClick = {
                                    scope.launch(Dispatchers.IO) { appState.database.clearSearchHistory() }
                                    appState.showStatus("Historial de búsqueda borrado")
                                }) { Text("Borrar historial de búsqueda") }
                            }
                        }
                    }
                }

                SettingsSection.STORAGE -> {
                    item {
                        SettingsCard("Almacenamiento y Caché") {
                            Text("Descargas: ${formatMb(downloadsBytes)}", fontWeight = FontWeight.Medium)
                            Text("Caché de canciones: ${formatMb(songCacheBytes)} / ${if (settings.maxSongCacheSizeMb <= 0) "Ilimitado" else "${settings.maxSongCacheSizeMb} MB"}", fontWeight = FontWeight.Medium)
                            Text("Caché de imágenes: ${formatMb(imageCacheBytes)}", fontWeight = FontWeight.Medium)

                            SettingsSwitch("Activar caché local de canciones", settings.enableSongCache) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.EnableSongCache, it) }
                            }
                            Text("Tamaño máximo de caché de canciones", style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(256, 512, 1024, 2048, 4096, 8192, -1).forEach { mb ->
                                    FilterChip(
                                        selected = settings.maxSongCacheSizeMb == mb,
                                        onClick = {
                                            scope.launch {
                                                appState.preferences.set(DesktopPrefKeys.MaxSongCacheSize, mb)
                                                withContext(Dispatchers.IO) { appState.proxyServer.enforceCacheLimit() }
                                            }
                                        },
                                        label = { Text(if (mb < 0) "Ilimitado" else if (mb >= 1024) "${mb / 1024} GB" else "$mb MB") },
                                    )
                                }
                            }
                            HorizontalDivider()
                            Text("Carpeta de descargas: ${settings.downloadsDir}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    val chooser = JFileChooser(settings.downloadsDir).apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
                                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                        scope.launch { appState.preferences.set(DesktopPrefKeys.DownloadsDir, chooser.selectedFile.absolutePath) }
                                    }
                                }) { Text("Cambiar carpeta") }
                                OutlinedButton(onClick = {
                                    appState.clearSongCache()
                                    songCacheBytes = 0L
                                }) { Text("Limpiar caché de canciones") }
                                OutlinedButton(onClick = {
                                    appState.clearImageCache()
                                    imageCacheBytes = 0L
                                }) { Text("Limpiar caché de imágenes") }
                                OutlinedButton(onClick = {
                                    appState.downloadManager.clearAllDownloads()
                                    downloadsBytes = 0L
                                    appState.showStatus("Todas las descargas eliminadas")
                                }) { Text("Borrar descargas") }
                            }
                        }
                    }
                }

                SettingsSection.BACKUP -> {
                    item {
                        SettingsCard("Respaldo (.backup compatible con Android)") {
                            Text(
                                "Incluye biblioteca SQLite v38, preferencias DataStore y alias de artistas.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = {
                                    val chooser = JFileChooser().apply { selectedFile = File("miliMusic_backup.backup") }
                                    if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                                        val file = chooser.selectedFile
                                        scope.launch {
                                            appState.backupService.exportBackup(file)
                                                .onSuccess { appState.showStatus("Respaldo guardado en ${file.name}") }
                                                .onFailure { appState.showStatus("Error al exportar: ${it.message}") }
                                        }
                                    }
                                }) { Text("Exportar .backup") }

                                OutlinedButton(onClick = {
                                    val chooser = JFileChooser()
                                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                        val file = chooser.selectedFile
                                        scope.launch {
                                            appState.backupService.previewBackup(file)
                                                .onSuccess { prev -> backupPreviewFile = file to prev }
                                                .onFailure { appState.showStatus("Respaldo inválido: ${it.message}") }
                                        }
                                    }
                                }) { Text("Restaurar .backup") }
                            }
                        }
                    }

                    item {
                        SettingsCard("Importar playlists externas (M3U / CSV)") {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = {
                                    val chooser = JFileChooser().apply {
                                        fileFilter = FileNameExtensionFilter("Playlists M3U (*.m3u, *.m3u8)", "m3u", "m3u8")
                                    }
                                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                        val file = chooser.selectedFile
                                        scope.launch {
                                            appState.showStatus("Importando M3U: ${file.name}...")
                                            val content = withContext(Dispatchers.IO) { file.readText(Charsets.UTF_8) }
                                            val candidates = PlaylistTransferService.parseM3u(content)
                                            val songs = PlaylistTransferService.resolveCandidatesOnline(candidates)
                                            if (songs.isNotEmpty()) {
                                                val pl = withContext(Dispatchers.IO) { appState.database.createPlaylist(file.nameWithoutExtension) }
                                                withContext(Dispatchers.IO) { songs.forEach { appState.database.addSongToPlaylistAt(pl.id, it) } }
                                                appState.showStatus("M3U importada: ${songs.size}/${candidates.size} canciones en \"${pl.name}\"")
                                            } else {
                                                appState.showStatus("No se encontraron canciones válidas en el M3U")
                                            }
                                        }
                                    }
                                }) { Text("Importar M3U") }

                                OutlinedButton(onClick = {
                                    val chooser = JFileChooser().apply {
                                        fileFilter = FileNameExtensionFilter("Archivos CSV (*.csv)", "csv")
                                    }
                                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                        csvImportFile = chooser.selectedFile
                                    }
                                }) { Text("Importar CSV (mapear columnas)") }
                            }
                        }
                    }
                }

                SettingsSection.DESKTOP -> {
                    item {
                        SettingsCard("Integración Windows") {
                            SettingsSwitch("Minimizar a la bandeja del sistema al cerrar", settings.minimizeToTray) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.MinimizeToTray, it) }
                            }
                            SettingsSwitch("Controles multimedia nativos de Windows (SMTC)", settings.enableSmtc) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.EnableSmtc, it) }
                            }
                            SettingsSwitch("Sincronización automática con YouTube Music", settings.ytmSync) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.YtmSync, it) }
                            }
                        }
                    }

                    item {
                        SettingsCard("Atajos de teclado") {
                            val shortcuts = listOf(
                                "Espacio" to "Reproducir / Pausar",
                                "Ctrl + F" to "Ir a Buscar",
                                "Ctrl + R" to "Reconocer música (Shazam)",
                                "Ctrl + L" to "Mostrar / Ocultar panel de Letras",
                                "Ctrl + Q" to "Mostrar / Ocultar Cola de reproducción",
                                "Ctrl + E" to "Abrir Ecualizador, AutoEQ, Velocidad y Tono",
                                "Ctrl + Shift + T" to "Abrir Temporizador de apagado",
                                "Ctrl + Derecha / Izquierda" to "Siguiente / Anterior canción",
                                "Ctrl + Arriba / Abajo" to "Subir / Bajar volumen",
                                "Ctrl + M" to "Silenciar / Activar sonido",
                                "Alt + Izquierda / Derecha" to "Navegar Atrás / Adelante",
                            )
                            shortcuts.forEach { (key, desc) ->
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(key, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                SettingsSection.ABOUT -> {
                    item {
                        SettingsCard("Actualizaciones y Novedades") {
                            Text("Versión instalada: miliMusic Desktop v${DesktopUpdater.CURRENT_VERSION}", fontWeight = FontWeight.Bold)
                            if (updateAvailable && latestRelease != null) {
                                Text("Nueva versión disponible: v${latestRelease!!.displayVersion}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            }
                            SettingsSwitch("Comprobar actualizaciones al iniciar", settings.checkForUpdates) {
                                scope.launch { appState.preferences.set(DesktopPrefKeys.CheckForUpdates, it) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { appState.checkForUpdatesSilently(force = true) }) { Text("Buscar actualizaciones") }
                                OutlinedButton(onClick = { appState.openChangelogDialog() }) { Text("Ver Novedades (Changelog)") }
                            }
                        }
                    }
                    item {
                        SettingsCard("Acerca de miliMusic") {
                            Text("miliMusic — Cliente nativo de YouTube Music para Android y Windows.", fontWeight = FontWeight.Medium)
                            Text(
                                "Basado en Metrolist • Compose Multiplatform Material 3 • Motor de audio libVLC • Licencia GPL-3.0.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = { openExternalUrl("https://github.com/angelvalentin-dev/miliMusic") }) {
                                    Text("Repositorio en GitHub")
                                }
                                OutlinedButton(onClick = { appState.openLoginDialog() }) {
                                    Text(if (settings.isLoggedIn) "Gestionar cuenta YTM" else "Iniciar sesión YTM")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (addAlarmOpen) {
        var hourStr by remember { mutableStateOf("07") }
        var minStr by remember { mutableStateOf("00") }
        var label by remember { mutableStateOf("") }
        var randomSong by remember { mutableStateOf(false) }
        var volumeRamp by remember { mutableStateOf(true) }
        var playlistChoice by remember { mutableStateOf("TOP") }
        var selectedDays by remember { mutableStateOf(setOf(1, 2, 3, 4, 5, 6, 7)) }

        AlertDialog(
            onDismissRequest = { addAlarmOpen = false },
            title = { Text("Nueva alarma musical") },
            text = {
                Column(modifier = Modifier.width(440.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = hourStr, onValueChange = { hourStr = it.filter(Char::isDigit).take(2) }, label = { Text("Hora (0-23)") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = minStr, onValueChange = { minStr = it.filter(Char::isDigit).take(2) }, label = { Text("Minuto (0-59)") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Etiqueta (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Días de la semana", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(1 to "L", 2 to "M", 3 to "X", 4 to "J", 5 to "V", 6 to "S", 7 to "D").forEach { (d, lbl) ->
                            val on = d in selectedDays
                            FilterChip(
                                selected = on,
                                onClick = { selectedDays = if (on) selectedDays - d else selectedDays + d },
                                label = { Text(lbl) },
                            )
                        }
                    }
                    Text("Origen de canciones", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = playlistChoice == "TOP", onClick = { playlistChoice = "TOP" }, label = { Text("Mi Top") })
                        FilterChip(selected = playlistChoice == "LIKED", onClick = { playlistChoice = "LIKED" }, label = { Text("Favoritas") })
                        FilterChip(selected = playlistChoice == "DOWNLOADED", onClick = { playlistChoice = "DOWNLOADED" }, label = { Text("Descargas") })
                        playlists.take(5).forEach { pl ->
                            FilterChip(selected = playlistChoice == pl.id, onClick = { playlistChoice = pl.id }, label = { Text(pl.name) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = randomSong, onCheckedChange = { randomSong = it })
                        Text("Canción aleatoria")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = volumeRamp, onCheckedChange = { volumeRamp = it })
                        Text("Rampa progresiva de volumen")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val h = (hourStr.toIntOrNull() ?: 7).coerceIn(0, 23)
                    val m = (minStr.toIntOrNull() ?: 0).coerceIn(0, 59)
                    val entry = DesktopAlarmEntry(
                        id = UUID.randomUUID().toString(),
                        enabled = true,
                        hour = h,
                        minute = m,
                        playlistId = playlistChoice,
                        randomSong = randomSong,
                        daysOfWeek = selectedDays.sorted().ifEmpty { listOf(1, 2, 3, 4, 5, 6, 7) },
                        label = label.trim(),
                        volumeRamp = volumeRamp,
                    )
                    appState.saveAlarms(alarms + entry)
                    addAlarmOpen = false
                }) { Text("Guardar alarma") }
            },
            dismissButton = { TextButton(onClick = { addAlarmOpen = false }) { Text("Cancelar") } },
        )
    }

    csvImportFile?.let { file ->
        CsvImportDialog(file = file, appState = appState, onDismiss = { csvImportFile = null })
    }

    backupPreviewFile?.let { (file, prev) ->
        AlertDialog(
            onDismissRequest = { backupPreviewFile = null },
            title = { Text("Confirmar restauración de respaldo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Archivo: ${file.name}", fontWeight = FontWeight.SemiBold)
                    Text("Versión esquema DB: v${prev.dbVersion} (${if (prev.compatible) "Compatible" else "Incompatible"})")
                    Text("Canciones: ${prev.songCount} • Playlists: ${prev.playlistCount}")
                    Text("Preferencias incluidas: ${if (prev.hasSettings) "Sí" else "No"} • Alias de artistas: ${if (prev.hasAliases) "Sí" else "No"}")
                }
            },
            confirmButton = {
                Button(
                    enabled = prev.compatible,
                    onClick = {
                        val target = file
                        backupPreviewFile = null
                        scope.launch {
                            appState.backupService.importBackup(target)
                                .onSuccess { appState.showStatus("Respaldo restaurado correctamente") }
                                .onFailure { appState.showStatus("Error al restaurar: ${it.message}") }
                        }
                    },
                ) { Text("Restaurar ahora") }
            },
            dismissButton = { TextButton(onClick = { backupPreviewFile = null }) { Text("Cancelar") } },
        )
    }
}

private fun formatMb(bytes: Long): String = "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
    }
}

@Composable
private fun SettingsSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics {
                contentDescription = title
                stateDescription = if (checked) "Activado" else "Desactivado"
            },
        )
    }
}
