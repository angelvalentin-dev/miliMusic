package com.metrolist.desktop

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.metrolist.desktop.data.DesktopPrefKeys
import com.metrolist.desktop.state.DesktopAppState
import com.metrolist.desktop.state.NavDestination
import com.metrolist.desktop.ui.components.AddToPlaylistDialog
import com.metrolist.desktop.ui.components.ChangelogAndUpdateDialog
import com.metrolist.desktop.ui.components.DesktopPlayerBar
import com.metrolist.desktop.ui.components.FullPlayerOverlay
import com.metrolist.desktop.ui.components.LoginDialog
import com.metrolist.desktop.ui.components.LyricsToolsDialog
import com.metrolist.desktop.ui.components.RightSidePanel
import com.metrolist.desktop.ui.components.SongDetailsDialog
import com.metrolist.desktop.ui.components.SongMetadataEditDialog
import com.metrolist.desktop.ui.components.TimeTransferDialog
import com.metrolist.desktop.ui.icons.Download
import com.metrolist.desktop.ui.icons.Explore
import com.metrolist.desktop.ui.icons.History
import com.metrolist.desktop.ui.icons.LibraryMusic
import com.metrolist.desktop.ui.icons.MusicNote
import com.metrolist.desktop.ui.icons.QueueMusic
import com.metrolist.desktop.ui.screens.AlbumScreen
import com.metrolist.desktop.ui.screens.ArtistScreen
import com.metrolist.desktop.ui.screens.BrowseScreen
import com.metrolist.desktop.ui.screens.EqualizerAndSpeedDialog
import com.metrolist.desktop.ui.screens.ExploreScreen
import com.metrolist.desktop.ui.screens.HomeScreen
import com.metrolist.desktop.ui.screens.LibraryScreen
import com.metrolist.desktop.ui.screens.ListenTogetherDialog
import com.metrolist.desktop.ui.screens.PlaylistScreen
import com.metrolist.desktop.ui.screens.PodcastScreen
import com.metrolist.desktop.ui.screens.RecognitionScreen
import com.metrolist.desktop.ui.screens.SearchScreen
import com.metrolist.desktop.ui.screens.SettingsScreen
import com.metrolist.desktop.ui.screens.SleepTimerDialog
import com.metrolist.desktop.ui.screens.StatsScreen
import com.metrolist.desktop.ui.screens.WrappedDialog
import com.metrolist.desktop.ui.theme.DefaultThemeColor
import com.metrolist.desktop.ui.theme.MiliMusicDesktopTheme
import com.metrolist.desktop.ui.theme.rememberArtworkSeedColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath
import java.awt.Frame
import java.awt.SystemTray
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

private data class TopLevelPlaybackSummary(
    val title: String? = null,
    val thumbnailUrl: String? = null,
    val isPlaying: Boolean = false,
    val hasSong: Boolean = false,
)

