package com.metrolist.desktop.windows

import com.metrolist.desktop.AppPaths
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.WString

interface SmtcNativeLibrary : Library {
    interface ActionCallback : Callback {
        fun invoke(actionCode: Int)
    }

    fun smtc_init(appName: WString, callback: ActionCallback): Int
    fun smtc_update_metadata(title: WString, artist: WString, album: WString, thumbnailUrl: WString)
    fun smtc_update_status(statusCode: Int)
    fun smtc_update_timeline(positionMs: Long, durationMs: Long)
    fun smtc_shutdown()
}

class WindowsSmtcManager(
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onStop: () -> Unit,
) {
    private var lib: SmtcNativeLibrary? = null
    private var enabled: Boolean = true
    private var lastTimelineUpdateMs: Long = 0L

    // Keep strong reference so GC never collects JNA callback while native DLL holds pointer
    private val callback = object : SmtcNativeLibrary.ActionCallback {
        override fun invoke(actionCode: Int) {
            if (!enabled) return
            when (actionCode) {
                ACTION_PLAY -> onPlay()
                ACTION_PAUSE -> onPause()
                ACTION_NEXT -> onNext()
                ACTION_PREVIOUS -> onPrevious()
                ACTION_STOP -> onStop()
            }
        }
    }

    init {
        initNativeIfWindows()
    }

    @Synchronized
    private fun initNativeIfWindows() {
        if (!AppPaths.isWindows || lib != null) return
        val dll = AppPaths.bundledFile("milimusic_smtc.dll")
        if (dll != null && dll.exists()) {
            runCatching {
                val loaded = Native.load(dll.absolutePath, SmtcNativeLibrary::class.java)
                if (loaded.smtc_init(WString("miliMusic"), callback) == 0) {
                    lib = loaded
                }
            }
        }
    }

    @Synchronized
    fun setEnabled(enable: Boolean) {
        if (enabled == enable) return
        enabled = enable
        if (!enable) {
            runCatching { lib?.smtc_shutdown() }
            lib = null
        } else {
            initNativeIfWindows()
        }
    }

    fun updateMetadata(title: String, artist: String, album: String?, thumbnailUrl: String?) {
        if (!enabled) return
        runCatching {
            lib?.smtc_update_metadata(
                WString(title),
                WString(artist),
                WString(album.orEmpty()),
                WString(thumbnailUrl.orEmpty()),
            )
        }
    }

    fun updatePlaybackStatus(isPlaying: Boolean, hasTrack: Boolean) {
        if (!enabled) return
        val code = when {
            !hasTrack -> STATUS_STOPPED
            isPlaying -> STATUS_PLAYING
            else -> STATUS_PAUSED
        }
        runCatching { lib?.smtc_update_status(code) }
    }

    fun updateTimeline(positionMs: Long, durationMs: Long, force: Boolean = false) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        if (!force && now - lastTimelineUpdateMs < 1_000L) return
        lastTimelineUpdateMs = now
        runCatching { lib?.smtc_update_timeline(positionMs, durationMs) }
    }

    @Synchronized
    fun shutdown() {
        enabled = false
        runCatching { lib?.smtc_shutdown() }
        lib = null
    }

    companion object {
        private const val ACTION_PLAY = 0
        private const val ACTION_PAUSE = 1
        private const val ACTION_NEXT = 2
        private const val ACTION_PREVIOUS = 3
        private const val ACTION_STOP = 4

        private const val STATUS_STOPPED = 0
        private const val STATUS_PLAYING = 1
        private const val STATUS_PAUSED = 2
    }
}
