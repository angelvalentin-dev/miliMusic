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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import com.metrolist.desktop.ui.icons.History
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import com.metrolist.desktop.db.toDesktopSong
import com.metrolist.desktop.state.DesktopAppState
import com.metrolist.desktop.state.NavDestination
import com.metrolist.desktop.ui.components.SongRowItem
import com.metrolist.desktop.ui.components.YTItemCard
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(appState: DesktopAppState) {
    val settings by appState.settings.collectAsState()
    val recentlyPlayed by appState.recentlyPlayed.collectAsState()
    val pb by appState.playbackState.collectAsState()
    val scope = rememberCoroutineScope()

    var homePage by remember { mutableStateOf<HomePage?>(null) }
    var selectedChip by remember { mutableStateOf<HomePage.Chip?>(null) }
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
                    homePage = if (continuation != null && homePage != null) {
                        homePage!!.copy(
                            sections = homePage!!.sections + page.sections,
                            continuation = page.continuation,
                        )
                    } else {
                        page
                    }
                }
                .onFailure { t ->
                    if (continuation == null) error = t.message ?: "No se pudo cargar Inicio"
                }
            loading = false
            loadingMore = false
        }
    }

    LaunchedEffect(settings.cookie, settings.contentCountry, settings.contentLanguage) {
        selectedChip = null
        loadHome()
    }

    if (loading && homePage == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
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

        if (recentlyPlayed.isNotEmpty() && selectedChip == null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Escuchado recientemente",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
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

        items(homePage?.sections.orEmpty()) { section ->
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
                        )
                    }
                    section.endpoint?.let { ep ->
                        TextButton(onClick = {
                            appState.navigate(NavDestination.Browse(ep.browseId, ep.params, section.title))
                        }) {
                            Text("Ver más")
                        }
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(section.items) { item ->
                        YTItemCard(item = item, appState = appState)
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
fun ExploreScreen(appState: DesktopAppState) {
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
                error = expRes.exceptionOrNull()?.message ?: chRes.exceptionOrNull()?.message ?: "Error al cargar Explorar"
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
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
            Spacer(Modifier.height(10.dp))
            Button(onClick = ::load) { Text("Reintentar") }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        explorePage?.newReleaseAlbums?.takeIf { it.isNotEmpty() }?.let { albums ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Nuevos lanzamientos", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(albums) { album -> YTItemCard(album, appState) }
                    }
                }
            }
        }

        chartsPage?.sections?.forEach { section ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(section.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(section.items) { item -> YTItemCard(item, appState) }
                    }
                }
            }
        }

        explorePage?.moodAndGenres?.takeIf { it.isNotEmpty() }?.let { moods ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Estados de ánimo y géneros", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    val rows = moods.chunked(4)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        rows.forEach { rowItems ->
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
                                            .clickable(
                                                role = Role.Button,
                                                onClickLabel = "Explorar estado de ánimo o género ${mood.title}",
                                            ) {
                                                appState.navigate(
                                                    NavDestination.Browse(
                                                        browseId = mood.endpoint.browseId,
                                                        params = mood.endpoint.params,
                                                        title = mood.title,
                                                    ),
                                                )
                                            },
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .width(6.dp)
                                                    .height(52.dp)
                                                    .background(Color(mood.stripeColor.toInt() or (0xFF shl 24))),
                                            )
                                            Text(
                                                text = mood.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
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
}

private enum class SearchTab(val label: String, val filter: YouTube.SearchFilter?) {
    ALL("Todo", null),
    SONGS("Canciones", YouTube.SearchFilter.FILTER_SONG),
    VIDEOS("Videos", YouTube.SearchFilter.FILTER_VIDEO),
    ALBUMS("Álbumes", YouTube.SearchFilter.FILTER_ALBUM),
    ARTISTS("Artistas", YouTube.SearchFilter.FILTER_ARTIST),
    PLAYLISTS("Playlists", YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST),
    PODCASTS("Podcasts", YouTube.SearchFilter.FILTER_PODCAST),
}

