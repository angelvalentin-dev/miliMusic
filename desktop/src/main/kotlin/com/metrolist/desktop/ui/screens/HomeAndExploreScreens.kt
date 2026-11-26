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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.data.DesktopQuickPicksMode
import com.metrolist.desktop.data.DesktopSettings
import com.metrolist.desktop.db.DesktopDatabase
import com.metrolist.desktop.db.DesktopSong
import com.metrolist.desktop.db.DesktopSpeedDialItem
import com.metrolist.desktop.db.toDesktopSong
import com.metrolist.desktop.state.DesktopAppState
import com.metrolist.desktop.state.NavDestination
import com.metrolist.desktop.ui.components.ArtworkImage
import com.metrolist.desktop.ui.components.SongRowItem
import com.metrolist.desktop.ui.components.YTItemCard
import com.metrolist.desktop.ui.icons.History
import com.metrolist.desktop.ui.icons.MusicNote
import com.metrolist.desktop.ui.icons.Shuffle
import com.metrolist.desktop.util.ParsedYouTubeLink
import com.metrolist.desktop.util.YouTubeUrlParser
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.pages.ChartsPage
import com.metrolist.innertube.pages.ExplorePage
import com.metrolist.innertube.pages.HomePage
import com.metrolist.innertube.pages.SearchSummaryPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun filterContentItem(item: YTItem, settings: DesktopSettings): Boolean {
    if (settings.hideExplicit && item.explicit) return false
    if (settings.hideVideoSongs && item is SongItem && item.isVideoSong) return false
    if (settings.hideYoutubeShorts && item is SongItem && (item.duration in 1..60) && item.isVideoSong) return false
    return true
}

