package com.metrolist.desktop.state

import com.metrolist.desktop.AppPaths
import com.metrolist.desktop.audio.DesktopAudioPlayer
import com.metrolist.desktop.audio.DesktopAutoEqSearch
import com.metrolist.desktop.audio.DownloadManager
import com.metrolist.desktop.audio.InnerTubeStreamResolver
import com.metrolist.desktop.audio.PlaybackState
import com.metrolist.desktop.audio.StreamProxyServer
import com.metrolist.desktop.auth.WindowsWebView2Auth
import com.metrolist.desktop.backup.BackupRestoreService
import com.metrolist.desktop.data.DesktopPlaylistAddPosition
import com.metrolist.desktop.data.DesktopPrefKeys
import com.metrolist.desktop.data.DesktopPreferences
import com.metrolist.desktop.data.DesktopProxyType
import com.metrolist.desktop.data.DesktopSettings
import com.metrolist.desktop.db.DesktopAlarmEntry
import com.metrolist.desktop.db.DesktopAlbum
import com.metrolist.desktop.db.DesktopArtist
import com.metrolist.desktop.db.DesktopDatabase
import com.metrolist.desktop.db.DesktopPlaylist
import com.metrolist.desktop.db.DesktopPodcast
import com.metrolist.desktop.db.DesktopRecognitionHistoryItem
import com.metrolist.desktop.db.DesktopSong
import com.metrolist.desktop.db.DesktopSpeedDialItem
import com.metrolist.desktop.db.toDesktopAlbum
import com.metrolist.desktop.db.toDesktopArtist
import com.metrolist.desktop.db.toDesktopPlaylist
import com.metrolist.desktop.db.toDesktopPodcast
import com.metrolist.desktop.db.toDesktopSong
import com.metrolist.desktop.integrations.DesktopAlarmScheduler
import com.metrolist.desktop.integrations.DesktopDiscordRpc
import com.metrolist.desktop.integrations.DesktopLastFmService
import com.metrolist.desktop.integrations.DesktopListenTogetherManager
import com.metrolist.desktop.lyrics.DesktopLyricsService
import com.metrolist.desktop.lyrics.LyricsPayload
import com.metrolist.desktop.recognition.DesktopMusicRecognizer
import com.metrolist.desktop.util.DesktopReleaseInfo
import com.metrolist.desktop.util.DesktopUpdater
import com.metrolist.desktop.util.ParsedYouTubeLink
import com.metrolist.desktop.windows.WindowsSmtcManager
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.innertube.utils.parseCookieString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

sealed interface NavDestination {
    data object Home : NavDestination
    data object Explore : NavDestination
    data class Search(val query: String = "") : NavDestination
    data class Library(val initialTab: Int = 0) : NavDestination
    data object Stats : NavDestination
    data object Recognition : NavDestination
    data class Artist(val artistId: String) : NavDestination
    data class Album(val albumId: String) : NavDestination
    data class Playlist(val playlistId: String, val isLocal: Boolean = false) : NavDestination
    data class Podcast(val podcastId: String) : NavDestination
    data class Browse(val browseId: String, val params: String? = null, val title: String = "") : NavDestination
    data object Settings : NavDestination
}

enum class LyricsToolMode { EDIT, SEARCH, OFFSET, SHARE_IMAGE }

class DesktopAppState {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val preferences = DesktopPreferences(scope)
    val database = DesktopDatabase()
    val backupService = BackupRestoreService(database, preferences)
    val streamResolver = InnerTubeStreamResolver(
        onFormatResolved = { fmt -> database.saveFormat(fmt) },
    )
    val autoEqSearch = DesktopAutoEqSearch()

    private val _settings = MutableStateFlow(DesktopSettings())
    val settings: StateFlow<DesktopSettings> = _settings.asStateFlow()

    val proxyServer = StreamProxyServer(
        resolver = streamResolver,
        qualityProvider = { _settings.value.audioQuality },
        downloadsDirProvider = { File(_settings.value.downloadsDir) },
        cacheEnabledProvider = { _settings.value.enableSongCache },
        maxCacheSizeMbProvider = { _settings.value.maxSongCacheSizeMb },
        onSongCached = { songId -> database.setCached(songId, true) },
    )

