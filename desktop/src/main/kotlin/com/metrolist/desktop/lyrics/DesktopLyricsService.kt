package com.metrolist.desktop.lyrics

import com.metrolist.desktop.data.DesktopSettings
import com.metrolist.desktop.db.DesktopDatabase
import com.metrolist.desktop.db.DesktopLyricWord
import com.metrolist.desktop.db.DesktopLyricsEntry
import com.metrolist.desktop.db.DesktopSong
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.BrowseEndpoint
import com.metrolist.innertube.models.WatchEndpoint
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

data class LyricsPayload(
    val rawLyrics: String,
    val provider: String,
    val entries: List<DesktopLyricsEntry>,
    val isSynced: Boolean,
    val hasWordTimings: Boolean = false,
    val translatedLines: List<String> = emptyList(),
    val translationLanguage: String = "",
)

data class LyricsSearchCandidate(
    val provider: String,
    val rawLyrics: String,
    val preview: String,
    val isSynced: Boolean,
)

class DesktopLyricsService(
    private val database: DesktopDatabase,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
            json(json, ContentType.Text.Plain)
            json(json, ContentType.Text.Html)
        }
    }

    @Volatile
    private var cachedAppleToken: String? = null

    suspend fun getLyrics(
        song: DesktopSong,
        settings: DesktopSettings,
        forceRefresh: Boolean = false,
    ): LyricsPayload? = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            database.getLyricsRecord(song.id)?.let { rec ->
                if (rec.lyrics.isNotBlank() && rec.lyrics != LYRICS_NOT_FOUND) {
                    return@withContext buildPayload(
                        raw = rec.lyrics,
                        provider = rec.provider,
                        song = song,
                        settings = settings,
                        cachedTranslated = rec.translatedLyrics.takeIf { it.isNotBlank() }?.lines().orEmpty(),
                        cachedTranslationLang = rec.translationLanguage,
                    )
                }
            }
        }

        val cleanedTitle = cleanTitle(song.title)
        val primaryArtist = song.artists.firstOrNull()?.name ?: song.artistDisplay

        for (provider in settings.lyricsProviderOrder) {
            if (!isProviderEnabled(provider, settings)) continue
            val raw = withTimeoutOrNull(8_000L) {
                fetchFromProvider(provider, song.id, cleanedTitle, primaryArtist, song.albumName, song.duration)
            }
            if (!raw.isNullOrBlank()) {
                val filtered = DesktopLyricsRomanizer.filterCreditLines(raw)
                database.saveLyrics(song.id, filtered, provider)
                return@withContext buildPayload(filtered, provider, song, settings)
            }
        }
        null
    }

    suspend fun searchAllProviders(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
        videoId: String,
    ): List<LyricsSearchCandidate> = withContext(Dispatchers.IO) {
        val cleaned = cleanTitle(title)
        val providers = listOf("BetterLyrics", "LrcLib", "Paxsenix", "LyricsPlus", "KuGou", "Zemer", "YouTubeSubtitle", "YouTube")
        coroutineScope {
            providers.map { provider ->
                async {
                    val raw = withTimeoutOrNull(7_000L) {
                        fetchFromProvider(provider, videoId, cleaned, artist, album, durationSec)
                    } ?: return@async null
                    val filtered = DesktopLyricsRomanizer.filterCreditLines(raw)
                    val (entries, synced) = parseLyrics(filtered)
                    val preview = entries.take(3).joinToString(" / ") { it.text }.take(140)
                    LyricsSearchCandidate(provider, filtered, preview, synced)
                }
            }.awaitAll().filterNotNull()
        }
    }

    fun applyManualLyrics(song: DesktopSong, rawLyrics: String, settings: DesktopSettings, provider: String = "Manual"): LyricsPayload {
        val cleaned = rawLyrics.trim()
        database.saveLyrics(song.id, cleaned, provider)
        return buildPayload(cleaned, provider, song, settings)
    }

    suspend fun translatePayload(song: DesktopSong, payload: LyricsPayload, settings: DesktopSettings): Result<LyricsPayload> {
        val plainLines = payload.entries.map { it.text }
        return DesktopLyricsTranslator.translateLines(plainLines, settings).map { translated ->
            database.saveLyricsTranslation(song.id, translated.joinToString("\n"), settings.translateLanguage, settings.translateMode)
            val updatedEntries = payload.entries.mapIndexed { idx, entry ->
                entry.copy(translatedText = translated.getOrNull(idx)?.takeIf { it.isNotBlank() })
            }
            payload.copy(
                entries = updatedEntries,
                translatedLines = translated,
                translationLanguage = settings.translateLanguage,
            )
        }
    }

    fun buildPayload(
        raw: String,
        provider: String,
        song: DesktopSong,
        settings: DesktopSettings,
        cachedTranslated: List<String> = emptyList(),
        cachedTranslationLang: String = "",
    ): LyricsPayload {
        val (parsedEntries, isSynced) = parseLyrics(raw)
        val shouldRomanize = song.romanizeLyrics && settings.lyricsRomanizeLanguages.isNotEmpty()
        val enriched = parsedEntries.mapIndexed { idx, entry ->
            val roman = if (shouldRomanize) {
                DesktopLyricsRomanizer.romanizeLine(entry.text, settings.lyricsRomanizeLanguages)
            } else null
            val translated = cachedTranslated.getOrNull(idx)?.takeIf { it.isNotBlank() }
            entry.copy(romanizedText = roman, translatedText = translated)
        }
        val hasWordTimings = enriched.any { it.words.isNotEmpty() }
        return LyricsPayload(
            rawLyrics = raw,
            provider = provider,
            entries = enriched,
            isSynced = isSynced,
            hasWordTimings = hasWordTimings,
            translatedLines = cachedTranslated,
            translationLanguage = cachedTranslationLang,
        )
    }

    private fun isProviderEnabled(provider: String, settings: DesktopSettings): Boolean = when (provider) {
        "LrcLib" -> settings.enableLrcLib
        "BetterLyrics" -> settings.enableBetterLyrics
        "KuGou" -> settings.enableKugou
        "Paxsenix" -> settings.enablePaxsenix
        "LyricsPlus" -> settings.enableLyricsPlus
        "Zemer" -> settings.enableZemer
        "YouTubeSubtitle", "YouTube" -> true
        else -> true
    }

    private suspend fun fetchFromProvider(
        provider: String,
        videoId: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): String? = when (provider) {
        "LrcLib" -> fetchFromLrcLib(title, artist, album, durationSec)
        "BetterLyrics" -> fetchFromBetterLyrics(title, artist, album, durationSec)
        "KuGou" -> fetchFromKuGou(title, artist, album, durationSec)
        "Paxsenix" -> fetchFromPaxsenix(title, artist, album, durationSec)
        "LyricsPlus" -> fetchFromLyricsPlus(title, artist, album, durationSec)
        "Zemer" -> fetchFromZemer(videoId)
        "YouTubeSubtitle" -> runCatching { YouTube.transcript(videoId).getOrNull() }.getOrNull()?.takeIf { it.isNotBlank() }
        "YouTube" -> fetchFromYouTubeMusic(videoId)
        else -> null
    }

    private suspend fun fetchFromYouTubeMusic(videoId: String): String? = runCatching {
        val next = YouTube.next(WatchEndpoint(videoId = videoId)).getOrNull()
        val browseId = next?.lyricsEndpoint?.browseId ?: return@runCatching null
        YouTube.lyrics(BrowseEndpoint(browseId = browseId)).getOrNull()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private suspend fun fetchFromLrcLib(title: String, artist: String, album: String?, durationSec: Int): String? {
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

        return searchResults
            .filter { !it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank() }
            .minByOrNull { track ->
                val durPenalty = if (durationSec > 0 && track.duration != null) abs(track.duration - durationSec) else 0.0
                val syncBonus = if (!track.syncedLyrics.isNullOrBlank()) 0.0 else 100.0
                durPenalty + syncBonus
            }?.bestLyrics()
    }

    private suspend fun fetchFromBetterLyrics(title: String, artist: String, album: String?, durationSec: Int): String? =
        runCatching {
            val resp = httpClient.get("https://lyrics-api.boidu.dev/getLyrics") {
                parameter("s", title)
                parameter("a", artist)
                if (!album.isNullOrBlank()) parameter("al", album)
                if (durationSec > 0) parameter("d", durationSec)
            }.body<BetterLyricsResponse>()
            resp.ttml?.takeIf { it.isNotBlank() }?.let { parseTtmlToExtendedLrc(it) }
        }.getOrNull()

    private suspend fun fetchFromKuGou(title: String, artist: String, album: String?, durationSec: Int): String? =
        runCatching {
            val keyword = "$title - $artist" + if (!album.isNullOrBlank()) " $album" else ""
            val resp = httpClient.get("https://lyrics.kugou.com/search") {
                parameter("ver", 1)
                parameter("man", "yes")
                parameter("client", "pc")
                if (durationSec > 0) parameter("duration", durationSec * 1000)
                parameter("keyword", keyword)
            }
            if (!resp.status.isSuccess()) return@runCatching null
            val obj = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val candidate = obj["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@runCatching null
            val id = candidate["id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
            val accesskey = candidate["accesskey"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
            val dlResp = httpClient.get("https://lyrics.kugou.com/download") {
                parameter("fmt", "lrc")
                parameter("charset", "utf8")
                parameter("client", "pc")
                parameter("ver", 1)
                parameter("id", id)
                parameter("accesskey", accesskey)
            }
            val b64 = json.parseToJsonElement(dlResp.bodyAsText()).jsonObject["content"]?.jsonPrimitive?.contentOrNull
                ?: return@runCatching null
            val decoded = String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
            decoded.lines().filter { it.matches(Regex("""\[\d{2}:\d{2}\.\d{2,3}].*""")) }.joinToString("\n").ifBlank { null }
        }.getOrNull()

    private suspend fun fetchFromLyricsPlus(title: String, artist: String, album: String?, durationSec: Int): String? {
        // 1. Try Binimum TTML API first
        runCatching {
            val resp = httpClient.get("https://lyrics-api.binimum.org/") {
                parameter("track", title)
                parameter("artist", artist)
                if (!album.isNullOrBlank()) parameter("album", album)
                if (durationSec > 0) parameter("duration", durationSec * 1000)
            }
            if (resp.status.isSuccess()) {
                val obj = json.parseToJsonElement(resp.bodyAsText()).jsonObject
                val url = obj["results"]?.jsonArray?.firstOrNull()?.jsonObject?.get("lyricsUrl")?.jsonPrimitive?.contentOrNull
                if (!url.isNullOrBlank()) {
                    val ttml = httpClient.get(url).bodyAsText()
                    parseTtmlToExtendedLrc(ttml)?.let { return it }
                }
            }
        }
        // 2. Fallback to LyricsPlus v2 mirrors
        val mirrors = listOf(
            "https://lyricsplus.binimum.org",
            "https://lyricsplus.atomix.one",
            "https://lyricsplus.prjktla.my.id",
            "https://lyricsplus-seven.vercel.app",
        )
        for (base in mirrors) {
            val lrc = runCatching {
                val resp = httpClient.get("$base/v2/lyrics/get") {
                    parameter("title", title)
                    parameter("artist", artist)
                    if (durationSec > 0) parameter("duration", durationSec)
                    if (!album.isNullOrBlank()) parameter("album", album)
                }
                if (!resp.status.isSuccess()) return@runCatching null
                val parsed = json.decodeFromString<LyricsPlusResponse>(resp.bodyAsText())
                convertLyricsPlusToLrc(parsed)
            }.getOrNull()
            if (!lrc.isNullOrBlank()) return lrc
        }
        return null
    }

    private suspend fun fetchFromPaxsenix(title: String, artist: String, album: String?, durationSec: Int): String? =
        runCatching {
            val token = getAppleMusicToken() ?: return@runCatching null
            val q = URLEncoder.encode("$title $artist", "UTF-8")
            val searchResp = httpClient.get("https://amp-api.music.apple.com/v1/catalog/us/search?term=$q&types=songs&limit=10&l=en-US&platform=web") {
                header("Authorization", "Bearer $token")
                header("Origin", "https://music.apple.com")
                header("Referer", "https://music.apple.com/")
            }
            if (!searchResp.status.isSuccess()) {
                if (searchResp.status.value == 401) cachedAppleToken = null
                return@runCatching null
            }
            val songId = json.parseToJsonElement(searchResp.bodyAsText()).jsonObject["results"]
                ?.jsonObject?.get("songs")?.jsonObject?.get("data")?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull ?: return@runCatching null

            val lyrResp = httpClient.get("https://lyrics.paxsenix.org/apple-music/lyrics") {
                parameter("id", songId)
                header("User-Agent", "Metrolist/13.7.0")
            }
            if (!lyrResp.status.isSuccess()) return@runCatching null
            val obj = json.parseToJsonElement(lyrResp.bodyAsText()).jsonObject
            obj["ttmlContent"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { ttml ->
                parseTtmlToExtendedLrc(ttml)?.let { return@runCatching it }
            }
            obj["elrcMultiPerson"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return@runCatching it }
            obj["elrc"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return@runCatching it }
            obj["plain"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }.getOrNull()

    private suspend fun getAppleMusicToken(): String? {
        cachedAppleToken?.let { return it }
        return runCatching {
            val html = httpClient.get("https://beta.music.apple.com").bodyAsText()
            val jsPath = Regex("""/assets/index~[^/"]+\.js""").find(html)?.value ?: return null
            val js = httpClient.get("https://beta.music.apple.com$jsPath").bodyAsText()
            Regex("""eyJ[A-Za-z0-9\-_=]+\.[A-Za-z0-9\-_=]+\.[A-Za-z0-9\-_=]+""").find(js)?.value?.also {
                cachedAppleToken = it
            }
        }.getOrNull()
    }

    private suspend fun fetchFromZemer(videoId: String): String? = runCatching {
        val resp = httpClient.get("https://search.zemer.io/lyrics/resolve") {
            parameter("videoId", videoId)
            header(HttpHeaders.Accept, "application/json")
        }
        if (!resp.status.isSuccess()) return@runCatching null
        val obj = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val sources = obj["sources"]?.jsonArray.orEmpty()
        for (srcEl in sources) {
            val src = srcEl.jsonObject
            val synced = src["syncedLrc"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (synced != null) return@runCatching synced
            val plain = src["plain"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (plain != null) return@runCatching plain
            val trackId = src["trackId"]?.jsonPrimitive?.contentOrNull
            if (src["type"]?.jsonPrimitive?.contentOrNull == "lrclib" && !trackId.isNullOrBlank()) {
                val track = runCatching { httpClient.get("https://lrclib.net/api/get/$trackId").body<LrcLibTrack>() }.getOrNull()
                track?.bestLyrics()?.let { return@runCatching it }
            }
        }
        null
    }.getOrNull()

    /** Full DOM-based TTML parser preserving word timings, duet agents, background vocals, and global lyricOffset. */
    fun parseTtmlToExtendedLrc(ttml: String): String? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
        }
        val doc = factory.newDocumentBuilder().parse(ttml.byteInputStream())
        val root = doc.documentElement
        var globalOffset = 0.0
        findChild(root, "head")?.let { head ->
            findChild(head, "metadata")?.let { meta ->
                findChild(meta, "audio")?.let { audio ->
                    globalOffset = audio.getAttribute("lyricOffset").toDoubleOrNull() ?: 0.0
                }
            }
        }
        val body = findChild(root, "body") ?: return@runCatching null
        val out = StringBuilder()
        fun walk(el: Element, divAgent: String?) {
            val local = el.localName ?: el.nodeName.substringAfterLast(':')
            var curAgent = divAgent
            if (local == "div") {
                val a = getTtmlAttr(el, "agent")
                if (a.isNotEmpty()) curAgent = a
            } else if (local == "p") {
                val begin = el.getAttribute("begin").ifBlank { findFirstSpanBegin(el).orEmpty() }
                if (begin.isNotBlank()) {
                    val startSec = parseTtmlTimeSec(begin) + globalOffset
                    val agent = getTtmlAttr(el, "agent").ifBlank { curAgent.orEmpty() }
                    val isBg = getTtmlAttr(el, "role") == "x-bg"
                    val words = mutableListOf<Triple<String, Double, Double>>()
                    val bgLines = mutableListOf<Pair<Double, String>>()
                    var child = el.firstChild
                    while (child != null) {
                        if (child is Element && (child.localName ?: child.nodeName.substringAfterLast(':')) == "span") {
                            val role = getTtmlAttr(child, "role")
                            if (role == "x-bg") {
                                val bgBegin = child.getAttribute("begin").takeIf { it.isNotBlank() }?.let { parseTtmlTimeSec(it) + globalOffset } ?: startSec
                                val bgText = child.textContent?.trim().orEmpty()
                                if (bgText.isNotBlank()) bgLines += bgBegin to bgText
                            } else if (role != "x-translation" && role != "x-roman") {
                                val wb = child.getAttribute("begin")
                                val we = child.getAttribute("end")
                                val wt = child.textContent?.trim().orEmpty()
                                if (wb.isNotBlank() && we.isNotBlank() && wt.isNotBlank()) {
                                    words += Triple(wt, parseTtmlTimeSec(wb) + globalOffset, parseTtmlTimeSec(we) + globalOffset)
                                }
                            }
                        }
                        child = child.nextSibling
                    }
                    val text = if (words.isNotEmpty()) words.joinToString(" ") { it.first } else directText(el).trim()
                    if (text.isNotBlank()) {
                        val tag = when {
                            isBg -> "{bg}"
                            agent.isNotBlank() -> "{agent:$agent}"
                            else -> ""
                        }
                        out.append(formatLrcTime((startSec * 1000).toLong())).append(tag).append(text).append('\n')
                        if (words.isNotEmpty()) {
                            out.append('<')
                            words.forEachIndexed { idx, w ->
                                out.append(w.first).append(':').append(w.second).append(':').append(w.third)
                                if (idx < words.lastIndex) out.append('|')
                            }
                            out.append(">\n")
                        }
                    }
                    bgLines.forEach { (bgSec, bgText) ->
                        out.append(formatLrcTime((bgSec * 1000).toLong())).append("{bg}").append(bgText).append('\n')
                    }
                }
                return
            }
            var c = el.firstChild
            while (c != null) {
                if (c is Element) walk(c, curAgent)
                c = c.nextSibling
            }
        }
        walk(body, null)
        out.toString().trim().ifBlank { null }
    }.getOrNull()

    private fun findChild(parent: Element, localName: String): Element? {
        var c = parent.firstChild
        while (c != null) {
            if (c is Element && (c.localName ?: c.nodeName.substringAfterLast(':')) == localName) return c
            c = c.nextSibling
        }
        return null
    }

    private fun getTtmlAttr(el: Element, name: String): String =
        el.getAttribute("ttm:$name").ifEmpty { el.getAttribute(name) }

    private fun findFirstSpanBegin(p: Element): String? {
        var c = p.firstChild
        while (c != null) {
            if (c is Element && (c.localName ?: c.nodeName.substringAfterLast(':')) == "span") {
                val b = c.getAttribute("begin")
                if (b.isNotBlank()) return b
            }
            c = c.nextSibling
        }
        return null
    }

    private fun directText(el: Element): String {
        val sb = StringBuilder()
        var c = el.firstChild
        while (c != null) {
            if (c.nodeType == Node.TEXT_NODE) sb.append(c.textContent)
            else if (c is Element && getTtmlAttr(c, "role") !in setOf("x-bg", "x-translation", "x-roman")) {
                sb.append(c.textContent)
            }
            c = c.nextSibling
        }
        return sb.toString()
    }

    private fun parseTtmlTimeSec(raw: String): Double {
        val t = raw.trim()
        val parts = t.split(":")
        return when (parts.size) {
            3 -> (parts[0].toDoubleOrNull() ?: 0.0) * 3600.0 + (parts[1].toDoubleOrNull() ?: 0.0) * 60.0 + (parts[2].toDoubleOrNull() ?: 0.0)
            2 -> (parts[0].toDoubleOrNull() ?: 0.0) * 60.0 + (parts[1].toDoubleOrNull() ?: 0.0)
            else -> t.removeSuffix("s").toDoubleOrNull() ?: 0.0
        }
    }

    private fun formatLrcTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val min = safe / 60_000
        val sec = (safe % 60_000) / 1_000
        val cs = (safe % 1_000) / 10
        return String.format(Locale.US, "[%02d:%02d.%02d]", min, sec, cs)
    }

    private fun convertLyricsPlusToLrc(resp: LyricsPlusResponse): String? {
        val lines = resp.lyrics?.takeIf { it.isNotEmpty() } ?: return null
        val isWord = resp.type.equals("Word", ignoreCase = true)
        val sb = StringBuilder()
        for (line in lines) {
            val mainWords = line.syllabus?.filter { !it.isBackground }.orEmpty()
            val text = if (isWord && mainWords.isNotEmpty()) mainWords.joinToString("") { it.text }.trim() else line.text.trim()
            if (text.isNotBlank()) {
                val agent = line.element?.singer?.takeIf { it.isNotBlank() }?.let { "{agent:$it}" }.orEmpty()
                sb.append(formatLrcTime(line.time)).append(agent).append(text).append('\n')
                if (isWord && mainWords.isNotEmpty()) {
                    sb.append('<')
                    mainWords.forEachIndexed { idx, w ->
                        sb.append(w.text.trim()).append(':').append(w.time / 1000.0).append(':').append((w.time + w.duration) / 1000.0)
                        if (idx < mainWords.lastIndex) sb.append('|')
                    }
                    sb.append(">\n")
                }
            }
        }
        return sb.toString().trim().ifBlank { null }
    }

    fun parseLyrics(raw: String): Pair<List<DesktopLyricsEntry>, Boolean> {
        val lrcRegex = Regex("""\[(\d{1,2}):(\d{2})\.(\d{2,3})](.*)""")
        val inlineWordTagRegex = Regex("""<\d{1,2}:\d{2}\.\d{2,3}>""")
        val agentRegex = Regex("""\{agent:([^}]+)}""")
        val synced = mutableListOf<DesktopLyricsEntry>()
        val rawLines = raw.lines()
        var idx = 0
        while (idx < rawLines.size) {
            val trimmed = rawLines[idx].trim()
            val match = lrcRegex.find(trimmed)
            if (match != null) {
                val min = match.groupValues[1].toLongOrNull() ?: 0L
                val sec = match.groupValues[2].toLongOrNull() ?: 0L
                val fracStr = match.groupValues[3]
                val msPart = if (fracStr.length == 2) fracStr.toLong() * 10L else fracStr.toLong()
                val timeMs = min * 60_000L + sec * 1_000L + msPart

                var body = match.groupValues[4]
                val isBg = body.startsWith("{bg}")
                if (isBg) body = body.removePrefix("{bg}")
                val agentMatch = agentRegex.find(body)
                val agent = agentMatch?.groupValues?.get(1)
                if (agentMatch != null) body = body.replace(agentRegex, "")
                val cleanText = body.replace(inlineWordTagRegex, "").trim()

                var words = emptyList<DesktopLyricWord>()
                val nextLine = rawLines.getOrNull(idx + 1)?.trim().orEmpty()
                if (nextLine.startsWith("<") && nextLine.endsWith(">") && nextLine.contains(':')) {
                    words = parseWordBlock(nextLine.substring(1, nextLine.length - 1))
                    idx += 1
                }
                synced += DesktopLyricsEntry(
                    timeMs = timeMs,
                    text = cleanText,
                    words = words,
                    agent = agent,
                    isBackground = isBg,
                )
            }
            idx += 1
        }
        if (synced.isNotEmpty()) {
            return synced.sortedBy { it.timeMs } to true
        }
        val plain = raw.lines().mapIndexed { i, line -> DesktopLyricsEntry(i.toLong(), line.trim()) }
        return plain to false
    }

    private fun parseWordBlock(inner: String): List<DesktopLyricWord> =
        inner.split("|").mapNotNull { token ->
            val lastColon = token.lastIndexOf(':')
            if (lastColon <= 0) return@mapNotNull null
            val prevColon = token.lastIndexOf(':', lastColon - 1)
            if (prevColon <= 0) return@mapNotNull null
            val wordText = token.substring(0, prevColon)
            val startSec = token.substring(prevColon + 1, lastColon).toDoubleOrNull() ?: return@mapNotNull null
            val endSec = token.substring(lastColon + 1).toDoubleOrNull() ?: return@mapNotNull null
            DesktopLyricWord(wordText, (startSec * 1000).toLong(), (endSec * 1000).toLong())
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
    private data class BetterLyricsResponse(val ttml: String? = null)

    @Serializable
    private data class LyricsPlusWord(val time: Long = 0, val duration: Long = 0, val text: String = "", val isBackground: Boolean = false)
    @Serializable
    private data class LyricsPlusElement(val singer: String? = null)
    @Serializable
    private data class LyricsPlusLine(val time: Long = 0, val text: String = "", val syllabus: List<LyricsPlusWord>? = null, val element: LyricsPlusElement? = null)
    @Serializable
    private data class LyricsPlusResponse(val type: String? = null, val lyrics: List<LyricsPlusLine>? = null)

    fun close() {
        runCatching { httpClient.close() }
        DesktopLyricsTranslator.close()
    }

    companion object {
        const val LYRICS_NOT_FOUND = "LYRICS_NOT_FOUND"
    }
}