@Composable
fun SearchScreen(
    initialQuery: String,
    appState: DesktopAppState,
) {
    val settings by appState.settings.collectAsState()
    val history by appState.searchHistory.collectAsState()
    val pb by appState.playbackState.collectAsState()
    val scope = rememberCoroutineScope()

    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    var submittedQuery by remember(initialQuery) { mutableStateOf(initialQuery) }
    var selectedTab by remember { mutableStateOf(SearchTab.ALL) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var summaryPage by remember { mutableStateOf<SearchSummaryPage?>(null) }
    var filteredItems by remember { mutableStateOf<List<YTItem>>(emptyList()) }
    var continuation by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(query) {
        if (query.isBlank() || query == submittedQuery) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(220)
        YouTube.searchSuggestions(query).onSuccess { res ->
            suggestions = res.queries
        }
    }

    fun executeSearch(q: String, tab: SearchTab = selectedTab) {
        val cleaned = q.trim()
        if (cleaned.isEmpty()) return
        submittedQuery = cleaned
        query = cleaned
        suggestions = emptyList()
        searchError = null
        if (!settings.pauseSearchHistory) {
            appState.database.addSearchQuery(cleaned)
        }
        scope.launch {
            loading = true
            continuation = null
            if (tab.filter == null) {
                YouTube.searchSummary(cleaned)
                    .onSuccess {
                        summaryPage = it
                        filteredItems = emptyList()
                    }
                    .onFailure { t ->
                        summaryPage = null
                        filteredItems = emptyList()
                        searchError = t.message ?: "Error al buscar en YouTube Music"
                    }
            } else {
                YouTube.search(cleaned, tab.filter)
                    .onSuccess { res ->
                        filteredItems = res.items
                        continuation = res.continuation
                        summaryPage = null
                    }
                    .onFailure { t ->
                        filteredItems = emptyList()
                        summaryPage = null
                        searchError = t.message ?: "Error al buscar en YouTube Music"
                    }
            }
            loading = false
        }
    }

    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank()) {
            executeSearch(initialQuery)
        } else {
            runCatching { searchFocusRequester.requestFocus() }
        }
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
                label = { Text("Buscar música") },
                placeholder = { Text("Buscar canciones, artistas, álbumes o podcasts...") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            query = ""
                            submittedQuery = ""
                            summaryPage = null
                            filteredItems = emptyList()
                            searchError = null
                        }) {
                            Icon(Icons.Rounded.Close, "Limpiar búsqueda")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { executeSearch(query) }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(searchFocusRequester)
                    .onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown && (ev.key == Key.Enter || ev.key == Key.NumPadEnter)) {
                            executeSearch(query)
                            true
                        } else {
                            false
                        }
                    },
            )
            Button(
                enabled = query.isNotBlank(),
                onClick = { executeSearch(query) },
                modifier = Modifier.height(52.dp),
            ) {
                Text("Buscar")
            }
        }

        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(6.dp)) {
                    suggestions.take(6).forEach { s ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = "Buscar sugerencia $s",
                                ) { executeSearch(s) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Search, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(s, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        if (submittedQuery.isBlank() && history.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Búsquedas recientes",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                TextButton(onClick = { appState.database.clearSearchHistory() }) {
                    Text("Limpiar historial")
                }
            }
            Spacer(Modifier.height(6.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(history) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(
                                role = Role.Button,
                                onClickLabel = "Buscar $item",
                            ) { executeSearch(item) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        Text(item, modifier = Modifier.weight(1f))
                        IconButton(onClick = { appState.database.deleteSearchQuery(item) }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Eliminar $item del historial de búsqueda",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
            return
        }

        if (submittedQuery.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchTab.entries.forEach { tab ->
                    FilterChip(
                        selected = selectedTab == tab,
                        onClick = {
                            selectedTab = tab
                            executeSearch(submittedQuery, tab)
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = "Buscando resultados" })
            }
            return
        }

        if (searchError != null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(searchError!!, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(10.dp))
                Button(onClick = { executeSearch(submittedQuery) }) {
                    Icon(Icons.Rounded.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Reintentar")
                }
            }
            return
        }

        val hasResults = (summaryPage?.summaries?.isNotEmpty() == true) || filteredItems.isNotEmpty()
        if (submittedQuery.isNotBlank() && !hasResults) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No se encontraron resultados para \"$submittedQuery\"",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            summaryPage?.summaries?.forEach { section ->
                item {
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                val songs = section.items.filterIsInstance<SongItem>()
                if (songs.size == section.items.size) {
                    items(songs) { song ->
                        val ds = song.toDesktopSong()
                        SongRowItem(
                            song = ds,
                            appState = appState,
                            isCurrent = pb.currentSong?.id == ds.id,
                            onPlay = { appState.audioPlayer.playSong(ds) },
                        )
                    }
                } else {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(section.items) { yt -> YTItemCard(yt, appState) }
                        }
                    }
                }
            }

            items(filteredItems) { item ->
                when (item) {
                    is SongItem -> {
                        val ds = item.toDesktopSong()
                        SongRowItem(
                            song = ds,
                            appState = appState,
                            isCurrent = pb.currentSong?.id == ds.id,
                            onPlay = { appState.audioPlayer.playSong(ds) },
                        )
                    }
                    else -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            YTItemCard(item = item, appState = appState)
                        }
                    }
                }
            }

            val nextCont = continuation
            if (!nextCont.isNullOrBlank()) {
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                YouTube.searchContinuation(nextCont).onSuccess { res ->
                                    filteredItems = (filteredItems + res.items).distinctBy { it.id }
                                    continuation = res.continuation
                                }
                            }
                        }) {
                            Text("Cargar más resultados")
                        }
                    }
                }
            }
        }
    }
}