    val audioPlayer: DesktopAudioPlayer = DesktopAudioPlayer(
        database = database,
        proxyServer = proxyServer,
        settingsProvider = { _settings.value },
        onVolumePersist = { vol ->
            scope.launch { preferences.set(DesktopPrefKeys.Volume, vol) }
            listenTogetherManager.broadcastHostVolume(vol)
        },
        onSpeedPersist = { spd -> scope.launch { preferences.set(DesktopPrefKeys.PlaybackSpeed, spd) } },
        onPitchPersist = { pitch -> scope.launch { preferences.set(DesktopPrefKeys.PlaybackPitch, pitch) } },
        onEqPersist = { enabled, preset, preamp, bands ->
            scope.launch {
                preferences.set(DesktopPrefKeys.EqEnabled, enabled)
                preferences.set(DesktopPrefKeys.EqPreset, preset)
                preferences.set(DesktopPrefKeys.EqPreamp, preamp)
                preferences.set(DesktopPrefKeys.EqBands, bands.joinToString(","))
            }
        },
        onSeekBroadcast = { pos -> listenTogetherManager.broadcastHostSeek(pos) },
        onQueueBroadcast = { queue, _ -> listenTogetherManager.broadcastHostQueue(queue) },
    )

    val downloadManager = DownloadManager(
        database = database,
        resolver = streamResolver,
        qualityProvider = { _settings.value.audioQuality },
        downloadsDirProvider = { File(_settings.value.downloadsDir) },
    )

    val lyricsService = DesktopLyricsService(database)
    val musicRecognizer = DesktopMusicRecognizer(database, scope)

