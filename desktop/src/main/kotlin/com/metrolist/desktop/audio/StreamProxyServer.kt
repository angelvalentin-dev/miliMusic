package com.metrolist.desktop.audio

import com.metrolist.desktop.AppPaths
import com.metrolist.desktop.data.DesktopAudioQuality
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StreamProxyServer(
    private val resolver: InnerTubeStreamResolver,
    private val qualityProvider: () -> DesktopAudioQuality,
    private val downloadsDirProvider: () -> File,
    private val cacheEnabledProvider: () -> Boolean,
    private val cacheDirProvider: () -> File = { AppPaths.songCacheDir },
    private val onSongCached: (String) -> Unit = {},
) {
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val server: HttpServer = HttpServer.create(
        InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
        0,
    ).apply {
        executor = Executors.newCachedThreadPool()
        createContext("/stream/") { exchange -> handleStream(exchange) }
        start()
    }

    val port: Int get() = server.address.port

    fun streamUrlFor(videoId: String): String = "http://127.0.0.1:$port/stream/$videoId"

    fun findLocalFile(videoId: String): File? {
        val dlDir = downloadsDirProvider()
        val extensions = listOf("webm", "m4a", "opus", "mp3", "mp4", "ogg")
        for (ext in extensions) {
            val f = File(dlDir, "$videoId.$ext")
            if (f.exists() && f.length() > 0L) return f
        }
        val cached = File(cacheDirProvider(), "$videoId.cache")
        if (cached.exists() && cached.length() > 0L) return cached
        return null
    }

    private fun handleStream(exchange: HttpExchange) {
        try {
            val videoId = exchange.requestURI.path.removePrefix("/stream/").substringBefore("/")
            if (videoId.isBlank()) {
                exchange.sendResponseHeaders(400, -1)
                return
            }

            val localFile = findLocalFile(videoId)
            if (localFile != null) {
                serveLocalFile(exchange, localFile)
                return
            }

            val quality = qualityProvider()
            var resolved = runBlocking { resolver.resolve(videoId, quality) }.getOrNull()
            if (resolved == null) {
                exchange.sendResponseHeaders(502, -1)
                return
            }

            val rangeHeader = exchange.requestHeaders.getFirst("Range")
            if (rangeHeader == null && (resolved.requireBoundedRange || resolved.useRangeChunks) && (resolved.contentLengthBytes ?: 0L) > 0L) {
                streamChunkedUpstream(exchange, resolved)
                return
            }

            var response = openUpstream(resolved, rangeHeader)
            if (response.code == 403 || response.code == 410) {
                response.close()
                resolver.markClientFailed(videoId, resolved.clientName)
                runBlocking { resolver.refreshAfterRejection() }
                resolved = runBlocking { resolver.resolve(videoId, quality, forceRefresh = true) }.getOrNull()
                if (resolved == null) {
                    exchange.sendResponseHeaders(502, -1)
                    return
                }
                response = openUpstream(resolved, rangeHeader)
            }

            response.use { upstream ->
                val status = upstream.code
                val contentType = upstream.header("Content-Type") ?: resolved.mimeType
                val contentRange = upstream.header("Content-Range")
                val acceptRanges = upstream.header("Accept-Ranges") ?: "bytes"
                val contentLength = upstream.body?.contentLength() ?: -1L

                exchange.responseHeaders.set("Content-Type", contentType)
                exchange.responseHeaders.set("Accept-Ranges", acceptRanges)
                if (contentRange != null) exchange.responseHeaders.set("Content-Range", contentRange)

                val responseLength = if (contentLength > 0L) contentLength else 0L
                exchange.sendResponseHeaders(status, responseLength)

                if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) return

                val isFullDownload = status == 200 && cacheEnabledProvider() && rangeHeader == null
                val cacheTemp = if (isFullDownload) File(cacheDirProvider(), "$videoId.part") else null
                val cacheFinal = if (isFullDownload) File(cacheDirProvider(), "$videoId.cache") else null
                val cacheOut = cacheTemp?.outputStream()?.buffered()

                try {
                    val bodyStream = upstream.body?.byteStream()
                    if (bodyStream != null) {
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = bodyStream.read(buffer)
                            if (read == -1) break
                            exchange.responseBody.write(buffer, 0, read)
                            cacheOut?.write(buffer, 0, read)
                        }
                    }
                    cacheOut?.flush()
                    cacheOut?.close()
                    if (cacheTemp != null && cacheFinal != null && cacheTemp.exists() && cacheTemp.length() > 0L) {
                        if (cacheTemp.renameTo(cacheFinal)) {
                            runCatching { onSongCached(videoId) }
                        }
                    }
                } catch (_: Throwable) {
                    runCatching { cacheOut?.close() }
                    cacheTemp?.delete()
                }
            }
        } catch (_: Throwable) {
            runCatching { exchange.sendResponseHeaders(500, -1) }
        } finally {
            runCatching { exchange.close() }
        }
    }

    private fun streamChunkedUpstream(exchange: HttpExchange, resolved: ResolvedStream) {
        val total = resolved.contentLengthBytes ?: return
        val chunkSize = if (resolved.rangeChunkSizeBytes > 0L) resolved.rangeChunkSizeBytes else 8L * 1024L * 1024L
        exchange.responseHeaders.set("Content-Type", resolved.mimeType)
        exchange.responseHeaders.set("Accept-Ranges", "bytes")
        exchange.sendResponseHeaders(200, total)
        if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) return

        val cacheEnabled = cacheEnabledProvider()
        val cacheTemp = if (cacheEnabled) File(cacheDirProvider(), "${resolved.videoId}.part") else null
        val cacheFinal = if (cacheEnabled) File(cacheDirProvider(), "${resolved.videoId}.cache") else null
        val cacheOut = cacheTemp?.outputStream()?.buffered()

        try {
            var offset = 0L
            val buf = ByteArray(32 * 1024)
            while (offset < total) {
                val end = minOf(offset + chunkSize - 1, total - 1)
                openUpstream(resolved, "bytes=$offset-$end").use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code} chunking stream")
                    val input = resp.body?.byteStream() ?: break
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        exchange.responseBody.write(buf, 0, read)
                        cacheOut?.write(buf, 0, read)
                        offset += read
                    }
                }
            }
            cacheOut?.flush()
            cacheOut?.close()
            if (cacheTemp != null && cacheFinal != null && cacheTemp.exists() && cacheTemp.length() == total) {
                if (cacheTemp.renameTo(cacheFinal)) {
                    runCatching { onSongCached(resolved.videoId) }
                }
            }
        } catch (_: Throwable) {
            runCatching { cacheOut?.close() }
            cacheTemp?.delete()
        }
    }

    private fun openUpstream(resolved: ResolvedStream, rangeHeader: String?): okhttp3.Response {
        val reqBuilder = Request.Builder().url(resolved.url)
        resolved.headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        if (rangeHeader != null) {
            reqBuilder.header("Range", rangeHeader)
        } else if ((resolved.requireBoundedRange || resolved.useRangeChunks) && resolved.rangeChunkSizeBytes > 0L) {
            val end = (resolved.rangeChunkSizeBytes - 1).coerceAtLeast(0L)
            reqBuilder.header("Range", "bytes=0-$end")
        }
        return httpClient.newCall(reqBuilder.build()).execute()
    }

    private fun serveLocalFile(exchange: HttpExchange, file: File) {
        val total = file.length()
        val range = exchange.requestHeaders.getFirst("Range")
        val contentType = when (file.extension.lowercase()) {
            "m4a", "mp4" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "ogg", "opus" -> "audio/ogg"
            else -> "audio/webm"
        }
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.responseHeaders.set("Accept-Ranges", "bytes")

        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").substringBefore(",")
            val parts = spec.split("-")
            val start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
            val end = parts.getOrNull(1)?.toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
            val len = (end - start + 1).coerceAtLeast(0L)
            exchange.responseHeaders.set("Content-Range", "bytes $start-$end/$total")
            exchange.sendResponseHeaders(206, len)
            if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) return
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(32 * 1024)
                var remaining = len
                while (remaining > 0L) {
                    val read = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (read == -1) break
                    exchange.responseBody.write(buf, 0, read)
                    remaining -= read
                }
            }
        } else {
            exchange.sendResponseHeaders(200, total)
            if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) return
            file.inputStream().buffered().use { it.copyTo(exchange.responseBody) }
        }
    }

    fun close() {
        runCatching { server.stop(0) }
    }
}
