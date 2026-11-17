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
import com.metrolist.desktop.ui.icons.Download
import com.metrolist.desktop.ui.icons.Explore
import com.metrolist.desktop.ui.icons.LibraryMusic
import com.metrolist.desktop.ui.icons.QueueMusic
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
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
import com.metrolist.desktop.state.DesktopAppState
import com.metrolist.desktop.state.NavDestination
import com.metrolist.desktop.ui.components.AddToPlaylistDialog
import com.metrolist.desktop.ui.components.DesktopPlayerBar
import com.metrolist.desktop.ui.components.FullPlayerOverlay
import com.metrolist.desktop.ui.components.LoginDialog
import com.metrolist.desktop.ui.components.RightSidePanel
import com.metrolist.desktop.ui.screens.AlbumScreen
import com.metrolist.desktop.ui.screens.ArtistScreen
import com.metrolist.desktop.ui.screens.BrowseScreen
import com.metrolist.desktop.ui.screens.ExploreScreen
import com.metrolist.desktop.ui.screens.HomeScreen
import com.metrolist.desktop.ui.screens.LibraryScreen
import com.metrolist.desktop.ui.screens.PlaylistScreen
import com.metrolist.desktop.ui.screens.PodcastScreen
import com.metrolist.desktop.ui.screens.SearchScreen
import com.metrolist.desktop.ui.screens.SettingsScreen
import com.metrolist.desktop.ui.theme.DefaultThemeColor
import com.metrolist.desktop.ui.theme.MiliMusicDesktopTheme
import com.metrolist.desktop.ui.theme.rememberArtworkSeedColor
import okio.Path.Companion.toOkioPath
import java.awt.SystemTray
import javax.imageio.ImageIO
import androidx.compose.ui.graphics.toComposeImageBitmap

