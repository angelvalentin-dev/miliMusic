package com.metrolist.desktop.audio

import com.metrolist.desktop.AppPaths
import com.metrolist.desktop.data.DesktopAudioQuality
import com.metrolist.innertube.YouTube
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality as InnerTubeXAudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock

data class ResolvedStream(
    val videoId: String,
    val url: String,
    val headers: Map<String, String>,
    val mimeType: String,
    val contentLengthBytes: Long?,
    val expiresAtMs: Long,
    val clientName: String,
    val requireBoundedRange: Boolean,
    val rangeChunkSizeBytes: Long,
    val useRangeChunks: Boolean,
)

class InnerTubeStreamResolver {
    private val bundleMutex = Mutex()

    @Volatile
    private var currentBundle: ExtractionBundle? = null
    private val cache = ConcurrentHashMap<String, ResolvedStream>()
    private val failedClients = ConcurrentHashMap<String, Pair<Set<String>, Long>>()

    suspend fun resolve(
        videoId: String,
        quality: DesktopAudioQuality,
        forceRefresh: Boolean = false,
    ): Result<ResolvedStream> {
        if (!forceRefresh) {
            cache[videoId]?.takeIf { it.expiresAtMs > System.currentTimeMillis() + 15_000L }?.let {
                return Result.success(it)
            }
        } else {
            cache.remove(videoId)
        }

        return try {
            val hints = ContentHints().withStreamCapabilities(
                allowHls = false,
                allowSabr = false,
                allowBoundedRange = true,
            )
            val excluded = currentFailedClients(videoId)
            val stream = requireNotNull(
                bundle().extractor.extract(
                    videoId = videoId,
                    hints = hints,
                    excludedClients = excluded,
                    audioQuality = quality.toInnerTubeX(),
                    clientPlaybackNonce = generateClientPlaybackNonce(),
                ),
            ) { "InnerTubeX returned no playable stream for $videoId" }

            val resolved = stream.toResolvedStream()
            cache[videoId] = resolved
            Result.success(resolved)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun markClientFailed(videoId: String, clientName: String) {
        val now = System.currentTimeMillis()
        failedClients.compute(videoId) { _, prev ->
            (prev?.first.orEmpty() + clientName) to now
        }
        cache.remove(videoId)
    }

    suspend fun refreshAfterRejection(): Boolean =
        runCatching { bundle().cipherService.refreshAfterStreamRejection() }.getOrDefault(false)

    private fun currentFailedClients(videoId: String): Set<String> {
        val entry = failedClients[videoId] ?: return emptySet()
        if (System.currentTimeMillis() - entry.second > 5 * 60 * 1000L) {
            failedClients.remove(videoId)
            return emptySet()
        }
        return entry.first
    }

    private suspend fun bundle(): ExtractionBundle {
        val transport = YouTube.extractionTransport()
        currentBundle?.takeIf { it.generation == transport.generation }?.let { return it }
        return bundleMutex.withLock {
            val latest = YouTube.extractionTransport()
            currentBundle?.takeIf { it.generation == latest.generation }?.let { return@withLock it }
            runCatching { currentBundle?.cipherService?.dispose() }
            val remoteStore = RemotePlayerConfigStore(latest.httpClient, DesktopPlayerConfigRepo, logger)
            val cipherService = YouTubeCipherService(latest.httpClient, remoteStore, logger)
            val extractor = InnerTubeExtractor(
                configParser = YtConfigParserImpl(latest.httpClient, latest.innerTube, remoteStore, logger)
                    .withEmbeddedConfigFallback(),
                cipherService = cipherService,
                innerTube = latest.innerTube,
                tokenProvider = noOpTokenProvider,
                logger = logger,
            )
            ExtractionBundle(latest.generation, cipherService, extractor).also { currentBundle = it }
        }
    }

    private fun YtConfigParser.withEmbeddedConfigFallback(): YtConfigParser =
        object : YtConfigParser by this {
            override suspend fun fetchConfig(videoId: String, useLoginCookies: Boolean) =
                try {
                    this@withEmbeddedConfigFallback.fetchConfig(videoId, useLoginCookies)
                } catch (_: IllegalStateException) {
                    this@withEmbeddedConfigFallback.fetchEmbeddedConfig(videoId, useLoginCookies = false)
                }
        }

    private fun DesktopAudioQuality.toInnerTubeX(): InnerTubeXAudioQuality =
        when (this) {
            DesktopAudioQuality.HIGH -> InnerTubeXAudioQuality.HIGH
            DesktopAudioQuality.LOW -> InnerTubeXAudioQuality.LOW
            DesktopAudioQuality.AUTO -> InnerTubeXAudioQuality.AUTO
        }

    private fun ExtractedStream.toResolvedStream(): ResolvedStream {
        val expiresAtMs = expiresAt?.toEpochMilliseconds() ?: (System.currentTimeMillis() + 5 * 60 * 1000L)
        return ResolvedStream(
            videoId = videoId,
            url = audioUrl,
            headers = headers,
            mimeType = mimeType.orEmpty().ifBlank { "audio/webm" },
            contentLengthBytes = contentLengthBytes,
            expiresAtMs = expiresAtMs,
            clientName = clientName,
            requireBoundedRange = requireBoundedRange,
            rangeChunkSizeBytes = rangeChunkSizeBytes,
            useRangeChunks = useRangeChunks,
        )
    }

    private val noOpTokenProvider = object : TokenProvider {
        override val capabilities = TokenProviderCapabilities(providers = emptySet(), usesWebView = false)
        override suspend fun getPoToken(videoId: String, visitorData: String, cookie: String?): PoTokenResult? = null
        override suspend fun close() = Unit
    }

    private val logger = InnerTubeLogger { event ->
        if (event.level == InnerTubeLogLevel.ERROR || event.level == InnerTubeLogLevel.WARN) {
            System.err.println("[InnerTubeX/${event.level}] ${event.tag}: ${event.message} ${event.details}")
        }
    }

    private data class ExtractionBundle(
        val generation: Long,
        val cipherService: YouTubeCipherService,
        val extractor: InnerTubeExtractor,
    )

    private object DesktopPlayerConfigRepo : PlayerConfigRepository {
        private const val URL =
            "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"
        private val file = File(AppPaths.dataDir, "player_config.properties")
        private val props = Properties().apply {
            if (file.exists()) runCatching { file.inputStream().use(::load) }
        }

        private fun save() = synchronized(this) {
            runCatching { file.outputStream().use { props.store(it, null) } }
        }

        override val enabled: Boolean = true
        override val sourceUrl: String = URL
        override val defaultSourceUrl: String = URL
        override var cachedJson: String
            get() = props.getProperty("json", "")
            set(value) { props.setProperty("json", value); save() }
        override var cachedAtMs: Long
            get() = props.getProperty("cached_at_ms", "0").toLongOrNull() ?: 0L
            set(value) { props.setProperty("cached_at_ms", value.toString()); save() }
        override var cachedSourceUrl: String
            get() = props.getProperty("source_url", "")
            set(value) { props.setProperty("source_url", value); save() }
        override var cachedEtag: String
            get() = props.getProperty("etag", "")
            set(value) { props.setProperty("etag", value); save() }
    }
}
