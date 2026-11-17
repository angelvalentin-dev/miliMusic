package com.metrolist.desktop.audio

import com.metrolist.desktop.data.DesktopAudioQuality
import com.metrolist.desktop.db.DesktopDatabase
import com.metrolist.desktop.db.DesktopSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class DownloadManager(
    private val database: DesktopDatabase,
    private val resolver: InnerTubeStreamResolver,
    private val qualityProvider: () -> DesktopAudioQuality,
    private val downloadsDirProvider: () -> File,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _downloading = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloading: StateFlow<Map<String, Float>> = _downloading.asStateFlow()

    fun download(song: DesktopSong) {
        if (_downloading.value.containsKey(song.id)) return
        _downloading.update { it + (song.id to 0f) }

        scope.launch {
            try {
                database.upsertSong(song)
                val resolved = resolver.resolve(song.id, qualityProvider()).getOrThrow()
                val dir = downloadsDirProvider().apply { mkdirs() }
                val ext = if (resolved.mimeType.contains("mp4", ignoreCase = true)) "m4a" else "webm"
                val partFile = File(dir, "${song.id}.$ext.part")
                val finalFile = File(dir, "${song.id}.$ext")

                val totalBytes = resolved.contentLengthBytes ?: -1L
                val chunkSize = if (resolved.rangeChunkSizeBytes > 0L) resolved.rangeChunkSizeBytes else 8L * 1024L * 1024L

                FileOutputStream(partFile).buffered().use { out ->
                    if ((resolved.requireBoundedRange || resolved.useRangeChunks) && totalBytes > 0L) {
                        var offset = 0L
                        while (offset < totalBytes) {
                            val end = minOf(offset + chunkSize - 1, totalBytes - 1)
                            val req = Request.Builder().url(resolved.url).apply {
                                resolved.headers.forEach { (k, v) -> header(k, v) }
                                header("Range", "bytes=$offset-$end")
                            }.build()
                            httpClient.newCall(req).execute().use { resp ->
                                check(resp.isSuccessful) { "HTTP ${resp.code} downloading ${song.id}" }
                                resp.body?.byteStream()?.use { input ->
                                    val buf = ByteArray(32 * 1024)
                                    while (true) {
                                        val read = input.read(buf)
                                        if (read == -1) break
                                        out.write(buf, 0, read)
                                        offset += read
                                        _downloading.update {
                                            it + (song.id to (offset.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f))
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        val req = Request.Builder().url(resolved.url).apply {
                            resolved.headers.forEach { (k, v) -> header(k, v) }
                        }.build()
                        httpClient.newCall(req).execute().use { resp ->
                            check(resp.isSuccessful) { "HTTP ${resp.code} downloading ${song.id}" }
                            val len = resp.body?.contentLength() ?: totalBytes
                            var written = 0L
                            resp.body?.byteStream()?.use { input ->
                                val buf = ByteArray(32 * 1024)
                                while (true) {
                                    val read = input.read(buf)
                                    if (read == -1) break
                                    out.write(buf, 0, read)
                                    written += read
                                    if (len > 0L) {
                                        _downloading.update {
                                            it + (song.id to (written.toFloat() / len.toFloat()).coerceIn(0f, 1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (finalFile.exists()) finalFile.delete()
                check(partFile.renameTo(finalFile)) { "Failed to finalize download file" }
                database.setDownloaded(song, true)
            } catch (t: Throwable) {
                t.printStackTrace()
            } finally {
                _downloading.update { it - song.id }
            }
        }
    }

    fun deleteDownload(song: DesktopSong) {
        scope.launch {
            val dir = downloadsDirProvider()
            listOf("webm", "m4a", "opus", "mp3", "mp4", "ogg").forEach { ext ->
                File(dir, "${song.id}.$ext").delete()
                File(dir, "${song.id}.$ext.part").delete()
            }
            database.setDownloaded(song, false)
        }
    }
}
