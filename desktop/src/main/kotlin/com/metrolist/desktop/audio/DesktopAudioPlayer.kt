package com.metrolist.desktop.audio

import com.metrolist.desktop.AppPaths
import com.metrolist.desktop.data.DesktopLoudnessLevel
import com.metrolist.desktop.data.DesktopSettings
import com.metrolist.desktop.db.DesktopDatabase
import com.metrolist.desktop.db.DesktopSong
import com.metrolist.desktop.db.toDesktopSong
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.WatchEndpoint
import com.sun.jna.NativeLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uk.co.caprica.vlcj.binding.lib.LibC
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.factory.discovery.strategy.BaseNativeDiscoveryStrategy
import uk.co.caprica.vlcj.factory.discovery.strategy.WindowsNativeDiscoveryStrategy
import uk.co.caprica.vlcj.player.base.Equalizer
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.State
import uk.co.caprica.vlcj.player.component.AudioPlayerComponent
import java.io.File
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

enum class RepeatMode { OFF, ALL, ONE }

object DesktopEqPresets {
    // True libVLC 10-band center frequencies
    val bandLabels = DesktopParametricEq.vlcBandLabels

    val presets: Map<String, List<Float>> = linkedMapOf(
        "Flat" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Bass Boost" to listOf(7f, 6f, 4.5f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Rock" to listOf(5f, 3.5f, -2f, -3.5f, -1.5f, 2f, 4.5f, 5.5f, 5.5f, 5.5f),
        "Pop" to listOf(-1.5f, 2.5f, 4.5f, 5f, 3f, 0f, -1f, -1f, 1.5f, 2f),
        "Jazz" to listOf(3.5f, 2.5f, 1f, 2f, -1.5f, -1.5f, 0f, 1.5f, 3f, 4f),
        "Classical" to listOf(4.5f, 3.5f, 2.5f, 2f, -1.5f, -1.5f, 0f, 2f, 3.5f, 4.5f),
        "Electronic" to listOf(5.5f, 4.5f, 1.5f, 0f, -2f, 2f, 1f, 2f, 5f, 6f),
        "Vocal" to listOf(-2.5f, -2f, -1f, 2f, 4.5f, 5f, 4f, 2f, 0f, -1.5f),
        "Hip-Hop" to listOf(6.5f, 5.5f, 3f, 2f, -1f, -1f, 1.5f, 0f, 2.5f, 3.5f),
        "Acoustic" to listOf(4f, 3.5f, 2.5f, 1f, 2f, 2f, 3f, 3.5f, 3f, 2f),
    )
}

object LoudnessNormalizationMath {
    /**
     * Computes linear volume multiplier to align a track's loudnessDb toward target LUFS.
     * YouTube's loudnessDb is relative to -14 LUFS (positive = louder than -14 LUFS).
     */
    fun computeGainFactor(loudnessDb: Double?, targetLevel: DesktopLoudnessLevel): Float {
        if (loudnessDb == null || !loudnessDb.isFinite()) return 1f
        val trackLufs = -14.0 + loudnessDb
        val diffDb = (targetLevel.targetLufs - trackLufs).coerceIn(-12.0, 6.0)
        return 10.0.pow(diffDb / 20.0).toFloat().coerceIn(0.25f, 1.5f)
    }

    fun ratioToSemitones(pitchRatio: Float): Float =
        (12.0 * (ln(pitchRatio.coerceIn(0.5f, 2.0f).toDouble()) / ln(2.0))).toFloat().coerceIn(-12f, 12f)
}

@Serializable
data class PersistedQueueSnapshot(
    val queue: List<DesktopSong> = emptyList(),
    val currentIndex: Int = -1,
    val positionMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: String = RepeatMode.OFF.name,
)

data class PlaybackState(
    val queue: List<DesktopSong> = emptyList(),
    val automixQueue: List<DesktopSong> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val accumulatedPlayMs: Long = 0L,
    val volume: Float = 0.85f,
    val isMuted: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val playbackSpeed: Float = 1.0f,
    val playbackPitch: Float = 1.0f,
    val eqEnabled: Boolean = false,
    val eqPreset: String = "Flat",
    val eqPreamp: Float = 0f,
    val eqBands: List<Float> = List(10) { 0f },
    val normalizationGain: Float = 1f,
    val sleepTimerTargetEpochMs: Long? = null,
    val sleepTimerRemainingMs: Long? = null,
    val sleepTimerEndOfTrack: Boolean = false,
    val sleepTimerStopAfterTrack: Boolean = false,
    val vlcAvailable: Boolean = true,
    val errorMessage: String? = null,
) {
    val currentSong: DesktopSong?
        get() = queue.getOrNull(currentIndex)
}