@Composable
fun HomeScreen(appState: DesktopAppState) {
    val settings by appState.settings.collectAsState()
    val recentlyPlayed by appState.recentlyPlayed.collectAsState()
    val speedDial by appState.speedDialItems.collectAsState()
    val playlists by appState.playlists.collectAsState()
    val pb by appState.playbackState.collectAsState()
    val scope = rememberCoroutineScope()

    var homePage by remember { mutableStateOf<HomePage?>(null) }
    var selectedChip by remember { mutableStateOf<HomePage.Chip?>(null) }
    var forgottenFavorites by remember { mutableStateOf<List<DesktopSong>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadHome(params: String? = null, continuation: String? = null) {
        scope.launch {
            if (continuation == null) {
                loading = true
                error = null
            } else {
                loadingMore = true
            }
            YouTube.home(continuation = continuation, params = params)
                .onSuccess { page ->
                    val sections = if (continuation == null && settings.randomizeHomeOrder) page.sections.shuffled() else page.sections
                    homePage = if (continuation != null && homePage != null) {
                        homePage!!.copy(
                            sections = homePage!!.sections + sections,
                            continuation = page.continuation,
                        )
                    } else {
                        page.copy(sections = sections)
                    }
                }
                .onFailure { t ->
                    if (continuation == null) error = t.message ?: "No se pudo cargar Inicio"
                }
            loading = false
            loadingMore = false
        }
    }

    LaunchedEffect(settings.cookie, settings.contentCountry, settings.contentLanguage, settings.randomizeHomeOrder) {
        selectedChip = null
        loadHome()
        forgottenFavorites = withContext(Dispatchers.IO) { appState.database.forgottenFavorites(10) }
    }

    if (loading && homePage == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = "Cargando Inicio" })
        }
        return
    }

    if (error != null && homePage == null) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { loadHome() }) {
                Icon(Icons.Rounded.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text("Reintentar")
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        val chips = homePage?.chips.orEmpty()
        if (chips.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    chips.forEach { chip ->
                        val isSelected = selectedChip?.title == chip.title
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                if (isSelected) {
                                    selectedChip = null
                                    loadHome(params = chip.deselectEndPoint?.params)
                                } else {
                                    selectedChip = chip
                                    loadHome(params = chip.endpoint?.params)
                                }
                            },
                            label = { Text(chip.title) },
                        )
                    }
                }
            }
        }

        // Wrapped Banner Card
        if (settings.showWrappedCard && recentlyPlayed.isNotEmpty() && selectedChip == null) {
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button, onClickLabel = "Abrir resumen Wrapped") {
                            appState.openWrappedDialog()
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Tu miliMusic Wrapped está listo",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                "Descubre tus minutos escuchados, artistas favoritos y crea tu playlist resumen.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                            )
                        }
                        Button(onClick = { appState.openWrappedDialog() }) { Text("Ver Wrapped") }
                    }
                }
            }
        }

        // Speed Dial (Pinned items + Randomize)
        if (speedDial.isNotEmpty() && selectedChip == null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Marcación rápida",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics { heading() },
                        )
                        OutlinedButton(onClick = {
                            val pool = recentlyPlayed.ifEmpty { appState.librarySongs.value }
                            if (pool.isNotEmpty()) {
                                appState.audioPlayer.playQueue(pool.shuffled(), 0)
                            }
                        }) {
                            Icon(Icons.Rounded.Shuffle, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Aleatorio")
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(speedDial, key = { it.id }) { sd ->
                            SpeedDialCard(sd, appState)
                        }
                    }
                }
            }
        }

        // Quick Picks / Keep Listening
        if (recentlyPlayed.isNotEmpty() && selectedChip == null) {
            item {
                val quickTitle = if (settings.quickPicksMode == DesktopQuickPicksMode.LAST_LISTEN) {
                    "Seguir escuchando"
                } else {
                    "Selección rápida y recientes"
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = quickTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        recentlyPlayed.take(5).forEach { song ->
                            SongRowItem(
                                song = song,
                                appState = appState,
                                isCurrent = pb.currentSong?.id == song.id,
                                onPlay = { appState.audioPlayer.playSong(song) },
                            )
                        }
                    }
                }
            }
        }

        // Account Playlists shortcut row
        if (settings.isLoggedIn && playlists.isNotEmpty() && selectedChip == null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Tus playlists",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(playlists.take(12), key = { it.id }) { pl ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier
                                    .width(156.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { appState.navigate(NavDestination.Playlist(pl.id, isLocal = pl.isLocal)) },
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    ArtworkImage(pl.thumbnailUrl, pl.name, modifier = Modifier.size(136.dp))
                                    Text(pl.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${pl.songCount} canciones", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Forgotten Favorites
        if (forgottenFavorites.isNotEmpty() && selectedChip == null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Favoritos olvidados",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        forgottenFavorites.take(5).forEach { song ->
                            SongRowItem(
                                song = song,
                                appState = appState,
                                isCurrent = pb.currentSong?.id == song.id,
                                onPlay = { appState.audioPlayer.playQueue(forgottenFavorites, forgottenFavorites.indexOf(song)) },
                            )
                        }
                    }
                }
            }
        }

        // YouTube Home Sections (with content filters applied)
        items(homePage?.sections.orEmpty()) { section ->
            val visibleItems = remember(section.items, settings.hideExplicit, settings.hideVideoSongs, settings.hideYoutubeShorts) {
                section.items.filter { filterContentItem(it, settings) }
            }
            if (visibleItems.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            section.label?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = section.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.semantics { heading() },
                            )
                        }
                        section.endpoint?.let { ep ->
                            TextButton(onClick = {
                                appState.navigate(NavDestination.Browse(ep.browseId, ep.params, section.title))
                            }) { Text("Ver más") }
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(visibleItems) { item ->
                            YTItemCard(item = item, appState = appState)
                        }
                    }
                }
            }
        }

        val continuation = homePage?.continuation
        if (!continuation.isNullOrBlank()) {
            item {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (loadingMore) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    } else {
                        OutlinedButton(onClick = { loadHome(continuation = continuation) }) {
                            Text("Cargar más recomendaciones")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedDialCard(sd: DesktopSpeedDialItem, appState: DesktopAppState) {
    val scope = rememberCoroutineScope()
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .width(148.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClickLabel = "Abrir ${sd.title}") {
                when (sd.type) {
                    "ALBUM" -> appState.navigate(NavDestination.Album(sd.id))
                    "ARTIST" -> appState.navigate(NavDestination.Artist(sd.id))
                    "LOCAL_PLAYLIST" -> appState.navigate(NavDestination.Playlist(sd.id, isLocal = true))
                    "PLAYLIST" -> appState.navigate(NavDestination.Playlist(sd.secondaryId ?: sd.id, isLocal = false))
                    else -> scope.launch {
                        val dbSong = withContext(Dispatchers.IO) { appState.database.getSong(sd.id) }
                        if (dbSong != null) {
                            appState.audioPlayer.playSong(dbSong)
                        } else {
                            appState.audioPlayer.playSong(
                                DesktopSong(id = sd.id, title = sd.title, thumbnailUrl = sd.thumbnailUrl, albumId = sd.albumId, albumName = sd.albumName),
                            )
                        }
                    }
                }
            },
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ArtworkImage(sd.thumbnailUrl, sd.title, isCircle = sd.type == "ARTIST", modifier = Modifier.size(128.dp))
            Text(sd.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            sd.subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun ExploreScreen(appState: DesktopAppState) {
    val settings by appState.settings.collectAsState()
    var explorePage by remember { mutableStateOf<ExplorePage?>(null) }
    var chartsPage by remember { mutableStateOf<ChartsPage?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun load() {
        scope.launch {
            loading = true
            error = null
            val expRes = YouTube.explore()
            val chRes = YouTube.getChartsPage()
            explorePage = expRes.getOrNull()
            chartsPage = chRes.getOrNull()
            if (explorePage == null && chartsPage == null) {
                error = expRes.exceptionOrNull()?.message ?: "No se pudo cargar Explorar"
            }
            loading = false
        }
    }

    LaunchedEffect(settings.contentCountry, settings.contentLanguage) { load() }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = "Cargando Explorar" })
        }
        return
    }
    if (error != null) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            Button(onClick = ::load) { Text("Reintentar") }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        val newReleases = explorePage?.newReleaseAlbums.orEmpty().filter { filterContentItem(it, settings) }
        if (newReleases.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Nuevos lanzamientos",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(newReleases) { album -> YTItemCard(item = album, appState = appState) }
                    }
                }
            }
        }

        items(chartsPage?.sections.orEmpty()) { section ->
            val filtered = section.items.filter { filterContentItem(it, settings) }
            if (filtered.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        section.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(filtered) { item -> YTItemCard(item = item, appState = appState) }
                    }
                }
            }
        }

        val moods = explorePage?.moodAndGenres.orEmpty()
        if (moods.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Estados de ánimo y géneros",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    moods.chunked(4).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            rowItems.forEach { mood ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(52.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable(role = Role.Button, onClickLabel = "Explorar ${mood.title}") {
                                            appState.navigate(NavDestination.Browse(mood.endpoint.browseId, mood.endpoint.params, mood.title))
                                        },
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .width(6.dp)
                                                .height(52.dp)
                                                .background(Color(mood.stripeColor.toInt() or 0xFF000000.toInt())),
                                        )
                                        Text(
                                            text = mood.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(horizontal = 12.dp),
                                        )
                                    }
                                }
                            }
                            repeat(4 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

private enum class SearchSource { ONLINE, LOCAL }

private enum class DesktopSearchFilter(val label: String, val filter: YouTube.SearchFilter?) {
    ALL("Todo", null),
    SONGS("Canciones", YouTube.SearchFilter.FILTER_SONG),
    VIDEOS("Videos", YouTube.SearchFilter.FILTER_VIDEO),
    ALBUMS("Álbumes", YouTube.SearchFilter.FILTER_ALBUM),
    ARTISTS("Artistas", YouTube.SearchFilter.FILTER_ARTIST),
    COMMUNITY_PLAYLISTS("Playlists comunidad", YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST),
    FEATURED_PLAYLISTS("Playlists destacadas", YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST),
    PODCASTS("Podcasts", YouTube.SearchFilter.FILTER_PODCAST),
    EPISODES("Episodios", YouTube.SearchFilter.FILTER_EPISODE),
    PROFILES("Perfiles", YouTube.SearchFilter.FILTER_PROFILE),
}

@Composable
fun SearchScreen(initialQuery: String, appState: DesktopAppState) {
    val settings by appState.settings.collectAsState()
    val history by appState.searchHistory.collectAsState()
    val pb by appState.playbackState.collectAsState()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    var searchSource by remember { mutableStateOf(SearchSource.ONLINE) }
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    var submittedQuery by remember(initialQuery) { mutableStateOf(initialQuery) }
    var selectedFilter by remember { mutableStateOf(DesktopSearchFilter.ALL) }
    var localFilter by remember { mutableStateOf(0) } // 0=Todo, 1=Canciones, 2=Álbumes, 3=Artistas, 4=Playlists
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var summaryPage by remember { mutableStateOf<SearchSummaryPage?>(null) }
    var filteredItems by remember { mutableStateOf<List<YTItem>>(emptyList()) }
    var continuation by remember { mutableStateOf<String?>(null) }
    var localResults by remember { mutableStateOf(DesktopDatabase.LocalSearchResults()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val detectedLink = remember(query) { YouTubeUrlParser.parse(query) }

    fun executeSearch(q: String, filter: DesktopSearchFilter = selectedFilter) {
        val cleaned = q.trim()
        if (cleaned.isEmpty()) return
        val link = YouTubeUrlParser.parse(cleaned)
        if (link != null && searchSource == SearchSource.ONLINE) {
            appState.openYouTubeLink(link)
            return
        }
        submittedQuery = cleaned
        if (!settings.pauseSearchHistory) {
            scope.launch(Dispatchers.IO) { appState.database.addSearchQuery(cleaned) }
        }
        scope.launch {
            loading = true
            error = null
            if (searchSource == SearchSource.LOCAL) {
                localResults = withContext(Dispatchers.IO) { appState.database.searchLocal(cleaned) }
            } else if (filter.filter == null) {
                YouTube.searchSummary(cleaned)
                    .onSuccess {
                        summaryPage = it
                        filteredItems = emptyList()
                        continuation = null
                    }
                    .onFailure { error = it.message ?: "Error al buscar" }
            } else {
                YouTube.search(cleaned, filter.filter)
                    .onSuccess {
                        filteredItems = it.items
                        continuation = it.continuation
                        summaryPage = null
                    }
                    .onFailure { error = it.message ?: "Error al buscar" }
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        if (initialQuery.isNotBlank()) executeSearch(initialQuery)
    }

    LaunchedEffect(query, searchSource) {
        if (query.isBlank() || searchSource == SearchSource.LOCAL) {
            suggestions = emptyList()
            if (searchSource == SearchSource.LOCAL && query.isNotBlank()) {
                delay(180L)
                localResults = withContext(Dispatchers.IO) { appState.database.searchLocal(query) }
            }
            return@LaunchedEffect
        }
        delay(220L)
        YouTube.searchSuggestions(query).onSuccess { suggestions = it.queries }
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = {
                    Text(
                        if (searchSource == SearchSource.ONLINE) "Buscar en YouTube Music o pegar enlace..."
                        else "Buscar en tu biblioteca local...",
                    )
                },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            query = ""
                            submittedQuery = ""
                            summaryPage = null
                            filteredItems = emptyList()
                            localResults = DesktopDatabase.LocalSearchResults()
                        }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Limpiar búsqueda")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { executeSearch(query) }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown && (ev.key == Key.Enter || ev.key == Key.NumPadEnter)) {
                            executeSearch(query)
                            true
                        } else false
                    },
            )
            Button(onClick = { executeSearch(query) }) { Text("Buscar") }
            OutlinedButton(onClick = { appState.navigate(NavDestination.Recognition) }) {
                Icon(Icons.Rounded.MusicNote, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Shazam")
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = searchSource == SearchSource.ONLINE,
                onClick = {
                    searchSource = SearchSource.ONLINE
                    if (query.isNotBlank()) executeSearch(query)
                },
                label = { Text("YouTube Music (Online)") },
            )
            FilterChip(
                selected = searchSource == SearchSource.LOCAL,
                onClick = {
                    searchSource = SearchSource.LOCAL
                    if (query.isNotBlank()) executeSearch(query)
                },
                label = { Text("Biblioteca local") },
            )
        }

        // Direct YouTube URL banner
        if (detectedLink != null) {
            Spacer(Modifier.height(10.dp))
            val linkLabel = when (detectedLink) {
                is ParsedYouTubeLink.Video -> "Enlace de canción/video detectado (${detectedLink.videoId})"
                is ParsedYouTubeLink.Playlist -> "Enlace de playlist detectado (${detectedLink.playlistId})"
                is ParsedYouTubeLink.Album -> "Enlace de álbum detectado (${detectedLink.browseId})"
                is ParsedYouTubeLink.Artist -> "Enlace de artista detectado (${detectedLink.channelId})"
                is ParsedYouTubeLink.Podcast -> "Enlace de podcast detectado (${detectedLink.podcastId})"
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(linkLabel, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Button(onClick = { appState.openYouTubeLink(detectedLink) }) {
                        Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Abrir directo")
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        if (searchSource == SearchSource.LOCAL) {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Todo", "Canciones", "Álbumes", "Artistas", "Playlists").forEachIndexed { idx, lbl ->
                    FilterChip(selected = localFilter == idx, onClick = { localFilter = idx }, label = { Text(lbl) })
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                if ((localFilter == 0 || localFilter == 1) && localResults.songs.isNotEmpty()) {
                    item { Text("Canciones (${localResults.songs.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    items(localResults.songs) { s ->
                        SongRowItem(s, appState, isCurrent = pb.currentSong?.id == s.id, onPlay = { appState.audioPlayer.playSong(s) })
                    }
                }
                if ((localFilter == 0 || localFilter == 2) && localResults.albums.isNotEmpty()) {
                    item { Text("Álbumes (${localResults.albums.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    items(localResults.albums) { alb ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().clickable { appState.navigate(NavDestination.Album(alb.id)) },
                        ) {
                            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                ArtworkImage(alb.thumbnailUrl, alb.title, modifier = Modifier.size(44.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(alb.title, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                if ((localFilter == 0 || localFilter == 3) && localResults.artists.isNotEmpty()) {
                    item { Text("Artistas (${localResults.artists.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    items(localResults.artists) { art ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().clickable { appState.navigate(NavDestination.Artist(art.id)) },
                        ) {
                            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                ArtworkImage(art.thumbnailUrl, art.displayName, isCircle = true, modifier = Modifier.size(44.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(art.displayName, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                if ((localFilter == 0 || localFilter == 4) && localResults.playlists.isNotEmpty()) {
                    item { Text("Playlists (${localResults.playlists.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    items(localResults.playlists) { pl ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().clickable { appState.navigate(NavDestination.Playlist(pl.id, isLocal = pl.isLocal)) },
                        ) {
                            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                ArtworkImage(pl.thumbnailUrl, pl.name, modifier = Modifier.size(44.dp))
                                Spacer(Modifier.width(12.dp))
                                Text("${pl.name} (${pl.songCount})", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                if (query.isNotBlank() && localResults.songs.isEmpty() && localResults.albums.isEmpty() && localResults.artists.isEmpty() && localResults.playlists.isEmpty()) {
                    item { Text("Sin coincidencias en tu biblioteca local.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            return
        }

        // Online search filter chips
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DesktopSearchFilter.entries.forEach { f ->
                FilterChip(
                    selected = selectedFilter == f,
                    onClick = {
                        selectedFilter = f
                        if (submittedQuery.isNotBlank()) executeSearch(submittedQuery, f)
                    },
                    label = { Text(f.label) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return
        }

        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
            return
        }

        if (submittedQuery.isBlank() || (summaryPage == null && filteredItems.isEmpty())) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (suggestions.isNotEmpty()) {
                    item {
                        Text(
                            "Sugerencias",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 4.dp).semantics { heading() },
                        )
                    }
                    items(suggestions) { sug ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(role = Role.Button, onClickLabel = "Buscar sugerencia $sug") {
                                    query = sug
                                    executeSearch(sug)
                                }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(sug)
                        }
                    }
                }

                if (history.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Búsquedas recientes",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.semantics { heading() },
                            )
                            TextButton(onClick = { scope.launch(Dispatchers.IO) { appState.database.clearSearchHistory() } }) {
                                Text("Limpiar todo")
                            }
                        }
                    }
                    items(history) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(role = Role.Button, onClickLabel = "Buscar $item del historial") {
                                    query = item
                                    executeSearch(item)
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(item, modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = { scope.launch(Dispatchers.IO) { appState.database.deleteSearchQuery(item) } },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = "Eliminar búsqueda $item", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (summaryPage != null) {
                items(summaryPage!!.summaries) { summary ->
                    val visible = summary.items.filter { filterContentItem(it, settings) }
                    if (visible.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(summary.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            val songs = visible.filterIsInstance<SongItem>()
                            if (songs.size == visible.size) {
                                songs.forEach { songItem ->
                                    val desktopSong = songItem.toDesktopSong()
                                    SongRowItem(
                                        song = desktopSong,
                                        appState = appState,
                                        isCurrent = pb.currentSong?.id == desktopSong.id,
                                        onPlay = { appState.audioPlayer.playSong(desktopSong) },
                                    )
                                }
                            } else {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    items(visible) { yt -> YTItemCard(item = yt, appState = appState) }
                                }
                            }
                        }
                    }
                }
            } else {
                val visibleFiltered = filteredItems.filter { filterContentItem(it, settings) }
                items(visibleFiltered) { yt ->
                    when (yt) {
                        is SongItem -> {
                            val ds = yt.toDesktopSong()
                            SongRowItem(
                                song = ds,
                                appState = appState,
                                isCurrent = pb.currentSong?.id == ds.id,
                                onPlay = { appState.audioPlayer.playSong(ds) },
                            )
                        }
                        is EpisodeItem -> {
                            val ds = DesktopSong(
                                id = yt.id,
                                title = yt.title,
                                duration = yt.duration ?: -1,
                                thumbnailUrl = yt.thumbnail,
                                isEpisode = true,
                            )
                            SongRowItem(
                                song = ds,
                                appState = appState,
                                isCurrent = pb.currentSong?.id == ds.id,
                                onPlay = { appState.audioPlayer.playSong(ds) },
                            )
                        }
                        is AlbumItem, is ArtistItem, is PlaylistItem, is PodcastItem -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        when (yt) {
                                            is AlbumItem -> appState.navigate(NavDestination.Album(yt.browseId))
                                            is ArtistItem -> appState.navigate(NavDestination.Artist(yt.id))
                                            is PlaylistItem -> appState.navigate(NavDestination.Playlist(yt.id, isLocal = false))
                                            is PodcastItem -> appState.navigate(NavDestination.Podcast(yt.id))
                                            is SongItem, is EpisodeItem -> Unit
                                        }
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ArtworkImage(
                                    url = yt.thumbnail,
                                    contentDescription = yt.title,
                                    isCircle = yt is ArtistItem,
                                    modifier = Modifier.size(52.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(yt.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                    val sub = when (yt) {
                                        is AlbumItem -> "Álbum • ${yt.artists?.joinToString(", ") { it.name }.orEmpty()}"
                                        is ArtistItem -> "Artista"
                                        is PlaylistItem -> "Playlist • ${yt.author?.name.orEmpty()}"
                                        is PodcastItem -> "Podcast • ${yt.author?.name.orEmpty()}"
                                        is SongItem, is EpisodeItem -> ""
                                    }
                                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                if (!continuation.isNullOrBlank()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            OutlinedButton(onClick = {
                                val cont = continuation ?: return@OutlinedButton
                                scope.launch {
                                    YouTube.searchContinuation(cont).onSuccess { res ->
                                        filteredItems = filteredItems + res.items
                                        continuation = res.continuation
                                    }
                                }
                            }) { Text("Cargar más resultados") }
                        }
                    }
                }
            }
        }
    }
}
