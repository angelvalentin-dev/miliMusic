package com.metrolist.desktop.audio

import com.metrolist.desktop.AppPaths
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
import kotlin.math.roundToInt

enum class RepeatMode { OFF, ALL, ONE }

object DesktopEqPresets {
    val bandLabels = listOf("31Hz", "63Hz", "125Hz", "250Hz", "500Hz", "1kHz", "2kHz", "4kHz", "8kHz", "16kHz")

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
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Float = 0.85f,
    val isMuted: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val playbackSpeed: Float = 1.0f,
    val eqEnabled: Boolean = false,
    val eqPreset: String = "Flat",
    val eqPreamp: Float = 0f,
    val eqBands: List<Float> = List(10) { 0f },
    val sleepTimerTargetEpochMs: Long? = null,
    val sleepTimerRemainingMs: Long? = null,
    val sleepTimerEndOfTrack: Boolean = false,
    val vlcAvailable: Boolean = true,
    val errorMessage: String? = null,
) {
    val currentSong: DesktopSong?
        get() = queue.getOrNull(currentIndex)
}

class DesktopAudioPlayer(
    private val database: DesktopDatabase,
    private val proxyServer: StreamProxyServer,
    private val pauseListenHistoryProvider: () -> Boolean,
    private val autoplayProvider: () -> Boolean = { true },
    private val persistentQueueProvider: () -> Boolean = { true },
    private val queueFile: File = File(AppPaths.dataDir, "playback_queue.json"),
    private val onVolumePersist: (Float) -> Unit,
    private val onSpeedPersist: (Float) -> Unit = {},
    private val onEqPersist: (Boolean, String, Float, List<Float>) -> Unit = { _, _, _, _ -> },
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
    private var currentSongStartTimeMs: Long = 0L
    private var recordedCurrentPlay: Boolean = false
    private var pendingRestoredSeekMs: Long = 0L
    private var startedCurrentMedia: Boolean = false
    private var fetchingAutoplay: Boolean = false

    init {
        initVlc()
        restorePersistedQueue()
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
                        _state.update { it.copy(isPlaying = true, isBuffering = false, errorMessage = null) }
                        if (pendingRestoredSeekMs > 0L) {
                            val target = pendingRestoredSeekMs
                            pendingRestoredSeekMs = 0L
                            runCatching { mediaPlayer.controls().setTime(target) }
                        }
                        applySpeedToPlayer()
                        applyEqualizerToPlayer()
                    }

                    override fun paused(mediaPlayer: MediaPlayer) {
                        _state.update { it.copy(isPlaying = false, isBuffering = false) }
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
                val targetSleep = st.sleepTimerTargetEpochMs
                if (targetSleep != null) {
                    val remaining = targetSleep - System.currentTimeMillis()
                    if (remaining <= 0L) {
                        pause()
                        _state.update {
                            it.copy(
                                sleepTimerTargetEpochMs = null,
                                sleepTimerRemainingMs = null,
                                sleepTimerEndOfTrack = false,
                            )
                        }
                    } else {
                        _state.update { it.copy(sleepTimerRemainingMs = remaining) }
                    }
                }

                if (player != null && st.isPlaying) {
                    val time = player.status().time().coerceAtLeast(0L)
                    val len = player.status().length().takeIf { it > 0L }
                        ?: ((st.currentSong?.duration ?: 0) * 1000L).coerceAtLeast(0L)
                    _state.update { it.copy(positionMs = time, durationMs = len) }

                    val current = st.currentSong
                    if (!recordedCurrentPlay && current != null && !pauseListenHistoryProvider()) {
                        if (System.currentTimeMillis() - currentSongStartTimeMs >= 8_000L) {
                            recordedCurrentPlay = true
                            val playDuration = len.takeIf { it > 0L } ?: 180_000L
                            runCatching { database.recordPlay(current, playDuration.coerceAtLeast(8_000L)) }
                        }
                    }

                    tickCount += 1
                    if (tickCount % 20 == 0) {
                        persistQueueSnapshot()
                    }
                }
                delay(250L)
            }
        }
    }

    fun restorePersistedQueue() {
        if (!persistentQueueProvider() || !queueFile.exists()) return
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
                    shuffle = snapshot.shuffle,
                    repeatMode = runCatching { RepeatMode.valueOf(snapshot.repeatMode) }.getOrDefault(RepeatMode.OFF),
                    isPlaying = false,
                    isBuffering = false,
                )
            }
        }
    }

    private fun writeQueueSnapshotNow() {
        if (!persistentQueueProvider()) return
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
        if (!persistentQueueProvider()) return
        scope.launch { writeQueueSnapshotNow() }
    }

    fun playSong(song: DesktopSong, loadRadio: Boolean = true) {
        playQueue(listOf(song), startIndex = 0, loadRadio = loadRadio)
    }

    fun playQueue(songs: List<DesktopSong>, startIndex: Int = 0, loadRadio: Boolean = false) {
        if (songs.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, songs.lastIndex)
        pendingRestoredSeekMs = 0L
        _state.update {
            it.copy(
                queue = songs,
                currentIndex = safeIndex,
                positionMs = 0L,
                durationMs = (songs[safeIndex].duration.coerceAtLeast(0)) * 1000L,
                isBuffering = true,
                errorMessage = null,
            )
        }
        startCurrentTrack()
        persistQueueSnapshot()
        if ((loadRadio || autoplayProvider()) && songs.size == 1) {
            fetchAndAppendRadio(songs.first())
        }
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

    fun playNext(song: DesktopSong) {
        _state.update { st ->
            if (st.queue.isEmpty()) {
                return@update st.copy(queue = listOf(song), currentIndex = 0)
            }
            val insertAt = (st.currentIndex + 1).coerceAtMost(st.queue.size)
            val updated = st.queue.toMutableList().apply { add(insertAt, song) }
            st.copy(queue = updated)
        }
        persistQueueSnapshot()
        if (_state.value.queue.size == 1) startCurrentTrack()
    }

    fun addToQueue(song: DesktopSong) {
        val wasEmpty = _state.value.queue.isEmpty()
        _state.update { st ->
            val updated = st.queue + song
            st.copy(queue = updated, currentIndex = if (wasEmpty) 0 else st.currentIndex)
        }
        persistQueueSnapshot()
        if (wasEmpty) startCurrentTrack()
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

    fun jumpToIndex(index: Int) {
        if (index !in _state.value.queue.indices) return
        pendingRestoredSeekMs = 0L
        _state.update { it.copy(currentIndex = index, positionMs = 0L, isBuffering = true) }
        startCurrentTrack()
        persistQueueSnapshot()
    }

    private fun startCurrentTrack() {
        val song = _state.value.currentSong ?: return
        currentSongStartTimeMs = System.currentTimeMillis()
        recordedCurrentPlay = false
        startedCurrentMedia = true
        playTrackJob?.cancel()
        playTrackJob = scope.launch {
            runCatching { database.upsertSong(song) }
            val player = mediaPlayer
            if (player == null) {
                _state.update { it.copy(isBuffering = false, isPlaying = false) }
                return@launch
            }
            val mrl = proxyServer.streamUrlFor(song.id)
            player.media().play(mrl)
            applyVolumeToPlayer()
            applySpeedToPlayer()
            applyEqualizerToPlayer()
        }
        val st = _state.value
        if (autoplayProvider() && st.currentIndex == st.queue.lastIndex && st.queue.isNotEmpty()) {
            fetchAndAppendRadio(song, playFirstFetched = false)
        }
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
        mediaPlayer?.controls()?.stop()
        _state.update { it.copy(isPlaying = false, isBuffering = false, positionMs = 0L) }
    }

    fun seekTo(positionMs: Long) {
        val player = mediaPlayer ?: return
        val target = positionMs.coerceAtLeast(0L)
        if (!startedCurrentMedia) {
            pendingRestoredSeekMs = target
            _state.update { it.copy(positionMs = target) }
            return
        }
        player.controls().setTime(target)
        _state.update { it.copy(positionMs = target) }
    }

    fun next() {
        val st = _state.value
        if (st.queue.isEmpty()) return
        val nextIndex = when {
            st.shuffle && st.queue.size > 1 -> {
                val candidates = st.queue.indices.filter { it != st.currentIndex }
                candidates.random()
            }
            st.currentIndex + 1 < st.queue.size -> st.currentIndex + 1
            st.repeatMode == RepeatMode.ALL -> 0
            autoplayProvider() && st.currentSong != null -> {
                fetchAndAppendRadio(st.currentSong!!, playFirstFetched = true)
                return
            }
            else -> return
        }
        jumpToIndex(nextIndex)
    }

    fun previous() {
        val st = _state.value
        if (st.queue.isEmpty()) return
        if (st.positionMs > 3_500L) {
            seekTo(0L)
            return
        }
        val prevIndex = when {
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
        if (st.sleepTimerEndOfTrack) {
            _state.update {
                it.copy(
                    isPlaying = false,
                    sleepTimerEndOfTrack = false,
                    sleepTimerTargetEpochMs = null,
                    sleepTimerRemainingMs = null,
                )
            }
            return
        }
        if (st.repeatMode == RepeatMode.ONE) {
            seekTo(0L)
            startCurrentTrack()
            return
        }
        val hasNext = st.shuffle && st.queue.size > 1 ||
            st.currentIndex + 1 < st.queue.size ||
            st.repeatMode == RepeatMode.ALL
        if (hasNext) {
            next()
        } else if (autoplayProvider() && st.currentSong != null) {
            fetchAndAppendRadio(st.currentSong!!, playFirstFetched = true)
        } else {
            _state.update { it.copy(isPlaying = false, positionMs = it.durationMs) }
        }
    }

    fun setVolume(volume: Float, persist: Boolean = true) {
        val clamped = volume.coerceIn(0f, 1f)
        _state.update { it.copy(volume = clamped, isMuted = clamped == 0f) }
        applyVolumeToPlayer()
        if (persist) onVolumePersist(clamped)
    }

    fun toggleMute() {
        _state.update { it.copy(isMuted = !it.isMuted) }
        applyVolumeToPlayer()
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

    fun setSleepTimer(minutes: Int?, endOfTrack: Boolean = false) {
        when {
            endOfTrack -> {
                _state.update {
                    it.copy(
                        sleepTimerEndOfTrack = true,
                        sleepTimerTargetEpochMs = null,
                        sleepTimerRemainingMs = null,
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
                    )
                }
            }
            else -> {
                _state.update {
                    it.copy(
                        sleepTimerEndOfTrack = false,
                        sleepTimerTargetEpochMs = null,
                        sleepTimerRemainingMs = null,
                    )
                }
            }
        }
    }

    private fun applyVolumeToPlayer() {
        val player = mediaPlayer ?: return
        val st = _state.value
        val vlcVolume = if (st.isMuted) 0 else (st.volume * 100f).roundToInt().coerceIn(0, 100)
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
        writeQueueSnapshotNow()
        playTrackJob?.cancel()
        positionJob?.cancel()
        scope.cancel()
        runCatching { mediaPlayer?.audio()?.setEqualizer(null) }
        runCatching { mediaPlayer?.controls()?.stop() }
        runCatching { audioComponent?.release() }
        // AudioPlayerComponent(factory) sets ownFactory=false, so factory must be released explicitly.
        runCatching { mediaPlayerFactory?.release() }
        vlcEqualizer = null
        mediaPlayer = null
        audioComponent = null
        mediaPlayerFactory = null
    }
}