fun main() = application {
    val appState = remember { DesktopAppState() }
    val settings by appState.settings.collectAsState()
    val pb by appState.playbackState.collectAsState()

    var windowVisible by remember { mutableStateOf(true) }
    val windowState = remember { WindowState(size = DpSize(1280.dp, 820.dp)) }

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
            tooltip = pb.currentSong?.let { "miliMusic — ${it.title}" } ?: "miliMusic",
            onAction = { windowVisible = true },
            menu = {
                Item("Mostrar miliMusic", onClick = { windowVisible = true })
                Separator()
                Item(
                    if (pb.isPlaying) "Pausar" else "Reproducir",
                    enabled = pb.currentSong != null,
                    onClick = { appState.audioPlayer.togglePlayPause() },
                )
                Item("Siguiente", enabled = pb.currentSong != null, onClick = { appState.audioPlayer.next() })
                Item("Anterior", enabled = pb.currentSong != null, onClick = { appState.audioPlayer.previous() })
                Separator()
                Item("Salir", onClick = {
                    appState.shutdown()
                    exitApplication()
                })
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
                appState.shutdown()
                exitApplication()
            }
        },
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown) {
                when {
                    event.isCtrlPressed && event.key == Key.F -> {
                        appState.navigate(NavDestination.Search())
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
            if (event.type == KeyEventType.KeyDown &&
                event.key == Key.Spacebar &&
                !event.isCtrlPressed &&
                !event.isAltPressed
            ) {
                appState.audioPlayer.togglePlayPause()
                true
            } else {
                false
            }
        },
    ) {
        window.minimumSize = java.awt.Dimension(960, 640)

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
            thumbnailUrl = pb.currentSong?.thumbnailUrl,
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

@Composable
private fun DesktopShell(appState: DesktopAppState) {
    val currentDest by appState.currentDestination.collectAsState()
    val canGoBack by appState.canGoBack.collectAsState()
    val canGoForward by appState.canGoForward.collectAsState()
    val settings by appState.settings.collectAsState()
    val playlists by appState.playlists.collectAsState()
    val statusMsg by appState.statusMessage.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(statusMsg) {
        val msg = statusMsg ?: return@LaunchedEffect
        snackbarHost.showSnackbar(msg)
        appState.clearStatus()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = { DesktopPlayerBar(appState) },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Left sidebar
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.width(236.dp).fillMaxHeight(),
                ) {
                    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = "miliMusic",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        NavigationDrawerItem(
                            selected = currentDest is NavDestination.Home,
                            onClick = { appState.navigate(NavDestination.Home) },
                            icon = { Icon(Icons.Rounded.Home, null) },
                            label = { Text("Inicio") },
                        )
                        NavigationDrawerItem(
                            selected = currentDest is NavDestination.Explore,
                            onClick = { appState.navigate(NavDestination.Explore) },
                            icon = { Icon(Icons.Rounded.Explore, null) },
                            label = { Text("Explorar") },
                        )
                        NavigationDrawerItem(
                            selected = currentDest is NavDestination.Search,
                            onClick = { appState.navigate(NavDestination.Search()) },
                            icon = { Icon(Icons.Rounded.Search, null) },
                            label = { Text("Buscar") },
                        )
                        NavigationDrawerItem(
                            selected = currentDest is NavDestination.Library && (currentDest as NavDestination.Library).initialTab != 5,
                            onClick = { appState.navigate(NavDestination.Library(0)) },
                            icon = { Icon(Icons.Rounded.LibraryMusic, null) },
                            label = { Text("Biblioteca") },
                        )
                        NavigationDrawerItem(
                            selected = currentDest is NavDestination.Library && (currentDest as NavDestination.Library).initialTab == 5,
                            onClick = { appState.navigate(NavDestination.Library(5)) },
                            icon = { Icon(Icons.Rounded.Download, null) },
                            label = { Text("Descargas") },
                        )
                        NavigationDrawerItem(
                            selected = currentDest is NavDestination.Settings,
                            onClick = { appState.navigate(NavDestination.Settings) },
                            icon = { Icon(Icons.Rounded.Settings, null) },
                            label = { Text("Ajustes") },
                        )

                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Spacer(Modifier.height(10.dp))

                        Text(
                            text = "PLAYLISTS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(playlists) { pl ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            appState.navigate(NavDestination.Playlist(pl.id, isLocal = pl.isLocal))
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.QueueMusic,
                                        null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = pl.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                // Vertical divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                )

                // Center content + top bar
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
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
                                IconButton(enabled = canGoBack, onClick = appState::goBack) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Atrás")
                                }
                                IconButton(enabled = canGoForward, onClick = appState::goForward) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Adelante")
                                }
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier
                                        .width(340.dp)
                                        .height(38.dp)
                                        .clip(RoundedCornerShape(20.dp))
                                        .clickable { appState.navigate(NavDestination.Search()) },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Rounded.Search,
                                            null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = "Buscar música... (Ctrl+F)",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }

                            if (settings.isLoggedIn) {
                                OutlinedButton(onClick = { appState.navigate(NavDestination.Settings) }) {
                                    Icon(Icons.Rounded.Person, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(settings.accountName.ifBlank { "Cuenta YTM" }, maxLines = 1)
                                }
                            } else {
                                OutlinedButton(onClick = appState::openLoginDialog) {
                                    Icon(Icons.Rounded.Person, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Iniciar sesión")
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (val d = currentDest) {
                            NavDestination.Home -> HomeScreen(appState)
                            NavDestination.Explore -> ExploreScreen(appState)
                            is NavDestination.Search -> SearchScreen(d.query, appState)
                            is NavDestination.Library -> LibraryScreen(d.initialTab, appState)
                            is NavDestination.Artist -> ArtistScreen(d.artistId, appState)
                            is NavDestination.Album -> AlbumScreen(d.albumId, appState)
                            is NavDestination.Playlist -> PlaylistScreen(d.playlistId, d.isLocal, appState)
                            is NavDestination.Podcast -> PodcastScreen(d.podcastId, appState)
                            is NavDestination.Browse -> BrowseScreen(d.browseId, d.params, d.title, appState)
                            NavDestination.Settings -> SettingsScreen(appState)
                        }
                    }
                }

                // Right side panel (Lyrics or Queue)
                RightSidePanel(appState)
            }

            FullPlayerOverlay(appState)
        }
    }

    AddToPlaylistDialog(appState)
    LoginDialog(appState)
}