fun main() {
    val instanceGuard = SingleInstanceGuard.tryAcquire() ?: exitProcess(0)
    val appState = DesktopAppState()
    val shutdownHook = Thread({
        appState.shutdown()
        instanceGuard.close()
    }, "miliMusic-shutdown-hook")
    runCatching { Runtime.getRuntime().addShutdownHook(shutdownHook) }

    try {
        application(exitProcessOnExit = false) {
            val settings by appState.settings.collectAsState()
            val playbackSummary by remember(appState) {
                appState.playbackState
                    .map { pb ->
                        TopLevelPlaybackSummary(
                            title = pb.currentSong?.title,
                            thumbnailUrl = pb.currentSong?.thumbnailUrl,
                            isPlaying = pb.isPlaying,
                            hasSong = pb.currentSong != null,
                        )
                    }
                    .distinctUntilChanged()
            }.collectAsState(initial = TopLevelPlaybackSummary())

            var windowVisible by remember { mutableStateOf(true) }
            val windowState = remember { WindowState(size = DpSize(1280.dp, 820.dp)) }
            var awtWindowRef by remember { mutableStateOf<ComposeWindow?>(null) }

            fun restoreWindowToFront() {
                SwingUtilities.invokeLater {
                    windowVisible = true
                    windowState.isMinimized = false
                    awtWindowRef?.let { win ->
                        win.isVisible = true
                        if ((win.extendedState and Frame.ICONIFIED) != 0) {
                            win.extendedState = win.extendedState and Frame.ICONIFIED.inv()
                        }
                        win.toFront()
                        win.requestFocus()
                    }
                }
            }

            // Apply saved window size once settings load & persist resize changes debounced
            LaunchedEffect(settings.windowWidth, settings.windowHeight) {
                if (windowState.size.width.value.toInt() == 1280 && windowState.size.height.value.toInt() == 820) {
                    if (settings.windowWidth != 1280 || settings.windowHeight != 820) {
                        windowState.size = DpSize(settings.windowWidth.dp, settings.windowHeight.dp)
                    }
                }
            }
            LaunchedEffect(windowState.size) {
                delay(1_200L)
                val w = windowState.size.width.value.toInt().coerceIn(960, 3840)
                val h = windowState.size.height.value.toInt().coerceIn(640, 2160)
                if (w != settings.windowWidth) appState.scope.launch { appState.preferences.set(DesktopPrefKeys.WindowWidth, w) }
                if (h != settings.windowHeight) appState.scope.launch { appState.preferences.set(DesktopPrefKeys.WindowHeight, h) }
            }

            LaunchedEffect(instanceGuard) {
                instanceGuard.setOnActivate { restoreWindowToFront() }
            }

            fun requestExit() {
                windowVisible = false
                exitApplication()
            }

            val iconPainter = remember {
                runCatching {
                    DesktopAppState::class.java.classLoader
                        .getResourceAsStream("icons/milimusic.png")
                        ?.use { ImageIO.read(it) }
                        ?.toComposeImageBitmap()
                        ?.let { BitmapPainter(it) }
                }.getOrNull()
            }

            if (SystemTray.isSupported() && iconPainter != null) {
                Tray(
                    icon = iconPainter,
                    tooltip = playbackSummary.title?.let { "miliMusic — $it" } ?: "miliMusic",
                    onAction = { restoreWindowToFront() },
                    menu = {
                        Item("Mostrar miliMusic", onClick = { restoreWindowToFront() })
                        Separator()
                        Item(
                            if (playbackSummary.isPlaying) "Pausar" else "Reproducir",
                            enabled = playbackSummary.hasSong,
                            onClick = { appState.audioPlayer.togglePlayPause() },
                        )
                        Item("Siguiente", enabled = playbackSummary.hasSong, onClick = { appState.audioPlayer.next() })
                        Item("Anterior", enabled = playbackSummary.hasSong, onClick = { appState.audioPlayer.previous() })
                        Separator()
                        Item("Salir", onClick = { requestExit() })
                    },
                )
            }

            Window(
                visible = windowVisible,
                state = windowState,
                title = "miliMusic",
                icon = iconPainter,
                onCloseRequest = {
                    if (settings.minimizeToTray && SystemTray.isSupported()) {
                        windowVisible = false
                    } else {
                        requestExit()
                    }
                },
                onPreviewKeyEvent = { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when {
                            event.isCtrlPressed && event.key == Key.F -> {
                                appState.navigate(NavDestination.Search())
                                true
                            }
                            event.isCtrlPressed && event.key == Key.R -> {
                                appState.navigate(NavDestination.Recognition)
                                true
                            }
                            event.isCtrlPressed && event.key == Key.L -> {
                                appState.toggleLyricsPanel()
                                true
                            }
                            event.isCtrlPressed && event.key == Key.Q -> {
                                appState.toggleQueuePanel()
                                true
                            }
                            event.isCtrlPressed && event.key == Key.E -> {
                                appState.openEqualizerDialog()
                                true
                            }
                            event.isCtrlPressed && event.isShiftPressed && event.key == Key.T -> {
                                appState.openSleepTimerDialog()
                                true
                            }
                            event.isCtrlPressed && event.key == Key.M -> {
                                appState.audioPlayer.toggleMute()
                                true
                            }
                            event.isCtrlPressed && event.key == Key.DirectionUp -> {
                                appState.audioPlayer.setVolume((appState.playbackState.value.volume + 0.05f).coerceAtMost(1f))
                                true
                            }
                            event.isCtrlPressed && event.key == Key.DirectionDown -> {
                                appState.audioPlayer.setVolume((appState.playbackState.value.volume - 0.05f).coerceAtLeast(0f))
                                true
                            }
                            event.isCtrlPressed && event.key == Key.DirectionRight -> {
                                appState.audioPlayer.next()
                                true
                            }
                            event.isCtrlPressed && event.key == Key.DirectionLeft -> {
                                appState.audioPlayer.previous()
                                true
                            }
                            event.isAltPressed && event.key == Key.DirectionLeft -> {
                                appState.goBack()
                                true
                            }
                            event.isAltPressed && event.key == Key.DirectionRight -> {
                                appState.goForward()
                                true
                            }
                            else -> false
                        }
                    } else false
                },
                onKeyEvent = { event ->
                    val dest = appState.currentDestination.value
                    val allowSpaceShortcut = !appState.hasOpenDialog &&
                        dest !is NavDestination.Search &&
                        dest !is NavDestination.Settings
                    if (allowSpaceShortcut &&
                        event.type == KeyEventType.KeyDown &&
                        event.key == Key.Spacebar &&
                        !event.isCtrlPressed &&
                        !event.isAltPressed
                    ) {
                        appState.audioPlayer.togglePlayPause()
                        true
                    } else false
                },
            ) {
                LaunchedEffect(window) {
                    awtWindowRef = window
                    window.minimumSize = java.awt.Dimension(960, 640)
                }

                setSingletonImageLoaderFactory { context ->
                    ImageLoader.Builder(context)
                        .components { add(OkHttpNetworkFetcherFactory()) }
                        .diskCache {
                            DiskCache.Builder()
                                .directory(AppPaths.imageCacheDir.toOkioPath())
                                .maxSizeBytes(256L * 1024L * 1024L)
                                .build()
                        }
                        .build()
                }

                val fallbackColor = Color(settings.selectedThemeColor)
                val seedColor = rememberArtworkSeedColor(
                    thumbnailUrl = playbackSummary.thumbnailUrl,
                    fallback = if (settings.selectedThemeColor == 0) DefaultThemeColor else fallbackColor,
                    enabled = settings.dynamicTheme,
                )

                MiliMusicDesktopTheme(
                    darkMode = settings.darkMode,
                    pureBlack = settings.pureBlack,
                    seedColor = seedColor,
                ) {
                    DesktopShell(appState)
                }
            }
        }
    } finally {
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        val cleanupThread = Thread({
            appState.shutdown()
            instanceGuard.close()
        }, "miliMusic-exit-cleanup").apply {
            isDaemon = true
            start()
        }
        runCatching { cleanupThread.join(3_000L) }
    }
    exitProcess(0)
}

