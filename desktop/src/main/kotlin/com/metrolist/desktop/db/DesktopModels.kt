package com.metrolist.desktop.db

import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem

@kotlinx.serialization.Serializable
data class ArtistRef(
    val id: String,
    val name: String,
)

@kotlinx.serialization.Serializable
data class DesktopSong(
    val id: String,
    val title: String,
    val duration: Int = -1,
    val thumbnailUrl: String? = null,
    val albumId: String? = null,
    val albumName: String? = null,
    val explicit: Boolean = false,
    val year: Int? = null,
    val liked: Boolean = false,
    val likedDate: Long? = null,
    val inLibrary: Long? = null,
    val dateDownload: Long? = null,
    val isDownloaded: Boolean = false,
    val isUploaded: Boolean = false,
    val isVideo: Boolean = false,
    val isEpisode: Boolean = false,
    val totalPlayTime: Long = 0L,
    val lyricsOffset: Int = 0,
    val romanizeLyrics: Boolean = true,
    val playbackPosition: Long? = null,
    val uploadEntityId: String? = null,
    val libraryAddToken: String? = null,
    val libraryRemoveToken: String? = null,
    val artists: List<ArtistRef> = emptyList(),
) {
    val artistDisplay: String
        get() = artists.joinToString(", ") { ArtistAliasStore.resolve(it.id, it.name) }.ifBlank { "Artista desconocido" }
}

data class DesktopArtist(
    val id: String,
    val name: String,
    val thumbnailUrl: String? = null,
    val channelId: String? = null,
    val bookmarkedAt: Long? = null,
    val isPodcastChannel: Boolean = false,
) {
    val displayName: String
        get() = ArtistAliasStore.resolve(id, name)
}

data class DesktopAlbum(
    val id: String,
    val playlistId: String? = null,
    val title: String,
    val year: Int? = null,
    val thumbnailUrl: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val explicit: Boolean = false,
    val bookmarkedAt: Long? = null,
    val artists: List<ArtistRef> = emptyList(),
)

data class DesktopPlaylist(
    val id: String,
    val name: String,
    val browseId: String? = null,
    val thumbnailUrl: String? = null,
    val isEditable: Boolean = true,
    val isLocal: Boolean = true,
    val remoteSongCount: Int? = null,
    val songCount: Int = 0,
)

data class DesktopPodcast(
    val id: String,
    val title: String,
    val author: String? = null,
    val thumbnailUrl: String? = null,
    val channelId: String? = null,
    val bookmarkedAt: Long? = null,
    val libraryAddToken: String? = null,
    val libraryRemoveToken: String? = null,
)

data class DesktopSpeedDialItem(
    val id: String,
    val secondaryId: String? = null,
    val title: String,
    val subtitle: String? = null,
    val thumbnailUrl: String? = null,
    val type: String, // SONG, ALBUM, ARTIST, PLAYLIST, LOCAL_PLAYLIST
    val explicit: Boolean = false,
    val createDate: Long = System.currentTimeMillis(),
    val albumId: String? = null,
    val albumName: String? = null,
)

data class DesktopFormatInfo(
    val id: String,
    val itag: Int = 0,
    val mimeType: String = "",
    val codecs: String = "",
    val bitrate: Int = 0,
    val sampleRate: Int? = null,
    val contentLength: Long = 0L,
    val loudnessDb: Double? = null,
    val perceptualLoudnessDb: Double? = null,
    val playbackUrl: String? = null,
)

data class DesktopRecognitionHistoryItem(
    val id: Long = 0L,
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val coverArtUrl: String? = null,
    val coverArtHqUrl: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val label: String? = null,
    val shazamUrl: String? = null,
    val appleMusicUrl: String? = null,
    val spotifyUrl: String? = null,
    val isrc: String? = null,
    val youtubeVideoId: String? = null,
    val recognizedAt: Long = System.currentTimeMillis(),
    val liked: Boolean = false,
)

data class DesktopLyricWord(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
)

data class DesktopLyricsEntry(
    val timeMs: Long,
    val text: String,
    val words: List<DesktopLyricWord> = emptyList(),
    val agent: String? = null,
    val isBackground: Boolean = false,
    val romanizedText: String? = null,
    val translatedText: String? = null,
)

data class DesktopLyricsRecord(
    val lyrics: String,
    val provider: String,
    val translatedLyrics: String = "",
    val translationLanguage: String = "",
    val translationMode: String = "",
)

data class DesktopSongStat(
    val song: DesktopSong,
    val playCount: Int,
    val playTimeMs: Long,
)

data class DesktopArtistStat(
    val artist: DesktopArtist,
    val playCount: Int,
    val playTimeMs: Long,
)

data class DesktopAlbumStat(
    val album: DesktopAlbum,
    val playCount: Int,
    val playTimeMs: Long,
)

data class ListeningSummary(
    val totalPlayTimeMs: Long = 0L,
    val totalPlays: Int = 0,
    val uniqueSongs: Int = 0,
    val uniqueArtists: Int = 0,
    val uniqueAlbums: Int = 0,
)

data class DesktopHistoryEntry(
    val eventId: Long,
    val timestampMs: Long,
    val playTimeMs: Long,
    val song: DesktopSong,
)

@kotlinx.serialization.Serializable
data class DesktopAlarmEntry(
    val id: String,
    val enabled: Boolean = true,
    val hour: Int = 7,
    val minute: Int = 0,
    val playlistId: String = "TOP",
    val randomSong: Boolean = false,
    val nextTriggerAt: Long = -1L,
    val daysOfWeek: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7),
    val label: String = "",
    val volumeRamp: Boolean = true,
)

fun SongItem.toDesktopSong(): DesktopSong =
    DesktopSong(
        id = id,
        title = title,
        duration = duration ?: -1,
        thumbnailUrl = thumbnail,
        albumId = album?.id,
        albumName = album?.name,
        explicit = explicit,
        isVideo = isVideoSong,
        isEpisode = isEpisode,
        libraryAddToken = libraryAddToken,
        libraryRemoveToken = libraryRemoveToken,
        artists = artists.mapIndexed { index, artist ->
            ArtistRef(
                id = artist.id ?: "artist_${id}_$index",
                name = artist.name,
            )
        },
    )

fun AlbumItem.toDesktopAlbum(): DesktopAlbum =
    DesktopAlbum(
        id = browseId,
        playlistId = playlistId,
        title = title,
        year = year,
        thumbnailUrl = thumbnail,
        explicit = explicit,
        artists = artists.orEmpty().mapIndexed { index, artist ->
            ArtistRef(
                id = artist.id ?: "album_artist_${browseId}_$index",
                name = artist.name,
            )
        },
    )

fun ArtistItem.toDesktopArtist(): DesktopArtist =
    DesktopArtist(
        id = id,
        name = title,
        thumbnailUrl = thumbnail,
        channelId = channelId,
    )

fun PlaylistItem.toDesktopPlaylist(): DesktopPlaylist =
    DesktopPlaylist(
        id = id,
        name = title,
        browseId = id,
        thumbnailUrl = thumbnail,
        isEditable = isEditable,
        isLocal = false,
    )

fun PodcastItem.toDesktopPodcast(): DesktopPodcast =
    DesktopPodcast(
        id = id,
        title = title,
        author = author?.name,
        thumbnailUrl = thumbnail,
        channelId = author?.id,
        libraryAddToken = libraryAddToken,
        libraryRemoveToken = libraryRemoveToken,
    )
