package com.metrolist.desktop.audio

import com.metrolist.desktop.data.DesktopAudioQuality
import com.metrolist.desktop.db.DesktopDatabase
import com.metrolist.desktop.db.DesktopSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DownloadManager(
    private val database: DesktopDatabase,
    private val resolver: InnerTubeStreamResolver,
    private val qualityProvider: () -> DesktopAudioQuality,
    private val downloadsDirProvider: () -> File,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val concurrencyGate = Semaphore(3)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _downloading = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloading: StateFlow<Map<String, Float>> = _downloading.asStateFlow()

    fun downloadAll(songs: List<DesktopSong>) {
        songs.forEach { if (!it.isDownloaded) download(it) }
    }

    fun cancelDownload(songId: String) {
        activeJobs.remove(songId)?.cancel()
        _downloading.update { it - songId }
    }

    fun download(song: DesktopSong) {
        if (activeJobs.containsKey(song.id)) return
        _downloading.update { it + (song.id to 0f) }

        val job = scope.launch {
            var partFile: File? = null
            try {
                concurrencyGate.withPermit {
                    database.upsertSong(song)
                    val quality = qualityProvider()
                    var resolved = resolver.resolve(song.id, quality).getOrThrow()
                    val dir = downloadsDirProvider().apply { mkdirs() }
                    val ext = if (resolved.mimeType.contains("mp4", ignoreCase = true)) "m4a" else "webm"
                    val tmp = File(dir, "${song.id}.$ext.part")
                    partFile = tmp
                    val finalFile = File(dir, "${song.id}.$ext")

                    var attempt = 0
                    while (true) {
                        try {
                            downloadStreamToFile(song.id, resolved, tmp)
                            break
                        } catch (e: HttpRejectedException) {
                            if (attempt >= 1) throw e
                            attempt++
                            resolver.markClientFailed(song.id, resolved.clientName)
                            resolver.refreshAfterRejection()
                            resolved = resolver.resolve(song.id, quality, forceRefresh = true).getOrThrow()
                        }
                    }

                    Files.move(tmp.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    database.setDownloaded(song, true)
                }
            } catch (ce: CancellationException) {
                partFile?.delete()
                throw ce
            } catch (t: Throwable) {
                partFile?.delete()
                t.printStackTrace()
            } finally {
                activeJobs.remove(song.id)
                _downloading.update { it - song.id }
            }
        }
        activeJobs[song.id] = job
    }

    private fun downloadStreamToFile(songId: String, resolved: ResolvedStream, tmp: File) {
        val totalBytes = resolved.contentLengthBytes ?: -1L
        val chunkSize = if (resolved.rangeChunkSizeBytes > 0L) resolved.rangeChunkSizeBytes else 8L * 1024L * 1024L
        var lastReportMs = 0L

        fun reportProgress(written: Long, total: Long) {
            if (total <= 0L) return
            val now = System.currentTimeMillis()
            if (now - lastReportMs >= 150L || written >= total) {
                lastReportMs = now
                val progress = (written.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                _downloading.update { it + (songId to progress) }
            }
        }

        FileOutputStream(tmp).buffered().use { out ->
            if ((resolved.requireBoundedRange || resolved.useRangeChunks) && totalBytes > 0L) {
                var offset = 0L
                while (offset < totalBytes) {
                    val end = minOf(offset + chunkSize - 1, totalBytes - 1)
                    val req = Request.Builder().url(resolved.url).apply {
                        resolved.headers.forEach { (k, v) -> header(k, v) }
                        header("Range", "bytes=$offset-$end")
                    }.build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.code == 403 || resp.code == 410) throw HttpRejectedException(resp.code)
                        check(resp.isSuccessful) { "HTTP ${resp.code} downloading $songId" }
                        resp.body.byteStream().use { input ->
                            val buf = ByteArray(32 * 1024)
                            while (true) {
                                val read = input.read(buf)
                                if (read == -1) break
                                out.write(buf, 0, read)
                                offset += read
                                reportProgress(offset, totalBytes)
                            }
                        }
                    }
                }
            } else {
                val req = Request.Builder().url(resolved.url).apply {
                    resolved.headers.forEach { (k, v) -> header(k, v) }
                }.build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 403 || resp.code == 410) throw HttpRejectedException(resp.code)
                    check(resp.isSuccessful) { "HTTP ${resp.code} downloading $songId" }
                    val len = resp.body.contentLength().takeIf { it > 0L } ?: totalBytes
                    var written = 0L
                    resp.body.byteStream().use { input ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val read = input.read(buf)
                            if (read == -1) break
                            out.write(buf, 0, read)
                            written += read
                            reportProgress(written, len)
                        }
                    }
                }
            }
        }
    }

    fun deleteDownload(song: DesktopSong) {
        cancelDownload(song.id)
        scope.launch {
            val dir = downloadsDirProvider()
            listOf("webm", "m4a", "opus", "mp3", "mp4", "ogg").forEach { ext ->
                File(dir, "${song.id}.$ext").delete()
                File(dir, "${song.id}.$ext.part").delete()
            }
            database.setDownloaded(song, false)
        }
    }

    fun clearAllDownloads() {
        activeJobs.keys.toList().forEach(::cancelDownload)
        scope.launch {
            val dir = downloadsDirProvider()
            dir.listFiles()?.forEach { f -> if (f.isFile) f.delete() }
            database.downloadedSongs().forEach { database.setDownloaded(it, false) }
        }
    }

    fun close() {
        scope.cancel()
        runCatching { httpClient.dispatcher.cancelAll() }
        runCatching { httpClient.dispatcher.executorService.shutdownNow() }
        runCatching { httpClient.connectionPool.evictAll() }
    }

    private class HttpRejectedException(val code: Int) : IllegalStateException("HTTP $code")
}