@Composable
private fun DesktopShell(appState: DesktopAppState) {
    val currentDest by appState.currentDestination.collectAsState()
    val canGoBack by appState.canGoBack.collectAsState()
    val canGoForward by appState.canGoForward.collectAsState()
    val settings by appState.settings.collectAsState()
    val playlists by appState.playlists.collectAsState()
    val statusMsg by appState.statusMessage.collectAsState()
    val updateAvailable by appState.updateAvailable.collectAsState()
    val playbackError by remember(appState) {
        appState.playbackState.map { it.errorMessage }.distinctUntilChanged()
    }.collectAsState(initial = null)
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(statusMsg) {
        val msg = statusMsg ?: return@LaunchedEffect
        snackbarHost.showSnackbar(msg)
        appState.clearStatus()
    }

    LaunchedEffect(playbackError) {
        val err = playbackError ?: return@LaunchedEffect
        snackbarHost.showSnackbar(err)
    }

    Scaffold(
        snackbarHost = {
            Box(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                SnackbarHost(snackbarHost)
            }
        },
        bottomBar = { DesktopPlayerBar(appState) },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Left Sidebar
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .width(236.dp)
                        .fillMaxHeight()
                        .semantics { contentDescription = "Barra de navegación lateral" },
                ) {
                    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("M", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    "miliMusic",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.semantics { heading() },
                                )
                                Text("Windows Desktop", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Spacer(Modifier.height(6.dp))
                        NavigationDrawerItem(
                            label = { Text("Inicio") },
                            icon = { Icon(Icons.Rounded.Home, null) },
                            selected = currentDest is NavDestination.Home,
                            onClick = { appState.navigate(NavDestination.Home) },
                        )
                        NavigationDrawerItem(
                            label = { Text("Explorar") },
                            icon = { Icon(Icons.Rounded.Explore, null) },
                            selected = currentDest is NavDestination.Explore,
                            onClick = { appState.navigate(NavDestination.Explore) },
                        )
                        NavigationDrawerItem(
                            label = { Text("Buscar") },
                            icon = { Icon(Icons.Rounded.Search, null) },
                            selected = currentDest is NavDestination.Search,
                            onClick = { appState.navigate(NavDestination.Search()) },
                        )
                        NavigationDrawerItem(
                            label = { Text("Reconocer") },
                            icon = { Icon(Icons.Rounded.MusicNote, null) },
                            selected = currentDest is NavDestination.Recognition,
                            onClick = { appState.navigate(NavDestination.Recognition) },
                        )
                        NavigationDrawerItem(
                            label = { Text("Biblioteca") },
                            icon = { Icon(Icons.Rounded.LibraryMusic, null) },
                            selected = currentDest is NavDestination.Library && (currentDest as NavDestination.Library).initialTab != 8,
                            onClick = { appState.navigate(NavDestination.Library(0)) },
                        )
                        NavigationDrawerItem(
                            label = { Text("Descargas") },
                            icon = { Icon(Icons.Rounded.Download, null) },
                            selected = currentDest is NavDestination.Library && (currentDest as NavDestination.Library).initialTab == 8,
                            onClick = { appState.navigate(NavDestination.Library(8)) },
                        )
                        NavigationDrawerItem(
                            label = { Text("Estadísticas") },
                            icon = { Icon(Icons.Rounded.History, null) },
                            selected = currentDest is NavDestination.Stats,
                            onClick = { appState.navigate(NavDestination.Stats) },
                        )
                        NavigationDrawerItem(
                            label = { Text("Ajustes") },
                            icon = { Icon(Icons.Rounded.Settings, null) },
                            selected = currentDest is NavDestination.Settings,
                            onClick = { appState.navigate(NavDestination.Settings) },
                        )

                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Playlists",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).semantics { heading() },
                        )
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(playlists) { pl ->
                                val selected = currentDest is NavDestination.Playlist && (currentDest as NavDestination.Playlist).playlistId == pl.id
                                NavigationDrawerItem(
                                    label = { Text(pl.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    icon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, modifier = Modifier.size(18.dp)) },
                                    selected = selected,
                                    onClick = { appState.navigate(NavDestination.Playlist(pl.id, isLocal = pl.isLocal)) },
                                )
                            }
                        }
                    }
                }

                Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)))

                // Main content column
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    // Top Bar
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                IconButton(onClick = appState::goBack, enabled = canGoBack) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Atrás (Alt+Izquierda)")
                                }
                                IconButton(onClick = appState::goForward, enabled = canGoForward) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Adelante (Alt+Derecha)")
                                }
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(24.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier
                                        .width(300.dp)
                                        .height(38.dp)
                                        .clip(RoundedCornerShape(24.dp))
                                        .semantics(mergeDescendants = true) {
                                            contentDescription = "Buscar canciones, álbumes o artistas (Ctrl+F)"
                                        }
                                        .clickable(role = Role.Button, onClickLabel = "Abrir búsqueda") {
                                            appState.navigate(NavDestination.Search())
                                        },
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "Buscar canciones, álbumes, enlaces... (Ctrl+F)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }

                            val ltState by appState.listenTogetherManager.state.collectAsState()
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (updateAvailable) {
                                    OutlinedButton(onClick = appState::openChangelogDialog) {
                                        Text("Actualización")
                                    }
                                }
                                OutlinedButton(onClick = appState::openListenTogetherDialog) {
                                    Text(if (ltState.roomCode.isNotBlank()) "Sala ${ltState.roomCode}" else "Escuchar Juntos")
                                }
                                OutlinedButton(onClick = appState::openWrappedDialog) {
                                    Text("Wrapped")
                                }
                                OutlinedButton(onClick = appState::openLoginDialog) {
                                    Icon(Icons.Rounded.Person, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (settings.isLoggedIn) settings.accountName.ifBlank { "Mi cuenta" } else "Iniciar sesión")
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (val dest = currentDest) {
                            NavDestination.Home -> HomeScreen(appState)
                            NavDestination.Explore -> ExploreScreen(appState)
                            is NavDestination.Search -> SearchScreen(dest.query, appState)
                            is NavDestination.Library -> LibraryScreen(dest.initialTab, appState)
                            NavDestination.Stats -> StatsScreen(appState)
                            NavDestination.Recognition -> RecognitionScreen(appState)
                            is NavDestination.Artist -> ArtistScreen(dest.artistId, appState)
                            is NavDestination.Album -> AlbumScreen(dest.albumId, appState)
                            is NavDestination.Playlist -> PlaylistScreen(dest.playlistId, dest.isLocal, appState)
                            is NavDestination.Podcast -> PodcastScreen(dest.podcastId, appState)
                            is NavDestination.Browse -> BrowseScreen(dest.browseId, dest.params, dest.title, appState)
                            NavDestination.Settings -> SettingsScreen(appState)
                        }
                    }
                }

                // Right collapsible Lyrics / Queue panel
                RightSidePanel(appState)
            }

            // Full Player Overlay
            FullPlayerOverlay(appState)
        }
    }

    // All global dialogs
    AddToPlaylistDialog(appState)
    LoginDialog(appState)
    EqualizerAndSpeedDialog(appState)
    SleepTimerDialog(appState)
    ListenTogetherDialog(appState)
    WrappedDialog(appState)
    SongDetailsDialog(appState)
    SongMetadataEditDialog(appState)
    LyricsToolsDialog(appState)
    TimeTransferDialog(appState)
    ChangelogAndUpdateDialog(appState)
}
