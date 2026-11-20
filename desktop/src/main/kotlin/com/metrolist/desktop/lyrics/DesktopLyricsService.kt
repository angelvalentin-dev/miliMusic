package com.metrolist.desktop.lyrics

import com.metrolist.desktop.data.DesktopSettings
import com.metrolist.desktop.db.DesktopDatabase
import com.metrolist.desktop.db.DesktopLyricsEntry
import com.metrolist.desktop.db.DesktopSong
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.WatchEndpoint
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

data class LyricsPayload(
    val rawLyrics: String,
    val provider: String,
    val entries: List<DesktopLyricsEntry>,
    val isSynced: Boolean,
)

class DesktopLyricsService(
    private val database: DesktopDatabase,
) {
    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }

    suspend fun getLyrics(song: DesktopSong, settings: DesktopSettings): LyricsPayload? = withContext(Dispatchers.IO) {
        database.getLyrics(song.id)?.let { (raw, provider) ->
            if (raw.isNotBlank() && raw != LYRICS_NOT_FOUND) {
                val parsed = parseLyrics(raw)
                return@withContext LyricsPayload(raw, provider, parsed.first, parsed.second)
            }
        }

        val cleanedTitle = cleanTitle(song.title)
        val primaryArtist = song.artists.firstOrNull()?.name ?: song.artistDisplay

        if (settings.enableLrcLib) {
            fetchFromLrcLib(cleanedTitle, primaryArtist, song.albumName, song.duration)?.let { raw ->
                database.saveLyrics(song.id, raw, "LrcLib")
                val parsed = parseLyrics(raw)
                return@withContext LyricsPayload(raw, "LrcLib", parsed.first, parsed.second)
            }
        }

        if (settings.enableBetterLyrics) {
            fetchFromBetterLyrics(cleanedTitle, primaryArtist, song.albumName, song.duration)?.let { raw ->
                database.saveLyrics(song.id, raw, "BetterLyrics")
                val parsed = parseLyrics(raw)
                return@withContext LyricsPayload(raw, "BetterLyrics", parsed.first, parsed.second)
            }
        }

        runCatching {
            val next = YouTube.next(WatchEndpoint(videoId = song.id)).getOrNull()
            val lyricsBrowseId = next?.lyricsEndpoint?.browseId
            if (!lyricsBrowseId.isNullOrBlank()) {
                YouTube.lyrics(com.metrolist.innertube.models.BrowseEndpoint(browseId = lyricsBrowseId)).getOrNull()
            } else {
                null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { raw ->
            database.saveLyrics(song.id, raw, "YouTube Music")
            val parsed = parseLyrics(raw)
            return@withContext LyricsPayload(raw, "YouTube Music", parsed.first, parsed.second)
        }

        null
    }

    private suspend fun fetchFromLrcLib(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): String? {
        val exact = runCatching {
            httpClient.get("https://lrclib.net/api/get") {
                parameter("track_name", title)
                parameter("artist_name", artist)
                if (!album.isNullOrBlank()) parameter("album_name", album)
                if (durationSec > 0) parameter("duration", durationSec)
            }.body<LrcLibTrack>()
        }.getOrNull()

        exact?.bestLyrics()?.let { return it }

        val searchResults = runCatching {
            httpClient.get("https://lrclib.net/api/search") {
                parameter("track_name", title)
                parameter("artist_name", artist)
            }.body<List<LrcLibTrack>>()
        }.getOrNull().orEmpty()

        val best = searchResults
            .filter { !it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank() }
            .minByOrNull { track ->
                val durationPenalty = if (durationSec > 0 && track.duration != null) {
                    abs(track.duration - durationSec)
                } else 0.0
                val syncBonus = if (!track.syncedLyrics.isNullOrBlank()) 0.0 else 100.0
                durationPenalty + syncBonus
            }
        return best?.bestLyrics()
    }

    private suspend fun fetchFromBetterLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): String? = runCatching {
        val resp = httpClient.get("https://lyrics-api.boidu.dev/getLyrics") {
            parameter("s", title)
            parameter("a", artist)
            if (!album.isNullOrBlank()) parameter("al", album)
            if (durationSec > 0) parameter("d", durationSec)
        }.body<BetterLyricsResponse>()
        resp.ttml?.takeIf { it.isNotBlank() }?.let { parseTtmlToLrc(it) }
    }.getOrNull()

    private fun parseTtmlToLrc(ttml: String): String? {
        val lineRegex = Regex("""<p[^>]*begin="([^"]+)"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
        val tagRegex = Regex("""<[^>]+>""")
        val lines = lineRegex.findAll(ttml).mapNotNull { match ->
            val ms = parseTtmlTime(match.groupValues[1]) ?: return@mapNotNull null
            val text = match.groupValues[2].replace(tagRegex, "").trim()
            if (text.isBlank()) return@mapNotNull null
            val min = ms / 60_000
            val sec = (ms % 60_000) / 1_000
            val cs = (ms % 1_000) / 10
            "[%02d:%02d.%02d]%s".format(min, sec, cs, text)
        }.toList()
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun parseTtmlTime(raw: String): Long? {
        val parts = raw.trim().split(":")
        return when (parts.size) {
            1 -> (parts[0].removeSuffix("s").toDoubleOrNull()?.times(1000.0))?.toLong()
            2 -> {
                val m = parts[0].toLongOrNull() ?: return null
                val s = parts[1].toDoubleOrNull() ?: return null
                m * 60_000L + (s * 1000.0).toLong()
            }
            3 -> {
                val h = parts[0].toLongOrNull() ?: return null
                val m = parts[1].toLongOrNull() ?: return null
                val s = parts[2].toDoubleOrNull() ?: return null
                h * 3_600_000L + m * 60_000L + (s * 1000.0).toLong()
            }
            else -> null
        }
    }

    fun parseLyrics(raw: String): Pair<List<DesktopLyricsEntry>, Boolean> {
        val lrcRegex = Regex("""\[(\d{1,2}):(\d{2})\.(\d{2,3})](.*)""")
        val wordTagRegex = Regex("""<\d{1,2}:\d{2}\.\d{2,3}>""")
        val synced = mutableListOf<DesktopLyricsEntry>()
        for (line in raw.lines()) {
            val match = lrcRegex.find(line.trim()) ?: continue
            val min = match.groupValues[1].toLongOrNull() ?: continue
            val sec = match.groupValues[2].toLongOrNull() ?: continue
            val fracStr = match.groupValues[3]
            val msPart = if (fracStr.length == 2) fracStr.toLong() * 10L else fracStr.toLong()
            val text = match.groupValues[4]
                .replace(wordTagRegex, "")
                .replace(Regex("""^\{bg\}|\{agent:[^}]+\}"""), "")
                .trim()
            synced += DesktopLyricsEntry(min * 60_000L + sec * 1_000L + msPart, text)
        }
        if (synced.isNotEmpty()) {
            return synced.sortedBy { it.timeMs } to true
        }
        val plain = raw.lines()
            .mapIndexed { idx, line -> DesktopLyricsEntry(idx.toLong(), line.trim()) }
        return plain to false
    }

    private fun cleanTitle(title: String): String =
        title.replace(Regex("""\s*[(\[].*?[)\]]"""), "").trim().ifBlank { title }

    @Serializable
    private data class LrcLibTrack(
        val syncedLyrics: String? = null,
        val plainLyrics: String? = null,
        val duration: Double? = null,
    ) {
        fun bestLyrics(): String? = syncedLyrics?.takeIf { it.isNotBlank() } ?: plainLyrics?.takeIf { it.isNotBlank() }
    }

    @Serializable
    private data class BetterLyricsResponse(
        val ttml: String? = null,
    )

    fun close() {
        runCatching { httpClient.close() }
    }

    companion object {
        const val LYRICS_NOT_FOUND = "LYRICS_NOT_FOUND"
    }
}