class DesktopAudioPlayer(
    private val database: DesktopDatabase,
    private val proxyServer: StreamProxyServer,
    private val settingsProvider: () -> DesktopSettings = { DesktopSettings() },
    private val queueFile: File = File(AppPaths.dataDir, "playback_queue.json"),
    private val onVolumePersist: (Float) -> Unit = {},
    private val onSpeedPersist: (Float) -> Unit = {},
    private val onPitchPersist: (Float) -> Unit = {},
    private val onEqPersist: (Boolean, String, Float, List<Float>) -> Unit = { _, _, _, _ -> },
    private val onSeekBroadcast: (Long) -> Unit = {},
    private val onQueueBroadcast: (List<DesktopSong>, Int) -> Unit = { _, _ -> },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val queueJson = Json { ignoreUnknownKeys = true }
    private var mediaPlayerFactory: MediaPlayerFactory? = null
    private var audioComponent: AudioPlayerComponent? = null
    private var mediaPlayer: MediaPlayer? = null
    private var vlcEqualizer: Equalizer? = null
    private var positionJob: Job? = null
    private var playTrackJob: Job? = null
    private var recordedCurrentPlay: Boolean = false
    private var pendingRestoredSeekMs: Long = 0L
    private var startedCurrentMedia: Boolean = false
    private var fetchingAutoplay: Boolean = false
    private var restoredQueueOnce: Boolean = false
    private var crossfadeActive: Boolean = false
    private var crossfadeScale: Float = 1f
    private var sleepFadeScale: Float = 1f
    private var lastAutoSleepCheckMin: Int = -1
    private var lastScheduledSleepDateKey: String = ""
    private var consecutiveErrors: Int = 0
    private var pausedByMute: Boolean = false
    private var shuffleHistory = ArrayDeque<Int>()

    init {
        initVlc()
        startPositionTicker()
    }

    private fun createVlcDiscovery(bundledVlcDir: File?): NativeDiscovery {
        if (bundledVlcDir == null || !bundledVlcDir.exists() || !AppPaths.isWindows) {
            return NativeDiscovery()
        }
        val bundledStrategy = object : BaseNativeDiscoveryStrategy(
            arrayOf("libvlc\\.dll", "libvlccore\\.dll"),
            arrayOf("%s\\plugins", "%s\\vlc\\plugins"),
        ) {
            override fun supported(): Boolean = RuntimeUtil.isWindows()
            override fun discoveryDirectories(): List<String> = listOf(bundledVlcDir.absolutePath)
            override fun setPluginPath(path: String): Boolean =
                runCatching { LibC.INSTANCE._putenv("VLC_PLUGIN_PATH=$path") == 0 }.getOrDefault(false)
        }
        return NativeDiscovery(bundledStrategy, WindowsNativeDiscoveryStrategy())
    }

    private fun initVlc() {
        try {
            val bundledVlcDir = AppPaths.bundledFile("vlc")
            if (bundledVlcDir != null && bundledVlcDir.exists()) {
                NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcLibraryName(), bundledVlcDir.absolutePath)
                NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcCoreLibraryName(), bundledVlcDir.absolutePath)
            }
            val discovery = createVlcDiscovery(bundledVlcDir)
            val factory = MediaPlayerFactory(discovery, "--no-video", "--quiet")
            val component = AudioPlayerComponent(factory)
            val player = component.mediaPlayer()
            player.events().addMediaPlayerEventListener(
                object : MediaPlayerEventAdapter() {
                    override fun playing(mediaPlayer: MediaPlayer) {
                        consecutiveErrors = 0
                        _state.update { it.copy(isPlaying = true, isBuffering = false, errorMessage = null) }
                        if (pendingRestoredSeekMs > 0L) {
                            val target = pendingRestoredSeekMs
                            pendingRestoredSeekMs = 0L
                            runCatching { mediaPlayer.controls().setTime(target) }
                        }
                        applySpeedToPlayer()
                        applyEqualizerToPlayer()
                        applyVolumeToPlayer()
                    }

                    override fun paused(mediaPlayer: MediaPlayer) {
                        _state.update { it.copy(isPlaying = false, isBuffering = false) }
                        saveEpisodePositionIfNeeded()
                        persistQueueSnapshot()
                    }

                    override fun stopped(mediaPlayer: MediaPlayer) {
                        _state.update { it.copy(isPlaying = false, isBuffering = false) }
                    }

                    override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
                        _state.update { it.copy(isBuffering = newCache < 100f) }
                    }

                    override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                        if (newLength > 0L) {
                            _state.update { it.copy(durationMs = newLength) }
                        }
                    }

                    override fun finished(mediaPlayer: MediaPlayer) {
                        scope.launch { onTrackFinished() }
                    }

                    override fun error(mediaPlayer: MediaPlayer) {
                        _state.update {
                            it.copy(
                                isPlaying = false,
                                isBuffering = false,
                                errorMessage = "Error al reproducir la pista",
                            )
                        }
                        if (settingsProvider().autoSkipNextOnError && consecutiveErrors < 3) {
                            consecutiveErrors += 1
                            scope.launch {
                                delay(600L)
                                next()
                            }
                        }
                    }
                },
            )
            mediaPlayerFactory = factory
            audioComponent = component
            mediaPlayer = player
            vlcEqualizer = runCatching { factory.equalizer().newEqualizer() }.getOrNull()
            applyVolumeToPlayer()
            applySpeedToPlayer()
            applyEqualizerToPlayer()
        } catch (t: Throwable) {
            _state.update {
                it.copy(
                    vlcAvailable = false,
                    errorMessage = "libVLC no encontrado (${t.message ?: "instala VLC o usa el paquete Windows"})",
                )
            }
        }
    }

    private fun startPositionTicker() {
        positionJob = scope.launch {
            var tickCount = 0
            while (isActive) {
                val player = mediaPlayer
                val st = _state.value
                val cfg = settingsProvider()

                // Scheduled automatic sleep timer check (once per minute)
                checkScheduledSleepTimer(cfg, st)

                // Active sleep timer countdown + optional 60s gradual fade-out
                val targetSleep = st.sleepTimerTargetEpochMs
                if (targetSleep != null) {
                    val remaining = targetSleep - System.currentTimeMillis()
                    if (remaining <= 0L) {
                        sleepFadeScale = 1f
                        if (st.sleepTimerStopAfterTrack && st.isPlaying) {
                            _state.update {
                                it.copy(
                                    sleepTimerTargetEpochMs = null,
                                    sleepTimerRemainingMs = null,
                                    sleepTimerEndOfTrack = true,
                                    sleepTimerStopAfterTrack = false,
                                )
                            }
                        } else {
                            pause()
                            _state.update {
                                it.copy(
                                    sleepTimerTargetEpochMs = null,
                                    sleepTimerRemainingMs = null,
                                    sleepTimerEndOfTrack = false,
                                    sleepTimerStopAfterTrack = false,
                                )
                            }
                        }
                        applyVolumeToPlayer()
                    } else {
                        if (cfg.sleepTimerFadeOut && remaining <= 60_000L && !st.sleepTimerStopAfterTrack) {
                            sleepFadeScale = (remaining / 60_000f).coerceIn(0.05f, 1f)
                            applyVolumeToPlayer()
                        } else if (sleepFadeScale != 1f) {
                            sleepFadeScale = 1f
                            applyVolumeToPlayer()
                        }
                        _state.update { it.copy(sleepTimerRemainingMs = remaining) }
                    }
                } else if (sleepFadeScale != 1f) {
                    sleepFadeScale = 1f
                    applyVolumeToPlayer()
                }

                if (player != null && st.isPlaying) {
                    val time = player.status().time().coerceAtLeast(0L)
                    val len = player.status().length().takeIf { it > 0L }
                        ?: ((st.currentSong?.duration ?: 0) * 1000L).coerceAtLeast(0L)
                    val newAccum = st.accumulatedPlayMs + 250L
                    _state.update { it.copy(positionMs = time, durationMs = len, accumulatedPlayMs = newAccum) }

                    val current = st.currentSong
                    val historyThresholdMs = (cfg.historyDurationSec * 1000f).toLong().coerceAtLeast(1_000L)
                    if (!recordedCurrentPlay && current != null && !cfg.pauseListenHistory && newAccum >= historyThresholdMs) {
                        recordedCurrentPlay = true
                        val playDuration = len.takeIf { it > 0L } ?: 180_000L
                        runCatching { database.recordPlay(current, playDuration.coerceAtLeast(historyThresholdMs)) }
                    }

                    // Smooth software crossfade near end of track
                    handleCrossfadeTick(cfg, st, time, len)

                    tickCount += 1
                    if (tickCount % 20 == 0) {
                        saveEpisodePositionIfNeeded()
                        persistQueueSnapshot()
                    }
                }
                delay(250L)
            }
        }
    }

    private fun handleCrossfadeTick(cfg: DesktopSettings, st: PlaybackState, posMs: Long, lenMs: Long) {
        if (!cfg.crossfadeEnabled || lenMs <= 15_000L || st.repeatMode == RepeatMode.ONE) {
            if (crossfadeScale != 1f) {
                crossfadeScale = 1f
                applyVolumeToPlayer()
            }
            return
        }
        val nextIdx = computeNextIndex(st) ?: return
        val currAlbum = st.currentSong?.albumId
        val nextAlbum = st.queue.getOrNull(nextIdx)?.albumId
        if (cfg.crossfadeGapless && !currAlbum.isNullOrBlank() && currAlbum == nextAlbum) {
            return
        }
        val fadeMs = (cfg.crossfadeDurationSec * 1000f).toLong().coerceIn(1_000L, 15_000L)
        val remaining = lenMs - posMs
        if (remaining in 1L..fadeMs) {
            crossfadeScale = (remaining.toFloat() / fadeMs.toFloat()).coerceIn(0.08f, 1f)
            applyVolumeToPlayer()
            if (remaining <= 450L && !crossfadeActive) {
                crossfadeActive = true
                scope.launch {
                    jumpToIndex(nextIdx, fadeInMs = (fadeMs / 2L).coerceIn(400L, 3000L))
                    crossfadeActive = false
                }
            }
        } else if (crossfadeScale != 1f && !crossfadeActive) {
            crossfadeScale = 1f
            applyVolumeToPlayer()
        }
    }

    private fun checkScheduledSleepTimer(cfg: DesktopSettings, st: PlaybackState) {
        if (!cfg.sleepTimerEnabled || !st.isPlaying || st.sleepTimerTargetEpochMs != null) return
        val now = LocalDateTime.now()
        val minuteOfDay = now.hour * 60 + now.minute
        if (minuteOfDay == lastAutoSleepCheckMin) return
        lastAutoSleepCheckMin = minuteOfDay

        val dayOfWeek = now.dayOfWeek.value // 1=Mon..7=Sun
        if (cfg.sleepTimerRepeat == "CUSTOM" && dayOfWeek !in cfg.sleepTimerCustomDays) return

        val start = parseLocalTime(cfg.sleepTimerStartTime) ?: return
        val end = parseLocalTime(cfg.sleepTimerEndTime) ?: return
        val cur = now.toLocalTime()
        val inWindow = if (start <= end) cur >= start && cur < end else cur >= start || cur < end
        val dateKey = "${now.toLocalDate()}_${cfg.sleepTimerStartTime}"
        if (inWindow && dateKey != lastScheduledSleepDateKey) {
            lastScheduledSleepDateKey = dateKey
            setSleepTimer(
                minutes = cfg.sleepTimerDefaultMinutes.coerceAtLeast(1),
                endOfTrack = false,
                stopAfterCurrentSong = cfg.sleepTimerStopAfterCurrentSong,
            )
        }
    }

    private fun parseLocalTime(raw: String): LocalTime? {
        val parts = raw.trim().split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return runCatching { LocalTime.of(h, m) }.getOrNull()
    }

    private fun saveEpisodePositionIfNeeded() {
        val st = _state.value
        val song = st.currentSong ?: return
        if (song.isEpisode && st.positionMs > 2_000L) {
            val pos = if (st.durationMs > 0L && st.durationMs - st.positionMs < 5_000L) null else st.positionMs
            runCatching { database.setEpisodePlaybackPosition(song.id, pos) }
        }
    }

    fun restorePersistedQueueIfEnabled() {
        if (restoredQueueOnce) return
        restoredQueueOnce = true
        val cfg = settingsProvider()
        if (!cfg.persistentQueue || !queueFile.exists()) return
        runCatching {
            val snapshot = queueJson.decodeFromString<PersistedQueueSnapshot>(queueFile.readText())
            if (snapshot.queue.isEmpty()) return
            val safeIdx = snapshot.currentIndex.coerceIn(0, snapshot.queue.lastIndex)
            val song = snapshot.queue[safeIdx]
            pendingRestoredSeekMs = snapshot.positionMs.coerceAtLeast(0L)
            startedCurrentMedia = false
            _state.update {
                it.copy(
                    queue = snapshot.queue,
                    currentIndex = safeIdx,
                    positionMs = snapshot.positionMs.coerceAtLeast(0L),
                    durationMs = (song.duration.coerceAtLeast(0)) * 1000L,
                    shuffle = if (cfg.rememberShuffleAndRepeat) snapshot.shuffle else false,
                    repeatMode = if (cfg.rememberShuffleAndRepeat) {
                        runCatching { RepeatMode.valueOf(snapshot.repeatMode) }.getOrDefault(RepeatMode.OFF)
                    } else RepeatMode.OFF,
                    isPlaying = false,
                    isBuffering = false,
                )
            }
        }
    }

    // Kept for backwards compatibility with existing tests
    fun restorePersistedQueue() {
        restoredQueueOnce = false
        restorePersistedQueueIfEnabled()
    }

    private fun writeQueueSnapshotNow() {
        if (!settingsProvider().persistentQueue) return
        val st = _state.value
        runCatching {
            queueFile.parentFile?.mkdirs()
            val snapshot = PersistedQueueSnapshot(
                queue = st.queue.take(200),
                currentIndex = st.currentIndex,
                positionMs = st.positionMs,
                shuffle = st.shuffle,
                repeatMode = st.repeatMode.name,
            )
            queueFile.writeText(queueJson.encodeToString(snapshot))
        }
    }

    fun persistQueueSnapshot() {
        if (!settingsProvider().persistentQueue) return
        scope.launch { writeQueueSnapshotNow() }
    }

    fun playSong(song: DesktopSong, loadRadio: Boolean = true) {
        playQueue(listOf(song), startIndex = 0, loadRadio = loadRadio)
    }

    fun playQueue(
        songs: List<DesktopSong>,
        startIndex: Int = 0,
        loadRadio: Boolean = false,
        shuffled: Boolean = false,
    ) {
        if (songs.isEmpty()) return
        val cfg = settingsProvider()
        val deduped = if (cfg.preventDuplicateTracksInQueue) songs.distinctBy { it.id } else songs
        val finalQueue = if (shuffled && cfg.shufflePlaylistFirst) deduped.shuffled() else deduped
        val safeIndex = if (shuffled && cfg.shufflePlaylistFirst) 0 else startIndex.coerceIn(0, finalQueue.lastIndex)
        pendingRestoredSeekMs = 0L
        shuffleHistory.clear()
        val keepShuffle = if (shuffled) true else if (cfg.persistentShuffleAcrossQueues) _state.value.shuffle else false
        _state.update {
            it.copy(
                queue = finalQueue,
                currentIndex = safeIndex,
                positionMs = 0L,
                accumulatedPlayMs = 0L,
                durationMs = (finalQueue[safeIndex].duration.coerceAtLeast(0)) * 1000L,
                shuffle = keepShuffle,
                isBuffering = true,
                errorMessage = null,
            )
        }
        startCurrentTrack(allowAutoLoadRadio = loadRadio)
        persistQueueSnapshot()
        onQueueBroadcast(finalQueue, safeIndex)
        if (loadRadio && finalQueue.size == 1) {
            fetchAndAppendRadio(finalQueue.first())
        }
        if (cfg.similarContent) {
            fetchAutomixSuggestions(finalQueue[safeIndex])
        }
    }

    fun startRadioSeamlessly(seed: DesktopSong) {
        fetchAndAppendRadio(seed, playFirstFetched = false)
    }

    private fun fetchAndAppendRadio(seed: DesktopSong, playFirstFetched: Boolean = false) {
        if (fetchingAutoplay) return
        fetchingAutoplay = true
        scope.launch {
            try {
                val nextResult = YouTube.next(WatchEndpoint(videoId = seed.id)).getOrNull()
                val existingIds = _state.value.queue.map { it.id }.toSet()
                val radioTracks = nextResult?.items?.map { it.toDesktopSong() }
                    ?.filter { it.id !in existingIds }
                    .orEmpty()
                if (radioTracks.isNotEmpty()) {
                    var targetIdx: Int? = null
                    _state.update { st ->
                        val nextIdx = st.queue.size
                        if (playFirstFetched) targetIdx = nextIdx
                        st.copy(queue = st.queue + radioTracks)
                    }
                    persistQueueSnapshot()
                    targetIdx?.let { jumpToIndex(it) }
                }
            } finally {
                fetchingAutoplay = false
            }
        }
    }

    private fun fetchAutomixSuggestions(seed: DesktopSong) {
        scope.launch {
            val existingIds = _state.value.queue.map { it.id }.toSet()
            val nextResult = runCatching { YouTube.next(WatchEndpoint(videoId = seed.id)).getOrNull() }.getOrNull()
            val items = nextResult?.items?.map { it.toDesktopSong() }
                ?.filter { it.id !in existingIds && it.id != seed.id }
                ?.take(15)
                .orEmpty()
            if (items.isNotEmpty()) {
                _state.update { it.copy(automixQueue = items) }
            }
        }
    }

    fun playNext(song: DesktopSong) {
        val preventDup = settingsProvider().preventDuplicateTracksInQueue
        _state.update { st ->
            if (st.queue.isEmpty()) {
                return@update st.copy(queue = listOf(song), currentIndex = 0)
            }
            if (preventDup && st.queue.any { it.id == song.id }) return@update st
            val insertAt = (st.currentIndex + 1).coerceAtMost(st.queue.size)
            val updated = st.queue.toMutableList().apply { add(insertAt, song) }
            st.copy(queue = updated)
        }
        persistQueueSnapshot()
        if (_state.value.queue.size == 1) startCurrentTrack()
    }

    fun addToQueue(song: DesktopSong) {
        val preventDup = settingsProvider().preventDuplicateTracksInQueue
        val wasEmpty = _state.value.queue.isEmpty()
        _state.update { st ->
            if (preventDup && st.queue.any { it.id == song.id }) return@update st
            val updated = st.queue + song
            st.copy(queue = updated, currentIndex = if (wasEmpty) 0 else st.currentIndex)
        }
        persistQueueSnapshot()
        if (wasEmpty) startCurrentTrack()
    }

    fun addAllToQueue(songs: List<DesktopSong>) {
        if (songs.isEmpty()) return
        val preventDup = settingsProvider().preventDuplicateTracksInQueue
        val wasEmpty = _state.value.queue.isEmpty()
        _state.update { st ->
            val existing = if (preventDup) st.queue.map { it.id }.toSet() else emptySet()
            val toAdd = songs.filter { it.id !in existing }
            if (toAdd.isEmpty()) return@update st
            st.copy(queue = st.queue + toAdd, currentIndex = if (wasEmpty) 0 else st.currentIndex)
        }
        persistQueueSnapshot()
        if (wasEmpty) startCurrentTrack()
    }

    fun clearQueue() {
        stop()
        _state.update { it.copy(queue = emptyList(), currentIndex = -1, positionMs = 0L, durationMs = 0L, accumulatedPlayMs = 0L) }
        persistQueueSnapshot()
    }

    fun removeFromQueue(index: Int) {
        val wasCurrent = index == _state.value.currentIndex
        _state.update { st ->
            if (index !in st.queue.indices) return@update st
            val updated = st.queue.toMutableList().apply { removeAt(index) }
            val newIndex = when {
                updated.isEmpty() -> -1
                index < st.currentIndex -> st.currentIndex - 1
                index == st.currentIndex -> st.currentIndex.coerceAtMost(updated.lastIndex)
                else -> st.currentIndex
            }
            st.copy(queue = updated, currentIndex = newIndex)
        }
        persistQueueSnapshot()
        if (wasCurrent) {
            if (_state.value.currentSong != null) startCurrentTrack() else stop()
        }
    }

    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        _state.update { st ->
            if (fromIndex !in st.queue.indices || toIndex !in st.queue.indices || fromIndex == toIndex) return@update st
            val updated = st.queue.toMutableList()
            val moved = updated.removeAt(fromIndex)
            updated.add(toIndex, moved)
            val newCurrent = when (st.currentIndex) {
                fromIndex -> toIndex
                in (fromIndex + 1)..toIndex -> st.currentIndex - 1
                in toIndex until fromIndex -> st.currentIndex + 1
                else -> st.currentIndex
            }
            st.copy(queue = updated, currentIndex = newCurrent)
        }
        persistQueueSnapshot()
    }

    fun jumpToIndex(index: Int, fadeInMs: Long = 0L) {
        if (index !in _state.value.queue.indices) return
        saveEpisodePositionIfNeeded()
        pendingRestoredSeekMs = 0L
        _state.update { it.copy(currentIndex = index, positionMs = 0L, accumulatedPlayMs = 0L, isBuffering = true) }
        startCurrentTrack(fadeInMs = fadeInMs)
        persistQueueSnapshot()
    }

    private fun startCurrentTrack(allowAutoLoadRadio: Boolean = true, fadeInMs: Long = 0L) {
        val song = _state.value.currentSong ?: return
        recordedCurrentPlay = false
        startedCurrentMedia = true
        playTrackJob?.cancel()
        playTrackJob = scope.launch {
            runCatching { database.upsertSong(song) }
            val storedSong = runCatching { database.getSong(song.id) }.getOrNull()
            if (song.isEpisode && (storedSong?.playbackPosition ?: 0L) > 5_000L && pendingRestoredSeekMs == 0L) {
                pendingRestoredSeekMs = storedSong!!.playbackPosition!!
            }

            // Compute loudness normalization factor from cached format metadata
            val cfg = settingsProvider()
            val fmt = runCatching { database.getFormat(song.id) }.getOrNull()
            val normGain = if (cfg.audioNormalization) {
                LoudnessNormalizationMath.computeGainFactor(fmt?.loudnessDb, cfg.loudnessLevel)
            } else 1f
            _state.update { it.copy(normalizationGain = normGain) }

            val player = mediaPlayer
            if (player == null) {
                _state.update { it.copy(isBuffering = false, isPlaying = false) }
                return@launch
            }
            val mrl = proxyServer.streamUrlFor(song.id)
            val options = buildMediaOptions(cfg)
            if (fadeInMs > 0L) {
                crossfadeScale = 0.1f
            } else {
                crossfadeScale = 1f
            }
            if (options.isEmpty()) {
                player.media().play(mrl)
            } else {
                player.media().play(mrl, *options.toTypedArray())
            }
            applyVolumeToPlayer()
            applySpeedToPlayer()
            applyEqualizerToPlayer()

            if (fadeInMs > 0L) {
                val steps = 10
                val stepDelay = (fadeInMs / steps).coerceAtLeast(40L)
                for (i in 1..steps) {
                    delay(stepDelay)
                    crossfadeScale = (i.toFloat() / steps.toFloat()).coerceIn(0.1f, 1f)
                    applyVolumeToPlayer()
                }
            }

            // Re-check loudness once stream resolver populates format in background
            delay(1_200L)
            val refreshedFmt = runCatching { database.getFormat(song.id) }.getOrNull()
            if (refreshedFmt?.loudnessDb != null && settingsProvider().audioNormalization) {
                val updatedGain = LoudnessNormalizationMath.computeGainFactor(refreshedFmt.loudnessDb, settingsProvider().loudnessLevel)
                _state.update { it.copy(normalizationGain = updatedGain) }
                applyVolumeToPlayer()
            }
        }
        val st = _state.value
        val cfg = settingsProvider()
        val canAutoLoad = allowAutoLoadRadio && cfg.autoplay && cfg.autoLoadMore &&
            !(cfg.disableLoadMoreWhenRepeatAll && st.repeatMode == RepeatMode.ALL)
        if (canAutoLoad && st.currentIndex == st.queue.lastIndex && st.queue.isNotEmpty()) {
            fetchAndAppendRadio(song, playFirstFetched = false)
        }
    }

    private fun buildMediaOptions(cfg: DesktopSettings): List<String> {
        val opts = mutableListOf<String>()
        val filters = mutableListOf<String>()
        val pitch = _state.value.playbackPitch
        if (!cfg.varispeed && kotlin.math.abs(pitch - 1.0f) > 0.02f) {
            val semitones = LoudnessNormalizationMath.ratioToSemitones(pitch)
            filters += "scaletempo_pitch"
            opts += ":pitch-shift=$semitones"
        }
        if (cfg.skipSilence) {
            filters += "compressor"
        }
        if (filters.isNotEmpty()) {
            opts += ":audio-filter=${filters.joinToString(":")}"
        }
        return opts
    }

    private fun needsTrackRestart(player: MediaPlayer): Boolean {
        val vlcState = runCatching { player.status().state() }.getOrNull()
        return !startedCurrentMedia ||
            vlcState == State.NOTHING_SPECIAL ||
            vlcState == State.STOPPED ||
            vlcState == State.ENDED ||
            vlcState == State.ERROR
    }

    fun togglePlayPause() {
        val player = mediaPlayer ?: return
        if (_state.value.currentSong == null) return
        if (needsTrackRestart(player)) {
            startCurrentTrack()
            return
        }
        if (player.status().isPlaying) {
            player.controls().pause()
        } else {
            player.controls().play()
        }
    }

    fun pause() {
        mediaPlayer?.controls()?.setPause(true)
    }

    fun resume() {
        val player = mediaPlayer ?: return
        if (_state.value.currentSong == null) return
        if (needsTrackRestart(player)) {
            startCurrentTrack()
            return
        }
        player.controls().setPause(false)
    }

    fun stop() {
        saveEpisodePositionIfNeeded()
        mediaPlayer?.controls()?.stop()
        _state.update { it.copy(isPlaying = false, isBuffering = false, positionMs = 0L) }
    }

    fun seekTo(positionMs: Long, broadcast: Boolean = true) {
        val player = mediaPlayer ?: return
        val target = positionMs.coerceAtLeast(0L)
        if (!startedCurrentMedia) {
            pendingRestoredSeekMs = target
            _state.update { it.copy(positionMs = target) }
            return
        }
        player.controls().setTime(target)
        _state.update { it.copy(positionMs = target) }
        if (broadcast) onSeekBroadcast(target)
    }

    fun seekRelative(deltaMs: Long) {
        val st = _state.value
        val target = (st.positionMs + deltaMs).coerceIn(0L, st.durationMs.coerceAtLeast(1L))
        seekTo(target)
    }

    private fun computeNextIndex(st: PlaybackState): Int? = when {
        st.shuffle && st.queue.size > 1 -> st.queue.indices.filter { it != st.currentIndex }.randomOrNull()
        st.currentIndex + 1 < st.queue.size -> st.currentIndex + 1
        st.repeatMode == RepeatMode.ALL && st.queue.isNotEmpty() -> 0
        else -> null
    }

    fun next() {
        val st = _state.value
        if (st.queue.isEmpty()) return
        if (st.currentIndex in st.queue.indices) {
            shuffleHistory.addLast(st.currentIndex)
            if (shuffleHistory.size > 50) shuffleHistory.removeFirst()
        }
        val nextIndex = computeNextIndex(st)
        if (nextIndex != null) {
            jumpToIndex(nextIndex)
        } else if (settingsProvider().autoplay && st.currentSong != null) {
            fetchAndAppendRadio(st.currentSong!!, playFirstFetched = true)
        }
    }

    fun previous() {
        val st = _state.value
        if (st.queue.isEmpty()) return
        if (st.positionMs > 3_500L) {
            seekTo(0L)
            return
        }
        val prevIndex = when {
            st.shuffle && shuffleHistory.isNotEmpty() -> shuffleHistory.removeLast().coerceIn(0, st.queue.lastIndex)
            st.currentIndex - 1 >= 0 -> st.currentIndex - 1
            st.repeatMode == RepeatMode.ALL -> st.queue.lastIndex
            else -> {
                seekTo(0L)
                return
            }
        }
        jumpToIndex(prevIndex)
    }

    private fun onTrackFinished() {
        val st = _state.value
        st.currentSong?.takeIf { it.isEpisode }?.let {
            runCatching { database.setEpisodePlaybackPosition(it.id, null) }
        }
        if (st.sleepTimerEndOfTrack) {
            _state.update {
                it.copy(
                    isPlaying = false,
                    sleepTimerEndOfTrack = false,
                    sleepTimerTargetEpochMs = null,
                    sleepTimerRemainingMs = null,
                    sleepTimerStopAfterTrack = false,
                )
            }
            return
        }
        if (st.repeatMode == RepeatMode.ONE) {
            seekTo(0L, broadcast = false)
            startCurrentTrack()
            return
        }
        val hasNext = computeNextIndex(st) != null
        if (hasNext) {
            next()
        } else if (settingsProvider().autoplay && st.currentSong != null) {
            fetchAndAppendRadio(st.currentSong!!, playFirstFetched = true)
        } else {
            _state.update { it.copy(isPlaying = false, positionMs = it.durationMs) }
        }
    }

    fun setVolume(volume: Float, persist: Boolean = true) {
        val clamped = volume.coerceIn(0f, 1f)
        val wasMuted = _state.value.isMuted
        val nowMuted = clamped == 0f
        _state.update { it.copy(volume = clamped, isMuted = nowMuted) }
        applyVolumeToPlayer()
        if (settingsProvider().pauseOnMute) {
            if (nowMuted && _state.value.isPlaying) {
                pausedByMute = true
                pause()
            } else if (!nowMuted && wasMuted && pausedByMute) {
                pausedByMute = false
                resume()
            }
        }
        if (persist) onVolumePersist(clamped)
    }

    fun toggleMute() {
        val nextMuted = !_state.value.isMuted
        _state.update { it.copy(isMuted = nextMuted) }
        applyVolumeToPlayer()
        if (settingsProvider().pauseOnMute) {
            if (nextMuted && _state.value.isPlaying) {
                pausedByMute = true
                pause()
            } else if (!nextMuted && pausedByMute) {
                pausedByMute = false
                resume()
            }
        }
    }

    fun toggleShuffle() {
        _state.update { it.copy(shuffle = !it.shuffle) }
        persistQueueSnapshot()
    }

    fun cycleRepeatMode() {
        _state.update { st ->
            val nextMode = when (st.repeatMode) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
            st.copy(repeatMode = nextMode)
        }
        persistQueueSnapshot()
    }

    fun setPlaybackSpeed(speed: Float, persist: Boolean = true) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        _state.update { it.copy(playbackSpeed = clamped) }
        applySpeedToPlayer()
        if (persist) onSpeedPersist(clamped)
    }

    fun setPlaybackPitch(pitch: Float, persist: Boolean = true) {
        val clamped = pitch.coerceIn(0.5f, 2.0f)
        val changed = kotlin.math.abs(_state.value.playbackPitch - clamped) > 0.01f
        _state.update { it.copy(playbackPitch = clamped) }
        if (persist) onPitchPersist(clamped)
        if (changed && _state.value.isPlaying && startedCurrentMedia) {
            val pos = _state.value.positionMs
            pendingRestoredSeekMs = pos
            startCurrentTrack(allowAutoLoadRadio = false)
        }
    }

    fun setEqualizer(
        enabled: Boolean = _state.value.eqEnabled,
        preset: String = _state.value.eqPreset,
        preamp: Float = _state.value.eqPreamp,
        bands: List<Float> = _state.value.eqBands,
        persist: Boolean = true,
    ) {
        val normalizedBands = (0 until 10).map { idx -> (bands.getOrNull(idx) ?: 0f).coerceIn(-15f, 15f) }
        val clampedPreamp = preamp.coerceIn(-15f, 15f)
        _state.update {
            it.copy(
                eqEnabled = enabled,
                eqPreset = preset,
                eqPreamp = clampedPreamp,
                eqBands = normalizedBands,
            )
        }
        applyEqualizerToPlayer()
        if (persist) onEqPersist(enabled, preset, clampedPreamp, normalizedBands)
    }

    fun applyEqualizerPreset(presetName: String) {
        val bands = DesktopEqPresets.presets[presetName] ?: DesktopEqPresets.presets["Flat"]!!
        setEqualizer(enabled = true, preset = presetName, preamp = _state.value.eqPreamp, bands = bands)
    }

    fun applyParametricProfile(profile: ParametricEqProfile, name: String) {
        val (preamp, bands) = DesktopParametricEq.projectToVlc10Bands(profile)
        setEqualizer(enabled = true, preset = name, preamp = preamp, bands = bands)
    }

    fun setSleepTimer(
        minutes: Int?,
        endOfTrack: Boolean = false,
        stopAfterCurrentSong: Boolean = settingsProvider().sleepTimerStopAfterCurrentSong,
    ) {
        when {
            endOfTrack -> {
                _state.update {
                    it.copy(
                        sleepTimerEndOfTrack = true,
                        sleepTimerTargetEpochMs = null,
                        sleepTimerRemainingMs = null,
                        sleepTimerStopAfterTrack = false,
                    )
                }
            }
            minutes != null && minutes > 0 -> {
                val durationMs = minutes * 60_000L
                _state.update {
                    it.copy(
                        sleepTimerEndOfTrack = false,
                        sleepTimerTargetEpochMs = System.currentTimeMillis() + durationMs,
                        sleepTimerRemainingMs = durationMs,
                        sleepTimerStopAfterTrack = stopAfterCurrentSong,
                    )
                }
            }
            else -> {
                sleepFadeScale = 1f
                applyVolumeToPlayer()
                _state.update {
                    it.copy(
                        sleepTimerEndOfTrack = false,
                        sleepTimerTargetEpochMs = null,
                        sleepTimerRemainingMs = null,
                        sleepTimerStopAfterTrack = false,
                    )
                }
            }
        }
    }

    fun reapplyNormalization() {
        val song = _state.value.currentSong ?: return
        val cfg = settingsProvider()
        val fmt = runCatching { database.getFormat(song.id) }.getOrNull()
        val gain = if (cfg.audioNormalization) {
            LoudnessNormalizationMath.computeGainFactor(fmt?.loudnessDb, cfg.loudnessLevel)
        } else 1f
        _state.update { it.copy(normalizationGain = gain) }
        applyVolumeToPlayer()
    }

    private fun applyVolumeToPlayer() {
        val player = mediaPlayer ?: return
        val st = _state.value
        val effective = (st.volume * st.normalizationGain * crossfadeScale * sleepFadeScale).coerceIn(0f, 1.25f)
        val vlcVolume = if (st.isMuted) 0 else (effective * 100f).roundToInt().coerceIn(0, 125)
        runCatching {
            player.audio().setMute(st.isMuted)
            player.audio().setVolume(vlcVolume)
        }
    }

    private fun applySpeedToPlayer() {
        val player = mediaPlayer ?: return
        runCatching {
            player.controls().setRate(_state.value.playbackSpeed)
        }
    }

    private fun applyEqualizerToPlayer() {
        val player = mediaPlayer ?: return
        val eq = vlcEqualizer ?: return
        val st = _state.value
        runCatching {
            if (!st.eqEnabled) {
                player.audio().setEqualizer(null)
            } else {
                eq.setPreamp(st.eqPreamp)
                st.eqBands.forEachIndexed { idx, db ->
                    eq.setAmp(idx, db)
                }
                player.audio().setEqualizer(eq)
            }
        }
    }

    fun release() {
        saveEpisodePositionIfNeeded()
        writeQueueSnapshotNow()
        playTrackJob?.cancel()
        positionJob?.cancel()
        scope.cancel()
        runCatching { mediaPlayer?.audio()?.setEqualizer(null) }
        runCatching { mediaPlayer?.controls()?.stop() }
        runCatching { audioComponent?.release() }
        runCatching { mediaPlayerFactory?.release() }
        vlcEqualizer = null
        mediaPlayer = null
        audioComponent = null
        mediaPlayerFactory = null
    }
}
