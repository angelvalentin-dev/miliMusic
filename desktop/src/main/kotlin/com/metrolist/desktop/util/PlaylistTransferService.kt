package com.metrolist.desktop.util

import com.metrolist.desktop.db.DesktopSong
import com.metrolist.desktop.db.toDesktopSong
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class CsvPreview(
    val headers: List<String>,
    val sampleRows: List<List<String>>,
    val totalRows: Int,
    val suggestedTitleCol: Int,
    val suggestedArtistCol: Int,
    val suggestedUrlCol: Int,
)

data class ImportedTrackCandidate(
    val title: String,
    val artist: String = "",
    val videoId: String? = null,
)

object PlaylistTransferService {
    fun exportToCsv(targetFile: File, songs: List<DesktopSong>): Result<File> = runCatching {
        targetFile.parentFile?.mkdirs()
        val csv = buildString {
            appendLine("Title,Artist,Album,YouTube Video ID")
            songs.forEach { song ->
                append('"').append(song.title.replace("\"", "\"\"")).append('"').append(',')
                append('"').append(song.artistDisplay.replace("\"", "\"\"")).append('"').append(',')
                append('"').append(song.albumName.orEmpty().replace("\"", "\"\"")).append('"').append(',')
                appendLine(song.id)
            }
        }
        targetFile.writeText(csv, Charsets.UTF_8)
        targetFile
    }

    fun exportToM3u(targetFile: File, songs: List<DesktopSong>): Result<File> = runCatching {
        targetFile.parentFile?.mkdirs()
        val m3u = buildString {
            appendLine("#EXTM3U")
            songs.forEach { song ->
                appendLine("#EXTINF:${song.duration},${song.artistDisplay} - ${song.title}")
                appendLine("https://music.youtube.com/watch?v=${song.id}")
            }
        }
        targetFile.writeText(m3u, Charsets.UTF_8)
        targetFile
    }

