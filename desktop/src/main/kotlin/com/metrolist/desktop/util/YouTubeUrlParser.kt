package com.metrolist.desktop.util

sealed interface ParsedYouTubeLink {
    data class Video(val videoId: String, val playlistId: String? = null) : ParsedYouTubeLink
    data class Playlist(val playlistId: String) : ParsedYouTubeLink
    data class Album(val browseId: String) : ParsedYouTubeLink
    data class Artist(val channelId: String) : ParsedYouTubeLink
    data class Podcast(val podcastId: String) : ParsedYouTubeLink
}

object YouTubeUrlParser {
    private val hostRegex = Regex("""^(https?://)?(www\.|m\.|music\.)?(youtube\.com|youtu\.be)/""", RegexOption.IGNORE_CASE)
    private val videoIdRegex = Regex("""^[A-Za-z0-9_-]{11}$""")

    fun parse(input: String): ParsedYouTubeLink? {
        val trimmed = input.trim()
        if (!hostRegex.containsMatchIn(trimmed)) return null

        val normalized = if (trimmed.startsWith("http", ignoreCase = true)) trimmed else "https://$trimmed"
        val uri = runCatching { java.net.URI(normalized) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path.orEmpty().trimEnd('/')
        val query = parseQuery(uri.rawQuery.orEmpty())

        if (host.endsWith("youtu.be")) {
            val vid = path.removePrefix("/").substringBefore("/")
            if (videoIdRegex.matches(vid)) {
                return ParsedYouTubeLink.Video(vid, query["list"])
            }
            return null
        }

        val listParam = query["list"]?.takeIf { it.isNotBlank() }
        val vParam = query["v"]?.takeIf { videoIdRegex.matches(it) }
        if (vParam != null) {
            return ParsedYouTubeLink.Video(vParam, listParam)
        }

        if (path.startsWith("/shorts/") || path.startsWith("/live/") || path.startsWith("/embed/")) {
            val vid = path.substringAfterLast("/")
            if (videoIdRegex.matches(vid)) return ParsedYouTubeLink.Video(vid, listParam)
        }

        if (path == "/playlist" && listParam != null) {
            return if (listParam.startsWith("MPREb_")) {
                ParsedYouTubeLink.Album(listParam)
            } else {
                ParsedYouTubeLink.Playlist(listParam)
            }
        }

        if (path.startsWith("/browse/")) {
            val browseId = path.removePrefix("/browse/").substringBefore("/")
            return when {
                browseId.startsWith("MPREb_") -> ParsedYouTubeLink.Album(browseId)
                browseId.startsWith("MPSPP") -> ParsedYouTubeLink.Podcast(browseId)
                browseId.startsWith("UC") -> ParsedYouTubeLink.Artist(browseId)
                browseId.startsWith("VL") -> ParsedYouTubeLink.Playlist(browseId.removePrefix("VL"))
                browseId.isNotBlank() -> ParsedYouTubeLink.Playlist(browseId)
                else -> null
            }
        }

        if (path.startsWith("/channel/")) {
            val channelId = path.removePrefix("/channel/").substringBefore("/")
            if (channelId.isNotBlank()) return ParsedYouTubeLink.Artist(channelId)
        }

        if (path == "/podcast" && listParam != null) {
            return ParsedYouTubeLink.Podcast(listParam)
        }

        return null
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        return rawQuery.split("&").mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) null
            else {
                val k = java.net.URLDecoder.decode(pair.substring(0, eq), "UTF-8")
                val v = java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                k to v
            }
        }.toMap()
    }
}
