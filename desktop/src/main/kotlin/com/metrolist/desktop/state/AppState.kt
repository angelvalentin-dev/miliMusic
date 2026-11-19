package com.metrolist.desktop.state

import com.metrolist.desktop.AppPaths
import com.metrolist.desktop.audio.DesktopAudioPlayer
import com.metrolist.desktop.audio.DownloadManager
import com.metrolist.desktop.audio.InnerTubeStreamResolver
import com.metrolist.desktop.audio.PlaybackState
import com.metrolist.desktop.audio.StreamProxyServer
import com.metrolist.desktop.auth.WindowsWebView2Auth
import com.metrolist.desktop.backup.BackupRestoreService
import com.metrolist.desktop.data.DesktopPrefKeys
import com.metrolist.desktop.data.DesktopPreferences
import com.metrolist.desktop.data.DesktopSettings
import com.metrolist.desktop.db.DesktopAlarmEntry
import com.metrolist.desktop.db.DesktopAlbum
import com.metrolist.desktop.db.DesktopArtist
import com.metrolist.desktop.db.DesktopDatabase
import com.metrolist.desktop.db.DesktopPlaylist
import com.metrolist.desktop.db.DesktopSong
import com.metrolist.desktop.db.toDesktopAlbum
import com.metrolist.desktop.db.toDesktopArtist
import com.metrolist.desktop.db.toDesktopPlaylist
import com.metrolist.desktop.db.toDesktopSong
import com.metrolist.desktop.integrations.DesktopAlarmScheduler
import com.metrolist.desktop.integrations.DesktopDiscordRpc
import com.metrolist.desktop.integrations.DesktopLastFmService
import com.metrolist.desktop.integrations.DesktopListenTogetherManager
import com.metrolist.desktop.lyrics.DesktopLyricsService
import com.metrolist.desktop.lyrics.LyricsPayload
import com.metrolist.desktop.windows.WindowsSmtcManager
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.innertube.utils.parseCookieString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

sealed interface NavDestination {
    data object Home : NavDestination
    data object Explore : NavDestination
    data class Search(val query: String = "") : NavDestination
    data class Library(val initialTab: Int = 0) : NavDestination
    data object Stats : NavDestination
    data class Artist(val artistId: String) : NavDestination
    data class Album(val albumId: String) : NavDestination
    data class Playlist(val playlistId: String, val isLocal: Boolean = false) : NavDestination
    data class Podcast(val podcastId: String) : NavDestination
    data class Browse(val browseId: String, val params: String? = null, val title: String = "") : NavDestination
    data object Settings : NavDestination
}

class DesktopAppState {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val preferences = DesktopPreferences(scope)
    val database = DesktopDatabase()
    val backupService = BackupRestoreService(database, preferences)
    val streamResolver = InnerTubeStreamResolver()

    private val _settings = MutableStateFlow(DesktopSettings())
    val settings: StateFlow<DesktopSettings> = _settings.asStateFlow()

    val proxyServer = StreamProxyServer(
        resolver = streamResolver,
        qualityProvider = { _settings.value.audioQuality },
        downloadsDirProvider = { File(_settings.value.downloadsDir) },
        cacheEnabledProvider = { _settings.value.enableSongCache },
        onSongCached = { songId -> database.setCached(songId, true) },
    )

    val audioPlayer = DesktopAudioPlayer(
        database = database,
        proxyServer = proxyServer,
        pauseListenHistoryProvider = { _settings.value.pauseListenHistory },
        autoplayProvider = { _settings.value.autoplay },
        persistentQueueProvider = { _settings.value.persistentQueue },
        onVolumePersist = { vol -> scope.launch { preferences.set(DesktopPrefKeys.Volume, vol) } },
        onSpeedPersist = { spd -> scope.launch { preferences.set(DesktopPrefKeys.PlaybackSpeed, spd) } },
        onEqPersist = { enabled, preset, preamp, bands ->
            scope.launch {
                preferences.set(DesktopPrefKeys.EqEnabled, enabled)
                preferences.set(DesktopPrefKeys.EqPreset, preset)
                preferences.set(DesktopPrefKeys.EqPreamp, preamp)
                preferences.set(DesktopPrefKeys.EqBands, bands.joinToString(","))
            }
        },
    )