    fun parseM3u(content: String): List<ImportedTrackCandidate> {
        val result = mutableListOf<ImportedTrackCandidate>()
        var pendingTitle: String? = null
        var pendingArtist = ""
        for (rawLine in content.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.equals("#EXTM3U", ignoreCase = true)) continue
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                val info = line.substringAfter(",", "").trim()
                if (info.contains(" - ")) {
                    pendingArtist = info.substringBefore(" - ").trim()
                    pendingTitle = info.substringAfter(" - ").trim()
                } else {
                    pendingArtist = ""
                    pendingTitle = info
                }
            } else if (line.startsWith("#YTM:", ignoreCase = true)) {
                val id = line.removePrefix("#YTM:").trim()
                result += ImportedTrackCandidate(
                    title = pendingTitle ?: id,
                    artist = pendingArtist,
                    videoId = id.takeIf { it.length == 11 },
                )
                pendingTitle = null
                pendingArtist = ""
            } else if (!line.startsWith("#")) {
                val parsed = YouTubeUrlParser.parse(line)
                val vid = (parsed as? ParsedYouTubeLink.Video)?.videoId
                val fallbackTitle = pendingTitle ?: File(line).nameWithoutExtension
                if (vid != null || fallbackTitle.isNotBlank()) {
                    result += ImportedTrackCandidate(
                        title = fallbackTitle,
                        artist = pendingArtist,
                        videoId = vid,
                    )
                }
                pendingTitle = null
                pendingArtist = ""
            }
        }
        return result
    }

    fun previewCsv(file: File): Result<CsvPreview> = runCatching {
        val rows = parseCsvRows(file.readText(Charsets.UTF_8))
        require(rows.isNotEmpty()) { "El archivo CSV está vacío" }
        val headers = rows.first()
        val lowerHeaders = headers.map { it.lowercase().trim() }
        val titleCol = lowerHeaders.indexOfFirst { it.contains("title") || it.contains("track") || it.contains("song") || it.contains("canc") || it.contains("nombre") }
            .takeIf { it >= 0 } ?: 0
        val artistCol = lowerHeaders.indexOfFirst { it.contains("artist") || it.contains("autor") || it.contains("band") }
            .takeIf { it >= 0 } ?: if (headers.size > 1) 1 else -1
        val urlCol = lowerHeaders.indexOfFirst { it.contains("id") || it.contains("url") || it.contains("link") || it.contains("youtube") }
            .takeIf { it >= 0 } ?: -1
        CsvPreview(
            headers = headers,
            sampleRows = rows.drop(1).take(5),
            totalRows = (rows.size - 1).coerceAtLeast(0),
            suggestedTitleCol = titleCol,
            suggestedArtistCol = artistCol,
            suggestedUrlCol = urlCol,
        )
    }

    fun extractCsvCandidates(
        content: String,
        titleCol: Int,
        artistCol: Int,
        urlCol: Int,
        hasHeader: Boolean,
    ): List<ImportedTrackCandidate> {
        val rows = parseCsvRows(content)
        val dataRows = if (hasHeader && rows.isNotEmpty()) rows.drop(1) else rows
        return dataRows.mapNotNull { row ->
            val title = row.getOrNull(titleCol)?.trim().orEmpty()
            val artist = if (artistCol >= 0) row.getOrNull(artistCol)?.trim().orEmpty() else ""
            val rawIdOrUrl = if (urlCol >= 0) row.getOrNull(urlCol)?.trim().orEmpty() else ""
            val vid = when {
                rawIdOrUrl.matches(Regex("""^[A-Za-z0-9_-]{11}$""")) -> rawIdOrUrl
                rawIdOrUrl.isNotBlank() -> (YouTubeUrlParser.parse(rawIdOrUrl) as? ParsedYouTubeLink.Video)?.videoId
                else -> null
            }
            if (title.isBlank() && vid == null) null
            else ImportedTrackCandidate(title = title.ifBlank { vid.orEmpty() }, artist = artist, videoId = vid)
        }
    }

    suspend fun resolveCandidatesOnline(
        candidates: List<ImportedTrackCandidate>,
        onProgress: (index: Int, total: Int, message: String) -> Unit = { _, _, _ -> },
    ): List<DesktopSong> = withContext(Dispatchers.IO) {
        val resolved = mutableListOf<DesktopSong>()
        val total = candidates.size
        candidates.forEachIndexed { idx, candidate ->
            onProgress(idx + 1, total, "Resolviendo ${idx + 1}/$total: ${candidate.title}")
            val directId = candidate.videoId
            if (!directId.isNullOrBlank()) {
                val next = runCatching {
                    YouTube.queue(videoIds = listOf(directId)).getOrNull()?.firstOrNull()?.toDesktopSong()
                }.getOrNull()
                if (next != null) {
                    resolved += next
                    return@forEachIndexed
                }
            }
            val query = listOf(candidate.title, candidate.artist).filter { it.isNotBlank() }.joinToString(" ")
            if (query.isNotBlank()) {
                val found = runCatching {
                    YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                        ?.items?.filterIsInstance<SongItem>()?.firstOrNull()?.toDesktopSong()
                }.getOrNull()
                if (found != null) {
                    resolved += found
                }
            }
        }
        resolved
    }

    fun parseCsvRows(content: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val currentField = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < content.length) {
            val c = content[i]
            when {
                inQuotes -> {
                    if (c == '"') {
                        if (i + 1 < content.length && content[i + 1] == '"') {
                            currentField.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        currentField.append(c)
                    }
                }
                c == '"' -> inQuotes = true
                c == ',' -> {
                    currentRow += currentField.toString()
                    currentField.clear()
                }
                c == '\r' -> {}
                c == '\n' -> {
                    currentRow += currentField.toString()
                    currentField.clear()
                    if (currentRow.any { it.isNotBlank() }) rows += currentRow.toList()
                    currentRow.clear()
                }
                else -> currentField.append(c)
            }
            i++
        }
        if (currentField.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow += currentField.toString()
            if (currentRow.any { it.isNotBlank() }) rows += currentRow.toList()
        }
        return rows
    }
}
