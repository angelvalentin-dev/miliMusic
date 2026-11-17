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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.AudioPlayerComponent
import kotlin.math.roundToInt

enum class RepeatMode { OFF, ALL, ONE }

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
    private val onVolumePersist: (Float) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var audioComponent: AudioPlayerComponent? = null
    private var mediaPlayer: MediaPlayer? = null
    private var positionJob: Job? = null
    private var currentSongStartTimeMs: Long = 0L
    private var recordedCurrentPlay: Boolean = false

    init {
        initVlc()
        startPositionTicker()
    }

    private fun initVlc() {
        try {
            val bundledVlcDir = AppPaths.bundledFile("vlc")
            if (bundledVlcDir != null && bundledVlcDir.exists()) {
                NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcLibraryName(), bundledVlcDir.absolutePath)
            }
            val args = buildList {
                add("--no-video")
                add("--quiet")
                if (bundledVlcDir != null) {
                    val pluginsDir = bundledVlcDir.resolve("plugins")
                    if (pluginsDir.exists()) {
                        add("--plugin-path=${pluginsDir.absolutePath}")
                    }
                }
            }.toTypedArray()

            val factory = MediaPlayerFactory(*args)
            val component = AudioPlayerComponent(factory)
            val player = component.mediaPlayer()
            player.events().addMediaPlayerEventListener(
                object : MediaPlayerEventAdapter() {
                    override fun playing(mediaPlayer: MediaPlayer) {
                        _state.update { it.copy(isPlaying = true, isBuffering = false, errorMessage = null) }
                    }

                    override fun paused(mediaPlayer: MediaPlayer) {
                        _state.update { it.copy(isPlaying = false, isBuffering = false) }
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
            audioComponent = component
            mediaPlayer = player
            applyVolumeToPlayer()
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
            while (isActive) {
                val player = mediaPlayer
                if (player != null && _state.value.isPlaying) {
                    val time = player.status().time().coerceAtLeast(0L)
                    val len = player.status().length().takeIf { it > 0L }
                        ?: ((_state.value.currentSong?.duration ?: 0) * 1000L).coerceAtLeast(0L)
                    _state.update { it.copy(positionMs = time, durationMs = len) }

                    val current = _state.value.currentSong
                    if (!recordedCurrentPlay && current != null && !pauseListenHistoryProvider()) {
                        if (System.currentTimeMillis() - currentSongStartTimeMs >= 8_000L) {
                            recordedCurrentPlay = true
                            runCatching { database.recordPlay(current, 8_000L) }
                        }
                    }
                }
                delay(250L)
            }
        }
    }

    fun playSong(song: DesktopSong, loadRadio: Boolean = true) {
        playQueue(listOf(song), startIndex = 0, loadRadio = loadRadio)
    }

    fun playQueue(songs: List<DesktopSong>, startIndex: Int = 0, loadRadio: Boolean = false) {
        if (songs.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, songs.lastIndex)
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
        if (loadRadio && songs.size == 1) {
            val seed = songs.first()
            scope.launch {
                val nextResult = YouTube.next(WatchEndpoint(videoId = seed.id)).getOrNull()
                val radioTracks = nextResult?.items?.map { it.toDesktopSong() }
                    ?.filter { it.id != seed.id }
                    .orEmpty()
                if (radioTracks.isNotEmpty()) {
                    _state.update { st ->
                        if (st.currentSong?.id == seed.id && st.queue.size == 1) {
                            st.copy(queue = st.queue + radioTracks)
                        } else {
                            st
                        }
                    }
                }
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
        if (_state.value.queue.size == 1) startCurrentTrack()
    }

    fun addToQueue(song: DesktopSong) {
        val wasEmpty = _state.value.queue.isEmpty()
        _state.update { st ->
            val updated = st.queue + song
            st.copy(queue = updated, currentIndex = if (wasEmpty) 0 else st.currentIndex)
        }
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
    }

    fun jumpToIndex(index: Int) {
        if (index !in _state.value.queue.indices) return
        _state.update { it.copy(currentIndex = index, positionMs = 0L, isBuffering = true) }
        startCurrentTrack()
    }

    private fun startCurrentTrack() {
        val song = _state.value.currentSong ?: return
        currentSongStartTimeMs = System.currentTimeMillis()
        recordedCurrentPlay = false
        scope.launch {
            runCatching { database.upsertSong(song) }
            val player = mediaPlayer ?: return@launch
            val mrl = proxyServer.streamUrlFor(song.id)
            player.media().play(mrl)
            applyVolumeToPlayer()
        }
    }

    fun togglePlayPause() {
        val player = mediaPlayer ?: return
        if (_state.value.currentSong == null) return
        if (player.status().isPlaying) {
            player.controls().pause()
        } else {
            if (player.status().time() <= 0L && !_state.value.isBuffering) {
                player.controls().play()
            } else {
                player.controls().play()
            }
        }
    }

    fun pause() {
        mediaPlayer?.controls()?.setPause(true)
    }

    fun resume() {
        mediaPlayer?.controls()?.setPause(false)
    }

    fun stop() {
        mediaPlayer?.controls()?.stop()
        _state.update { it.copy(isPlaying = false, isBuffering = false, positionMs = 0L) }
    }

    fun seekTo(positionMs: Long) {
        val player = mediaPlayer ?: return
        val target = positionMs.coerceAtLeast(0L)
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
        } else {
            _state.update { it.copy(isPlaying = false, positionMs = it.durationMs) }
        }
    }

    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        _state.update { it.copy(volume = clamped, isMuted = clamped == 0f) }
        applyVolumeToPlayer()
        onVolumePersist(clamped)
    }

    fun toggleMute() {
        _state.update { it.copy(isMuted = !it.isMuted) }
        applyVolumeToPlayer()
    }

    fun toggleShuffle() {
        _state.update { it.copy(shuffle = !it.shuffle) }
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

    fun release() {
        positionJob?.cancel()
        runCatching { audioComponent?.release() }
    }
}