    val downloadManager = DownloadManager(
        database = database,
        resolver = streamResolver,
        qualityProvider = { _settings.value.audioQuality },
        downloadsDirProvider = { File(_settings.value.downloadsDir) },
    )

    val lyricsService = DesktopLyricsService(database)

    val discordRpc = DesktopDiscordRpc(scope)
    val alarmScheduler = DesktopAlarmScheduler(
        database = database,
        audioPlayer = audioPlayer,
        onStatusMessage = ::showStatus,
        scope = scope,
    )
    val listenTogetherManager = DesktopListenTogetherManager(
        audioPlayer = audioPlayer,
        onStatusMessage = ::showStatus,
        scope = scope,
    )

    val smtcManager = WindowsSmtcManager(
        onPlay = { audioPlayer.resume() },
        onPause = { audioPlayer.pause() },
        onNext = { audioPlayer.next() },
        onPrevious = { audioPlayer.previous() },
        onStop = { audioPlayer.stop() },
    )

    val playbackState: StateFlow<PlaybackState> = audioPlayer.state

    // Library reactive streams
    val likedSongs: StateFlow<List<DesktopSong>> = database.observe { database.likedSongs() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val librarySongs: StateFlow<List<DesktopSong>> = database.observe { database.librarySongs() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val downloadedSongs: StateFlow<List<DesktopSong>> = database.observe { database.downloadedSongs() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val topSongs: StateFlow<List<DesktopSong>> = database.observe { database.topSongs(50) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val cachedSongs: StateFlow<List<DesktopSong>> = database.observe { database.cachedSongs() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val playlists: StateFlow<List<DesktopPlaylist>> = database.observe { database.playlists() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val bookmarkedAlbums: StateFlow<List<DesktopAlbum>> = database.observe { database.bookmarkedAlbums() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val bookmarkedArtists: StateFlow<List<DesktopArtist>> = database.observe { database.bookmarkedArtists() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val recentlyPlayed: StateFlow<List<DesktopSong>> = database.observe { database.recentlyPlayed() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val searchHistory: StateFlow<List<String>> = database.observe { database.searchHistory() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    // Navigation history
    private val backStack = mutableListOf<NavDestination>(NavDestination.Home)
    private var navCursor = 0
    private val _currentDestination = MutableStateFlow<NavDestination>(NavDestination.Home)
    val currentDestination: StateFlow<NavDestination> = _currentDestination.asStateFlow()
    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()
    private val _canGoForward = MutableStateFlow(false)
    val canGoForward: StateFlow<Boolean> = _canGoForward.asStateFlow()

    // Right panel & full player
    private val _showLyricsPanel = MutableStateFlow(false)
    val showLyricsPanel: StateFlow<Boolean> = _showLyricsPanel.asStateFlow()
    private val _showQueuePanel = MutableStateFlow(false)
    val showQueuePanel: StateFlow<Boolean> = _showQueuePanel.asStateFlow()
    private val _showFullPlayer = MutableStateFlow(false)
    val showFullPlayer: StateFlow<Boolean> = _showFullPlayer.asStateFlow()

    // Feature dialogs
    private val _showEqualizerDialog = MutableStateFlow(false)
    val showEqualizerDialog: StateFlow<Boolean> = _showEqualizerDialog.asStateFlow()
    private val _showSleepTimerDialog = MutableStateFlow(false)
    val showSleepTimerDialog: StateFlow<Boolean> = _showSleepTimerDialog.asStateFlow()
    private val _showListenTogetherDialog = MutableStateFlow(false)
    val showListenTogetherDialog: StateFlow<Boolean> = _showListenTogetherDialog.asStateFlow()
    private val _showWrappedDialog = MutableStateFlow(false)
    val showWrappedDialog: StateFlow<Boolean> = _showWrappedDialog.asStateFlow()

    // Current song lyrics
    private val _currentLyrics = MutableStateFlow<LyricsPayload?>(null)
    val currentLyrics: StateFlow<LyricsPayload?> = _currentLyrics.asStateFlow()
    private val _lyricsLoading = MutableStateFlow(false)
    val lyricsLoading: StateFlow<Boolean> = _lyricsLoading.asStateFlow()

    // Toast / snackbar status
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    // Login dialog trigger
    private val _showLoginDialog = MutableStateFlow(false)
    val showLoginDialog: StateFlow<Boolean> = _showLoginDialog.asStateFlow()

    // Add-to-playlist target song
    private val _playlistPickerSong = MutableStateFlow<DesktopSong?>(null)
    val playlistPickerSong: StateFlow<DesktopSong?> = _playlistPickerSong.asStateFlow()

    init {
        scope.launch {
            preferences.settingsFlow.collectLatest { st ->
                _settings.value = st
                syncInnerTubeSession(st)
                alarmScheduler.updateFromRawJson(st.alarmEntriesJson)
                audioPlayer.setPlaybackSpeed(st.playbackSpeed, persist = false)
                audioPlayer.setEqualizer(
                    enabled = st.eqEnabled,
                    preset = st.eqPreset,
                    preamp = st.eqPreamp,
                    bands = st.eqBands,
                    persist = false,
                )
            }
        }
        scope.launch {
            var lastSongId: String? = null
            var lastPlaying: Boolean? = null
            var lastDiscordUpdateMs = 0L
            var scrobbledCurrentSong = false
            var currentSongStartedEpochSec = 0L

            audioPlayer.state.collectLatest { pb ->
                val song = pb.currentSong
                val st = _settings.value
                val trackChanged = song?.id != lastSongId
                val playingChanged = pb.isPlaying != lastPlaying

                if (trackChanged) {
                    lastSongId = song?.id
                    scrobbledCurrentSong = false
                    currentSongStartedEpochSec = System.currentTimeMillis() / 1000L
                    if (song != null) {
                        loadLyrics(song)
                        if (st.enableSmtc) {
                            smtcManager.updateMetadata(song.title, song.artistDisplay, song.albumName, song.thumbnailUrl)
                        }
                        scope.launch {
                            runCatching { DesktopLastFmService.updateNowPlaying(song, st) }
                        }
                    } else {
                        _currentLyrics.value = null
                    }
                }
                if (playingChanged && st.enableSmtc) {
                    smtcManager.updatePlaybackStatus(pb.isPlaying, song != null)
                }
                if (trackChanged || playingChanged) {
                    lastPlaying = pb.isPlaying
                    listenTogetherManager.broadcastHostPlayback(
                        song = song,
                        isPlaying = pb.isPlaying,
                        positionMs = pb.positionMs,
                        trackChanged = trackChanged,
                    )
                }
                if (st.enableSmtc && song != null) {
                    smtcManager.updateTimeline(pb.positionMs, pb.durationMs)
                }

                // Last.fm Scrobble after 50% of duration or 180 seconds (minimum 30s track)
                if (!scrobbledCurrentSong && song != null && pb.isPlaying) {
                    val durSec = (pb.durationMs / 1000L).takeIf { it > 0L } ?: song.duration.toLong()
                    val thresholdMs = if (durSec >= 30L) {
                        minOf((durSec * 500L), 180_000L).coerceAtLeast(15_000L)
                    } else {
                        30_000L
                    }
                    if (pb.positionMs >= thresholdMs) {
                        scrobbledCurrentSong = true
                        val startedAt = currentSongStartedEpochSec.takeIf { it > 0L } ?: (System.currentTimeMillis() / 1000L)
                        scope.launch {
                            runCatching { DesktopLastFmService.scrobble(song, startedAt, st) }
                        }
                    }
                }

                // Discord RPC update on track/play state change or every 15s
                val now = System.currentTimeMillis()
                if (trackChanged || playingChanged || (pb.isPlaying && now - lastDiscordUpdateMs >= 15_000L)) {
                    lastDiscordUpdateMs = now
                    discordRpc.updatePresence(song, pb.isPlaying, pb.positionMs, pb.durationMs, st)
                }
            }
        }
        scope.launch {
            preferences.settingsFlow.map { it.volume }.distinctUntilChanged().collectLatest { vol ->
                audioPlayer.setVolume(vol, persist = false)
            }
        }
    }

    private fun syncInnerTubeSession(st: DesktopSettings) {
        val sys = Locale.getDefault()
        val lang = if (st.contentLanguage == "SYSTEM_DEFAULT") sys.language.ifBlank { "en" } else st.contentLanguage
        val country = if (st.contentCountry == "SYSTEM_DEFAULT") sys.country.ifBlank { "US" } else st.contentCountry
        YouTube.locale = YouTubeLocale(gl = country, hl = lang)
        YouTube.cookie = st.cookie.ifBlank { null }
        YouTube.visitorData = st.visitorData.ifBlank { null }
        YouTube.dataSyncId = st.dataSyncId.ifBlank { null }
        YouTube.authUser = st.authUser.ifBlank { "0" }
        YouTube.useLoginForBrowse = st.isLoggedIn
    }

    fun navigate(dest: NavDestination) {
        if (_currentDestination.value == dest) return
        while (backStack.lastIndex > navCursor) {
            backStack.removeAt(backStack.lastIndex)
        }
        backStack.add(dest)
        navCursor = backStack.lastIndex
        updateNavState()
        _showFullPlayer.value = false
    }

    fun goBack() {
        if (navCursor > 0) {
            navCursor -= 1
            updateNavState()
        }
    }

    fun goForward() {
        if (navCursor < backStack.lastIndex) {
            navCursor += 1
            updateNavState()
        }
    }

    private fun updateNavState() {
        _currentDestination.value = backStack[navCursor]
        _canGoBack.value = navCursor > 0
        _canGoForward.value = navCursor < backStack.lastIndex
    }

    fun toggleLyricsPanel() {
        val next = !_showLyricsPanel.value
        _showLyricsPanel.value = next
        if (next) _showQueuePanel.value = false
    }

    fun toggleQueuePanel() {
        val next = !_showQueuePanel.value
        _showQueuePanel.value = next
        if (next) _showLyricsPanel.value = false
    }

    fun toggleFullPlayer() {
        _showFullPlayer.value = !_showFullPlayer.value
    }

    fun openEqualizerDialog() {
        _showEqualizerDialog.value = true
    }

    fun closeEqualizerDialog() {
        _showEqualizerDialog.value = false
    }

    fun openSleepTimerDialog() {
        _showSleepTimerDialog.value = true
    }

    fun closeSleepTimerDialog() {
        _showSleepTimerDialog.value = false
    }

    fun openListenTogetherDialog() {
        _showListenTogetherDialog.value = true
    }

    fun closeListenTogetherDialog() {
        _showListenTogetherDialog.value = false
    }

    fun openWrappedDialog() {
        _showWrappedDialog.value = true
    }

    fun closeWrappedDialog() {
        _showWrappedDialog.value = false
    }

    fun saveAlarms(entries: List<DesktopAlarmEntry>) {
        scope.launch {
            val serialized = alarmScheduler.serializeAlarms(entries)
            preferences.set(DesktopPrefKeys.AlarmEntries, serialized)
        }
    }

    fun openPlaylistPicker(song: DesktopSong) {
        _playlistPickerSong.value = song
    }

    fun closePlaylistPicker() {
        _playlistPickerSong.value = null
    }

    fun openLoginDialog() {
        _showLoginDialog.value = true
    }

    fun closeLoginDialog() {
        _showLoginDialog.value = false
    }

    fun showStatus(message: String) {
        _statusMessage.value = message
    }

    fun clearStatus() {
        _statusMessage.value = null
    }

    fun toggleLike(song: DesktopSong) {
        scope.launch {
            val updated = database.toggleLike(song)
            if (_settings.value.isLoggedIn && _settings.value.ytmSync) {
                YouTube.likeVideo(updated.id, updated.liked)
            }
            runCatching { DesktopLastFmService.setLoveStatus(updated, updated.liked, _settings.value) }
        }
    }

    fun toggleInLibrary(song: DesktopSong) {
        scope.launch {
            val updated = database.toggleInLibrary(song)
            val token = if (updated.inLibrary != null) updated.libraryAddToken else updated.libraryRemoveToken
            if (_settings.value.isLoggedIn && !token.isNullOrBlank()) {
                YouTube.feedback(listOf(token))
            }
        }
    }

    private fun loadLyrics(song: DesktopSong) {
        scope.launch {
            _lyricsLoading.value = true
            _currentLyrics.value = lyricsService.getLyrics(song, _settings.value)
            _lyricsLoading.value = false
        }
    }

    suspend fun loginWithWebView2(switchChannel: Boolean = false): Result<Unit> {
        val auth = WindowsWebView2Auth.launchInteractiveLogin(switchChannel).getOrElse { return Result.failure(it) }
        return applySessionCredentials(
            cookie = auth.cookie,
            visitorData = auth.visitorData,
            dataSyncId = auth.dataSyncId,
            authUser = auth.authUser,
        )
    }

    suspend fun applySessionCredentials(
        cookie: String,
        visitorData: String,
        dataSyncId: String,
        authUser: String,
    ): Result<Unit> = runCatching {
        val cookieMap = parseCookieString(cookie)
        require("SAPISID" in cookieMap) { "La cookie no contiene SAPISID (sesión inválida)" }
        YouTube.cookie = cookie
        YouTube.visitorData = visitorData.ifBlank { null }
        YouTube.dataSyncId = dataSyncId.ifBlank { null }
        YouTube.authUser = authUser.ifBlank { "0" }
        YouTube.useLoginForBrowse = true

        val account = YouTube.accountInfo().getOrThrow()
        preferences.updateSession(
            cookie = cookie,
            visitorData = visitorData,
            dataSyncId = dataSyncId,
            authUser = authUser.ifBlank { "0" },
            accountName = account.name,
            accountEmail = account.email.orEmpty(),
            accountChannelHandle = account.channelHandle.orEmpty(),
        )
        showStatus("Sesión iniciada como ${account.name}")
    }

    fun syncLibraryWithYtm() {
        if (!_settings.value.isLoggedIn) {
            showStatus("Inicia sesión primero para sincronizar con YouTube Music")
            return
        }
        scope.launch {
            showStatus("Sincronizando biblioteca con YouTube Music...")
            runCatching {
                val now = System.currentTimeMillis()
                YouTube.library("FEmusic_liked_playlists").getOrNull()?.items?.forEach { item ->
                    if (item is com.metrolist.innertube.models.PlaylistItem) {
                        database.upsertRemotePlaylist(item.toDesktopPlaylist())
                    }
                }
                YouTube.library("FEmusic_liked_videos").getOrNull()?.items?.forEach { item ->
                    if (item is com.metrolist.innertube.models.SongItem) {
                        database.upsertSong(item.toDesktopSong().copy(liked = true, inLibrary = now))
                    }
                }
                YouTube.library("FEmusic_library_corpus_track_artists").getOrNull()?.items?.forEach { item ->
                    if (item is com.metrolist.innertube.models.ArtistItem && !database.isArtistBookmarked(item.id)) {
                        database.toggleArtistBookmark(item.toDesktopArtist())
                    }
                }
                YouTube.library("FEmusic_liked_albums").getOrNull()?.items?.forEach { item ->
                    if (item is com.metrolist.innertube.models.AlbumItem && !database.isAlbumBookmarked(item.browseId)) {
                        database.toggleAlbumBookmark(item.toDesktopAlbum())
                    }
                }
                showStatus("Sincronización completada")
            }.onFailure {
                showStatus("Error al sincronizar: ${it.message}")
            }
        }
    }

    fun logout() {
        scope.launch {
            preferences.clearSession()
            showStatus("Sesión cerrada")
        }
    }

    fun shutdown() {
        alarmScheduler.stop()
        listenTogetherManager.disconnect()
        discordRpc.shutdown()
        audioPlayer.release()
        proxyServer.close()
        smtcManager.shutdown()
    }
}