    val discordRpc = DesktopDiscordRpc(scope)
    val alarmScheduler = DesktopAlarmScheduler(
        database = database,
        audioPlayer = audioPlayer,
        onStatusMessage = ::showStatus,
        scope = scope,
    )
    val listenTogetherManager: DesktopListenTogetherManager = DesktopListenTogetherManager(
        audioPlayer = audioPlayer,
        settingsProvider = { _settings.value },
        onBlockUsername = { username ->
            scope.launch {
                val updated = _settings.value.listenTogetherBlockedUsers + username
                preferences.set(DesktopPrefKeys.ListenTogetherBlockedUsers, updated.joinToString(","))
            }
        },
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
    val uploadedSongs: StateFlow<List<DesktopSong>> = database.observe { database.uploadedSongs() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val topSongs: StateFlow<List<DesktopSong>> = database.observe { database.topSongs(_settings.value.topSize) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val cachedSongs: StateFlow<List<DesktopSong>> = database.observe { database.cachedSongs() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val playlists: StateFlow<List<DesktopPlaylist>> = database.observe { database.playlists() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val bookmarkedAlbums: StateFlow<List<DesktopAlbum>> = database.observe { database.bookmarkedAlbums() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val bookmarkedArtists: StateFlow<List<DesktopArtist>> = database.observe { database.bookmarkedArtists() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val bookmarkedPodcasts: StateFlow<List<DesktopPodcast>> = database.observe { database.bookmarkedPodcasts() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val speedDialItems: StateFlow<List<DesktopSpeedDialItem>> = database.observe { database.speedDialItems() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val recognitionHistory: StateFlow<List<DesktopRecognitionHistoryItem>> = database.observe { database.recognitionHistory() }
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
    private val _showTimeTransferDialog = MutableStateFlow(false)
    val showTimeTransferDialog: StateFlow<Boolean> = _showTimeTransferDialog.asStateFlow()
    private val _showChangelogDialog = MutableStateFlow(false)
    val showChangelogDialog: StateFlow<Boolean> = _showChangelogDialog.asStateFlow()
    private val _showLoginDialog = MutableStateFlow(false)
    val showLoginDialog: StateFlow<Boolean> = _showLoginDialog.asStateFlow()
    private val _playlistPickerSongs = MutableStateFlow<List<DesktopSong>>(emptyList())
    val playlistPickerSongs: StateFlow<List<DesktopSong>> = _playlistPickerSongs.asStateFlow()
    private val _songDetailsTarget = MutableStateFlow<DesktopSong?>(null)
    val songDetailsTarget: StateFlow<DesktopSong?> = _songDetailsTarget.asStateFlow()
    private val _songEditTarget = MutableStateFlow<DesktopSong?>(null)
    val songEditTarget: StateFlow<DesktopSong?> = _songEditTarget.asStateFlow()
    private val _lyricsToolMode = MutableStateFlow<LyricsToolMode?>(null)
    val lyricsToolMode: StateFlow<LyricsToolMode?> = _lyricsToolMode.asStateFlow()

    // Current song lyrics & translation state
    private val _currentLyrics = MutableStateFlow<LyricsPayload?>(null)
    val currentLyrics: StateFlow<LyricsPayload?> = _currentLyrics.asStateFlow()
    private val _lyricsLoading = MutableStateFlow(false)
    val lyricsLoading: StateFlow<Boolean> = _lyricsLoading.asStateFlow()
    private val _lyricsTranslating = MutableStateFlow(false)
    val lyricsTranslating: StateFlow<Boolean> = _lyricsTranslating.asStateFlow()
    private val _showLyricsTranslation = MutableStateFlow(true)
    val showLyricsTranslation: StateFlow<Boolean> = _showLyricsTranslation.asStateFlow()

    // Update info
    private val _latestRelease = MutableStateFlow<DesktopReleaseInfo?>(null)
    val latestRelease: StateFlow<DesktopReleaseInfo?> = _latestRelease.asStateFlow()
    private val _updateAvailable = MutableStateFlow(false)
    val updateAvailable: StateFlow<Boolean> = _updateAvailable.asStateFlow()

    // Toast / snackbar status
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    @Volatile
    private var localModalOpen: Boolean = false

    fun setLocalModalOpen(open: Boolean) {
        localModalOpen = open
    }

    init {
        var appliedInitialSettings = false
        scope.launch {
            preferences.settingsFlow.collectLatest { st ->
                val prev = _settings.value
                _settings.value = st
                syncInnerTubeSession(st)
                smtcManager.setEnabled(st.enableSmtc)
                alarmScheduler.updateFromRawJson(st.alarmEntriesJson)
                audioPlayer.setPlaybackSpeed(st.playbackSpeed, persist = false)
                audioPlayer.setPlaybackPitch(st.playbackPitch, persist = false)
                audioPlayer.setEqualizer(
                    enabled = st.eqEnabled,
                    preset = st.eqPreset,
                    preamp = st.eqPreamp,
                    bands = st.eqBands,
                    persist = false,
                )
                if (prev.audioNormalization != st.audioNormalization || prev.loudnessLevel != st.loudnessLevel) {
                    audioPlayer.reapplyNormalization()
                }
                if (!appliedInitialSettings) {
                    appliedInitialSettings = true
                    audioPlayer.restorePersistedQueueIfEnabled()
                    when (st.defaultOpenTab.uppercase()) {
                        "SEARCH" -> navigate(NavDestination.Search())
                        "LIBRARY" -> navigate(NavDestination.Library())
                        "EXPLORE" -> navigate(NavDestination.Explore)
                    }
                    if (st.checkForUpdates) {
                        checkForUpdatesSilently()
                    }
                }
            }
        }
        scope.launch {
            var lastSongId: String? = null
            var lastPlaying: Boolean? = null
            var lastDiscordUpdateMs = 0L
            var scrobbledCurrentSong = false
            var reportedYtmPlayback = false
            var currentSongStartedEpochSec = 0L

            audioPlayer.state.collectLatest { pb ->
                val song = pb.currentSong
                val st = _settings.value
                val trackChanged = song?.id != lastSongId
                val playingChanged = pb.isPlaying != lastPlaying

                if (trackChanged) {
                    lastSongId = song?.id
                    scrobbledCurrentSong = false
                    reportedYtmPlayback = false
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
                    smtcManager.updateTimeline(pb.positionMs, pb.durationMs, force = trackChanged || playingChanged)
                }

                // Report YTM remote playback history after 10s of real playback
                if (!reportedYtmPlayback && song != null && pb.isPlaying && pb.accumulatedPlayMs >= 10_000L && st.isLoggedIn && !st.pauseListenHistory) {
                    reportedYtmPlayback = true
                    val trackingUrl = streamResolver.getCachedStream(song.id)?.playbackTrackingUrl
                    if (!trackingUrl.isNullOrBlank()) {
                        scope.launch { runCatching { YouTube.registerPlayback(playbackTracking = trackingUrl) } }
                    }
                }

                // Last.fm Scrobble using real accumulated playback time (no seek loophole)
                if (!scrobbledCurrentSong && song != null && pb.isPlaying) {
                    val durSec = (pb.durationMs / 1000L).takeIf { it > 0L } ?: song.duration.toLong()
                    if (durSec >= st.scrobbleMinSongDurationSec.toLong()) {
                        val pctMs = (durSec * 1000L * st.scrobbleDelayPercent).toLong()
                        val capMs = st.scrobbleDelaySeconds * 1000L
                        val thresholdMs = minOf(pctMs, capMs).coerceAtLeast(10_000L)
                        if (pb.accumulatedPlayMs >= thresholdMs) {
                            scrobbledCurrentSong = true
                            val startedAt = currentSongStartedEpochSec.takeIf { it > 0L } ?: (System.currentTimeMillis() / 1000L)
                            scope.launch {
                                runCatching { DesktopLastFmService.scrobble(song, startedAt, st) }
                            }
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
        YouTube.useLoginForBrowse = st.isLoggedIn && st.useLoginForBrowse

        // Apply optional HTTP/SOCKS proxy to YouTube InnerTube client
        if (st.proxyEnabled && st.proxyUrl.isNotBlank()) {
            val cleaned = st.proxyUrl.trim().removePrefix("http://").removePrefix("https://").removePrefix("socks5://")
            val host = cleaned.substringBefore(":")
            val port = cleaned.substringAfter(":", "8080").toIntOrNull() ?: 8080
            if (host.isNotBlank() && port in 1..65535) {
                val type = if (st.proxyType == DesktopProxyType.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP
                YouTube.proxy = Proxy(type, InetSocketAddress(host, port))
                YouTube.proxyAuth = if (st.proxyUsername.isNotBlank()) {
                    val creds = "${st.proxyUsername}:${st.proxyPassword}"
                    "Basic " + java.util.Base64.getEncoder().encodeToString(creds.toByteArray(Charsets.UTF_8))
                } else null
            }
        } else {
            YouTube.proxy = null
            YouTube.proxyAuth = null
        }
    }

    fun checkForUpdatesSilently(force: Boolean = false) {
        scope.launch {
            DesktopUpdater.checkForUpdate(force).onSuccess { (release, hasUpdate) ->
                _latestRelease.value = release
                _updateAvailable.value = hasUpdate
                if (force) {
                    showStatus(
                        if (hasUpdate && release != null) "Nueva versión disponible: v${release.displayVersion}"
                        else "miliMusic está actualizado (v${DesktopUpdater.CURRENT_VERSION})",
                    )
                }
            }.onFailure {
                if (force) showStatus("No se pudo comprobar actualizaciones: ${it.message}")
            }
        }
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

    fun openYouTubeLink(link: ParsedYouTubeLink) {
        when (link) {
            is ParsedYouTubeLink.Video -> {
                scope.launch {
                    showStatus("Abriendo pista de YouTube...")
                    val song = runCatching {
                        YouTube.queue(videoIds = listOf(link.videoId)).getOrNull()?.firstOrNull()?.toDesktopSong()
                    }.getOrNull()
                    if (song != null) {
                        audioPlayer.playSong(song)
                    } else {
                        showStatus("No se pudo cargar el video ${link.videoId}")
                    }
                }
            }
            is ParsedYouTubeLink.Playlist -> navigate(NavDestination.Playlist(link.playlistId, isLocal = false))
            is ParsedYouTubeLink.Album -> navigate(NavDestination.Album(link.browseId))
            is ParsedYouTubeLink.Artist -> navigate(NavDestination.Artist(link.channelId))
            is ParsedYouTubeLink.Podcast -> navigate(NavDestination.Podcast(link.podcastId))
        }
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

    fun openEqualizerDialog() { _showEqualizerDialog.value = true }
    fun closeEqualizerDialog() { _showEqualizerDialog.value = false }
    fun openSleepTimerDialog() { _showSleepTimerDialog.value = true }
    fun closeSleepTimerDialog() { _showSleepTimerDialog.value = false }
    fun openListenTogetherDialog() { _showListenTogetherDialog.value = true }
    fun closeListenTogetherDialog() { _showListenTogetherDialog.value = false }
    fun openWrappedDialog() { _showWrappedDialog.value = true }
    fun closeWrappedDialog() { _showWrappedDialog.value = false }
    fun openTimeTransferDialog() { _showTimeTransferDialog.value = true }
    fun closeTimeTransferDialog() { _showTimeTransferDialog.value = false }
    fun openChangelogDialog() { _showChangelogDialog.value = true }
    fun closeChangelogDialog() { _showChangelogDialog.value = false }
    fun openLoginDialog() { _showLoginDialog.value = true }
    fun closeLoginDialog() { _showLoginDialog.value = false }
    fun openSongDetails(song: DesktopSong) { _songDetailsTarget.value = song }
    fun closeSongDetails() { _songDetailsTarget.value = null }
    fun openSongEdit(song: DesktopSong) { _songEditTarget.value = song }
    fun closeSongEdit() { _songEditTarget.value = null }
    fun openLyricsTool(mode: LyricsToolMode) { _lyricsToolMode.value = mode }
    fun closeLyricsTool() { _lyricsToolMode.value = null }

    fun openPlaylistPicker(song: DesktopSong) {
        _playlistPickerSongs.value = listOf(song)
    }

    fun openPlaylistPickerBatch(songs: List<DesktopSong>) {
        if (songs.isNotEmpty()) _playlistPickerSongs.value = songs
    }

    fun closePlaylistPicker() {
        _playlistPickerSongs.value = emptyList()
    }

    fun saveAlarms(entries: List<DesktopAlarmEntry>) {
        scope.launch {
            val serialized = alarmScheduler.serializeAlarms(entries)
            preferences.set(DesktopPrefKeys.AlarmEntries, serialized)
        }
    }

    fun showStatus(message: String) {
        _statusMessage.value = message
    }

    fun clearStatus() {
        _statusMessage.value = null
    }

    // Song & Library mutations with two-way YTM sync
    fun toggleLike(song: DesktopSong) {
        scope.launch {
            val updated = database.toggleLike(song)
            if (_settings.value.isLoggedIn && _settings.value.ytmSync) {
                runCatching { YouTube.likeVideo(updated.id, updated.liked) }
            }
            if (updated.liked && _settings.value.autoDownloadOnLike && !updated.isDownloaded) {
                downloadManager.download(updated)
            }
            runCatching { DesktopLastFmService.setLoveStatus(updated, updated.liked, _settings.value) }
        }
    }

    fun toggleInLibrary(song: DesktopSong) {
        scope.launch {
            val updated = database.toggleInLibrary(song)
            val token = if (updated.inLibrary != null) updated.libraryAddToken else updated.libraryRemoveToken
            if (_settings.value.isLoggedIn && !token.isNullOrBlank()) {
                runCatching { YouTube.feedback(listOf(token)) }
            }
        }
    }

    fun toggleArtistSubscribe(artist: DesktopArtist) {
        scope.launch {
            val nowBookmarked = database.toggleArtistBookmark(artist)
            val chId = artist.channelId ?: artist.id
            if (_settings.value.isLoggedIn && _settings.value.ytmSync && chId.startsWith("UC")) {
                runCatching { YouTube.subscribeChannel(chId, nowBookmarked) }
            }
            showStatus(if (nowBookmarked) "Suscrito a ${artist.displayName}" else "Suscripción eliminada")
        }
    }

    fun togglePodcastBookmark(podcast: DesktopPodcast) {
        scope.launch {
            val saved = database.togglePodcastBookmark(podcast)
            if (_settings.value.isLoggedIn && _settings.value.ytmSync) {
                runCatching { YouTube.savePodcast(podcast.id, saved) }
            }
            showStatus(if (saved) "Podcast guardado en biblioteca" else "Podcast quitado de biblioteca")
        }
    }

    fun toggleSpeedDialSong(song: DesktopSong) {
        scope.launch {
            val pinned = database.toggleSpeedDial(
                DesktopSpeedDialItem(
                    id = song.id,
                    title = song.title,
                    subtitle = song.artistDisplay,
                    thumbnailUrl = song.thumbnailUrl,
                    type = "SONG",
                    explicit = song.explicit,
                    albumId = song.albumId,
                    albumName = song.albumName,
                ),
            )
            showStatus(if (pinned) "Fijado en Marcación rápida" else "Quitado de Marcación rápida")
        }
    }

    fun toggleSpeedDialAlbum(album: DesktopAlbum) {
        scope.launch {
            val pinned = database.toggleSpeedDial(
                DesktopSpeedDialItem(
                    id = album.id,
                    secondaryId = album.playlistId,
                    title = album.title,
                    subtitle = album.artists.joinToString(", ") { it.name },
                    thumbnailUrl = album.thumbnailUrl,
                    type = "ALBUM",
                    explicit = album.explicit,
                ),
            )
            showStatus(if (pinned) "Álbum fijado en Marcación rápida" else "Álbum quitado de Marcación rápida")
        }
    }

    fun toggleSpeedDialArtist(artist: DesktopArtist) {
        scope.launch {
            val pinned = database.toggleSpeedDial(
                DesktopSpeedDialItem(
                    id = artist.id,
                    title = artist.displayName,
                    thumbnailUrl = artist.thumbnailUrl,
                    type = "ARTIST",
                ),
            )
            showStatus(if (pinned) "Artista fijado en Marcación rápida" else "Artista quitado de Marcación rápida")
        }
    }

    fun toggleSpeedDialPlaylist(playlist: DesktopPlaylist) {
        scope.launch {
            val pinned = database.toggleSpeedDial(
                DesktopSpeedDialItem(
                    id = playlist.id,
                    secondaryId = playlist.browseId,
                    title = playlist.name,
                    thumbnailUrl = playlist.thumbnailUrl,
                    type = if (playlist.isLocal) "LOCAL_PLAYLIST" else "PLAYLIST",
                ),
            )
            showStatus(if (pinned) "Playlist fijada en Marcación rápida" else "Playlist quitada de Marcación rápida")
        }
    }

    fun createPlaylist(name: String, syncToYtm: Boolean = false) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            if (syncToYtm && _settings.value.isLoggedIn) {
                val remoteId = runCatching { YouTube.createPlaylist(trimmed) }.getOrNull()
                if (!remoteId.isNullOrBlank()) {
                    database.upsertRemotePlaylist(
                        DesktopPlaylist(id = remoteId, name = trimmed, browseId = remoteId, isEditable = true, isLocal = false),
                    )
                    showStatus("Playlist sincronizada creada en YouTube Music: $trimmed")
                    return@launch
                }
            }
            database.createPlaylist(trimmed)
            showStatus("Playlist local creada: $trimmed")
        }
    }

    fun addSongsToPlaylist(playlist: DesktopPlaylist, songs: List<DesktopSong>) {
        if (songs.isEmpty()) return
        val atBeginning = _settings.value.addToPlaylistPosition == DesktopPlaylistAddPosition.BEGINNING
        scope.launch {
            songs.forEach { song ->
                database.addSongToPlaylistAt(playlist.id, song, atBeginning = atBeginning)
                if (!playlist.isLocal && _settings.value.isLoggedIn && _settings.value.ytmSync) {
                    runCatching { YouTube.addToPlaylist(playlist.browseId ?: playlist.id, song.id) }
                }
            }
            showStatus(
                if (songs.size == 1) "Añadida a \"${playlist.name}\""
                else "${songs.size} canciones añadidas a \"${playlist.name}\"",
            )
        }
    }

    fun removeSongFromPlaylist(playlist: DesktopPlaylist, songId: String) {
        scope.launch {
            val setVideoId = database.getPlaylistSetVideoId(playlist.id, songId)
            database.removeSongFromPlaylist(playlist.id, songId)
            if (!playlist.isLocal && _settings.value.isLoggedIn && _settings.value.ytmSync && !setVideoId.isNullOrBlank()) {
                runCatching { YouTube.removeFromPlaylist(playlist.browseId ?: playlist.id, songId, setVideoId) }
            }
        }
    }

    fun renamePlaylist(playlist: DesktopPlaylist, newName: String) {
        val clean = newName.trim()
        if (clean.isEmpty()) return
        scope.launch {
            database.renamePlaylist(playlist.id, clean)
            if (!playlist.isLocal && _settings.value.isLoggedIn && _settings.value.ytmSync) {
                runCatching { YouTube.renamePlaylist(playlist.browseId ?: playlist.id, clean) }
            }
            showStatus("Playlist renombrada a $clean")
        }
    }

    fun deletePlaylist(playlist: DesktopPlaylist) {
        scope.launch {
            database.deletePlaylist(playlist.id)
            if (!playlist.isLocal && _settings.value.isLoggedIn && _settings.value.ytmSync) {
                runCatching { YouTube.deletePlaylist(playlist.browseId ?: playlist.id) }
            }
            showStatus("Playlist eliminada")
        }
    }

    fun uploadAudioFilesToYtm(files: List<File>) {
        if (!_settings.value.isLoggedIn) {
            showStatus("Inicia sesión en YouTube Music para subir archivos de audio")
            return
        }
        scope.launch {
            var ok = 0
            files.forEachIndexed { idx, file ->
                showStatus("Subiendo ${idx + 1}/${files.size}: ${file.name}...")
                val result = runCatching {
                    YouTube.uploadSong(
                        filename = file.name,
                        contentLength = file.length(),
                        content = { file.inputStream().buffered() },
                    )
                }.getOrNull()
                if (result?.isSuccess == true) {
                    ok++
                }
            }
            showStatus("Subida finalizada: $ok/${files.size} archivos enviados a YouTube Music")
            if (ok > 0) syncLibraryWithYtm()
        }
    }

    fun deleteUploadedSong(song: DesktopSong) {
        scope.launch {
            val entityId = song.uploadEntityId ?: song.id
            if (_settings.value.isLoggedIn) {
                runCatching { YouTube.deleteUploadedSong(entityId) }
            }
            database.deleteSong(song.id)
            showStatus("Canción subida eliminada")
        }
    }

    // Lyrics controls
    fun loadLyrics(song: DesktopSong, forceRefresh: Boolean = false) {
        scope.launch {
            _lyricsLoading.value = true
            val dbSong = database.getSong(song.id) ?: song
            _currentLyrics.value = lyricsService.getLyrics(dbSong, _settings.value, forceRefresh = forceRefresh)
            _lyricsLoading.value = false
        }
    }

    fun refetchCurrentLyrics() {
        val song = audioPlayer.state.value.currentSong ?: return
        loadLyrics(song, forceRefresh = true)
    }

    fun translateCurrentLyrics() {
        val song = audioPlayer.state.value.currentSong ?: return
        val payload = _currentLyrics.value ?: return
        if (_lyricsTranslating.value) return
        scope.launch {
            _lyricsTranslating.value = true
            lyricsService.translatePayload(song, payload, _settings.value)
                .onSuccess { updated ->
                    _currentLyrics.value = updated
                    _showLyricsTranslation.value = true
                    showStatus("Letras traducidas (${_settings.value.translateLanguage})")
                }
                .onFailure { err ->
                    showStatus(err.message ?: "Error al traducir letras")
                }
            _lyricsTranslating.value = false
        }
    }

    fun toggleLyricsTranslationVisibility() {
        _showLyricsTranslation.value = !_showLyricsTranslation.value
    }

    fun saveManualLyrics(rawText: String, provider: String = "Manual") {
        val song = audioPlayer.state.value.currentSong ?: return
        scope.launch {
            val dbSong = database.getSong(song.id) ?: song
            _currentLyrics.value = lyricsService.applyManualLyrics(dbSong, rawText, _settings.value, provider)
            showStatus("Letras guardadas ($provider)")
        }
    }

    fun setCurrentSongLyricsOffset(offsetMs: Int) {
        val song = audioPlayer.state.value.currentSong ?: return
        scope.launch {
            database.setSongLyricsOffset(song.id, offsetMs)
        }
    }

    fun toggleCurrentSongRomanization() {
        val song = audioPlayer.state.value.currentSong ?: return
        scope.launch {
            val cur = database.getSong(song.id)?.romanizeLyrics ?: true
            database.setSongRomanizeLyrics(song.id, !cur)
            val updated = database.getSong(song.id) ?: song.copy(romanizeLyrics = !cur)
            _currentLyrics.value?.let { payload ->
                _currentLyrics.value = lyricsService.buildPayload(
                    raw = payload.rawLyrics,
                    provider = payload.provider,
                    song = updated,
                    settings = _settings.value,
                    cachedTranslated = payload.translatedLines,
                    cachedTranslationLang = payload.translationLanguage,
                )
            }
        }
    }

    // Auth, Channel Switching & Full Paginated YTM Sync
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

    fun switchChannel(
        authUserIndex: String,
        name: String,
        email: String,
        handle: String,
        dataSyncId: String? = null,
    ) {
        scope.launch {
            val st = _settings.value
            preferences.updateSession(
                cookie = st.cookie,
                visitorData = st.visitorData,
                dataSyncId = dataSyncId?.takeIf { it.isNotBlank() } ?: st.dataSyncId,
                authUser = authUserIndex,
                accountName = name,
                accountEmail = email,
                accountChannelHandle = handle,
            )
            showStatus("Canal cambiado a $name")
        }
    }

    fun syncLibraryWithYtm() {
        if (!_settings.value.isLoggedIn) {
            showStatus("Inicia sesión primero para sincronizar con YouTube Music")
            return
        }
        scope.launch {
            showStatus("Sincronizando biblioteca completa con YouTube Music...")
            runCatching {
                val now = System.currentTimeMillis()
                suspend fun fetchAllLibraryPages(browseId: String): List<com.metrolist.innertube.models.YTItem> {
                    val items = mutableListOf<com.metrolist.innertube.models.YTItem>()
                    var page = YouTube.library(browseId).getOrNull() ?: return items
                    items += page.items
                    var cont = page.continuation
                    var pages = 1
                    while (!cont.isNullOrBlank() && pages < 8) {
                        val next = YouTube.libraryContinuation(cont).getOrNull() ?: break
                        items += next.items
                        cont = next.continuation
                        pages++
                    }
                    return items
                }

                fetchAllLibraryPages("FEmusic_liked_playlists").filterIsInstance<PlaylistItem>().forEach { item ->
                    database.upsertRemotePlaylist(item.toDesktopPlaylist())
                }
                fetchAllLibraryPages("FEmusic_liked_videos").filterIsInstance<SongItem>().forEach { item ->
                    database.upsertSong(item.toDesktopSong().copy(liked = true, inLibrary = now))
                }
                fetchAllLibraryPages("FEmusic_library_corpus_track_artists").filterIsInstance<ArtistItem>().forEach { item ->
                    if (!database.isArtistBookmarked(item.id)) {
                        database.toggleArtistBookmark(item.toDesktopArtist())
                    }
                }
                fetchAllLibraryPages("FEmusic_liked_albums").filterIsInstance<AlbumItem>().forEach { item ->
                    if (!database.isAlbumBookmarked(item.browseId)) {
                        database.toggleAlbumBookmark(item.toDesktopAlbum())
                    }
                }
                fetchAllLibraryPages("FEmusic_library_privately_owned_tracks").filterIsInstance<SongItem>().forEach { item ->
                    database.markSongUploaded(item.toDesktopSong())
                }
                runCatching {
                    YouTube.savedPodcastShows().getOrNull().orEmpty().forEach { show: PodcastItem ->
                        if (!database.isPodcastBookmarked(show.id)) {
                            database.togglePodcastBookmark(show.toDesktopPodcast())
                        }
                    }
                }
                showStatus("Sincronización completa finalizada")
            }.onFailure {
                showStatus("Error al sincronizar: ${it.message}")
            }
        }
    }

    fun logout(clearLocalLibrary: Boolean = false) {
        scope.launch {
            preferences.clearSession()
            if (clearLocalLibrary) {
                database.clearLibraryData()
                showStatus("Sesión cerrada y biblioteca remota limpiada")
            } else {
                showStatus("Sesión cerrada (datos locales conservados)")
            }
        }
    }

    fun clearImageCache() {
        scope.launch {
            var deleted = 0
            AppPaths.imageCacheDir.walkTopDown().forEach { f ->
                if (f.isFile && f.delete()) deleted++
            }
            showStatus("Caché de imágenes limpiada ($deleted archivos)")
        }
    }

    fun clearSongCache() {
        scope.launch {
            val deleted = proxyServer.clearSongCache()
            database.cachedSongs().forEach { database.setCached(it.id, false) }
            showStatus("Caché de canciones limpiada ($deleted archivos)")
        }
    }

    private val isShutDown = AtomicBoolean(false)

    val hasOpenDialog: Boolean
        get() = localModalOpen ||
            _showLoginDialog.value ||
            _playlistPickerSongs.value.isNotEmpty() ||
            _showEqualizerDialog.value ||
            _showSleepTimerDialog.value ||
            _showListenTogetherDialog.value ||
            _showWrappedDialog.value ||
            _showTimeTransferDialog.value ||
            _showChangelogDialog.value ||
            _songDetailsTarget.value != null ||
            _songEditTarget.value != null ||
            _lyricsToolMode.value != null

    fun shutdown() {
        if (!isShutDown.compareAndSet(false, true)) return
        runCatching { alarmScheduler.stop() }
        runCatching { musicRecognizer.close() }
        runCatching { listenTogetherManager.shutdown() }
        runCatching { discordRpc.shutdown() }
        runCatching { downloadManager.close() }
        runCatching { autoEqSearch.close() }
        runCatching { DesktopUpdater.close() }
        // Close local proxy first so any blocking libVLC HTTP read unblocks immediately.
        runCatching { proxyServer.close() }
        runCatching { audioPlayer.release() }
        runCatching { lyricsService.close() }
        runCatching { streamResolver.close() }
        runCatching { DesktopLastFmService.close() }
        runCatching { smtcManager.shutdown() }
        scope.cancel()
    }
}
