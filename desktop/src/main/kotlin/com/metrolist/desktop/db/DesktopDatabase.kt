package com.metrolist.desktop.db

import com.metrolist.desktop.AppPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID

class DesktopDatabase(
    private val dbFile: File = AppPaths.dbFile,
) {
    private val dbLock = Any()
    private val changeTrigger = MutableStateFlow(0L)

    init {
        Class.forName("org.sqlite.JDBC")
        ensureSchema()
    }

    private fun openConnection(): Connection {
        dbFile.parentFile?.mkdirs()
        return DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").apply {
            createStatement().use { st ->
                st.execute("PRAGMA busy_timeout = 60000")
                st.execute("PRAGMA foreign_keys = ON")
                st.execute("PRAGMA synchronous = NORMAL")
            }
        }
    }

    private fun ensureSchema() = synchronized(dbLock) {
        openConnection().use { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA journal_mode = WAL")
            }
            val version = conn.createStatement().use { st ->
                st.executeQuery("PRAGMA user_version").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
            if (version == 0) {
                val sql = javaClass.classLoader.getResourceAsStream("db/schema_v38.sql")
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: error("Missing db/schema_v38.sql resource")
                conn.createStatement().use { st ->
                    sql.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .forEach { stmt -> st.execute(stmt.removeSuffix(";")) }
                }
            }
        }
    }

    private fun notifyChanged() {
        changeTrigger.value += 1L
    }

    fun <T> observe(loader: () -> T): Flow<T> = changeTrigger.map { loader() }

    suspend fun checkpoint() = withContext(Dispatchers.IO) {
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
            }
        }
    }

    fun reloadFromDisk() {
        ensureSchema()
        notifyChanged()
    }

    fun getSong(songId: String): DesktopSong? = synchronized(dbLock) {
        openConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM song WHERE id = ? LIMIT 1").use { ps ->
                ps.setString(1, songId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return@synchronized null
                    readSong(conn, rs)
                }
            }
        }
    }

    fun upsertSong(song: DesktopSong) = synchronized(dbLock) {
        openConnection().use { conn ->
            conn.autoCommit = false
            try {
                upsertSongInternal(conn, song)
                conn.commit()
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            }
        }
        notifyChanged()
    }

    private fun upsertSongInternal(conn: Connection, song: DesktopSong) {
        val existing = conn.prepareStatement("SELECT liked, likedDate, inLibrary, dateDownload, isDownloaded, totalPlayTime FROM song WHERE id = ?").use { ps ->
            ps.setString(1, song.id)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    ExistingSongFlags(
                        liked = rs.getInt("liked") != 0,
                        likedDate = rs.getObject("likedDate")?.let { (it as Number).toLong() },
                        inLibrary = rs.getObject("inLibrary")?.let { (it as Number).toLong() },
                        dateDownload = rs.getObject("dateDownload")?.let { (it as Number).toLong() },
                        isDownloaded = rs.getInt("isDownloaded") != 0,
                        totalPlayTime = rs.getLong("totalPlayTime"),
                    )
                } else null
            }
        }

        val now = System.currentTimeMillis()
        conn.prepareStatement(
            """
            INSERT INTO song (
                id, title, duration, thumbnailUrl, albumId, albumName, explicit,
                dateModified, liked, likedDate, totalPlayTime, inLibrary, dateDownload,
                isLocal, libraryAddToken, libraryRemoveToken, isDownloaded, isVideo, isEpisode
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                title = excluded.title,
                duration = CASE WHEN excluded.duration > 0 THEN excluded.duration ELSE song.duration END,
                thumbnailUrl = COALESCE(excluded.thumbnailUrl, song.thumbnailUrl),
                albumId = COALESCE(excluded.albumId, song.albumId),
                albumName = COALESCE(excluded.albumName, song.albumName),
                explicit = excluded.explicit,
                dateModified = excluded.dateModified,
                libraryAddToken = COALESCE(excluded.libraryAddToken, song.libraryAddToken),
                libraryRemoveToken = COALESCE(excluded.libraryRemoveToken, song.libraryRemoveToken),
                isVideo = excluded.isVideo,
                isEpisode = excluded.isEpisode
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, song.id)
            ps.setString(2, song.title)
            ps.setInt(3, song.duration)
            ps.setString(4, song.thumbnailUrl)
            ps.setString(5, song.albumId)
            ps.setString(6, song.albumName)
            ps.setInt(7, if (song.explicit) 1 else 0)
            ps.setLong(8, now)
            ps.setInt(9, if (existing?.liked ?: song.liked) 1 else 0)
            val likedDate = existing?.likedDate ?: song.likedDate
            if (likedDate != null) ps.setLong(10, likedDate) else ps.setNull(10, java.sql.Types.INTEGER)
            ps.setLong(11, existing?.totalPlayTime ?: song.totalPlayTime)
            val inLib = existing?.inLibrary ?: song.inLibrary
            if (inLib != null) ps.setLong(12, inLib) else ps.setNull(12, java.sql.Types.INTEGER)
            val dateDl = existing?.dateDownload ?: song.dateDownload
            if (dateDl != null) ps.setLong(13, dateDl) else ps.setNull(13, java.sql.Types.INTEGER)
            ps.setString(14, song.libraryAddToken)
            ps.setString(15, song.libraryRemoveToken)
            ps.setInt(16, if (existing?.isDownloaded ?: song.isDownloaded) 1 else 0)
            ps.setInt(17, if (song.isVideo) 1 else 0)
            ps.setInt(18, if (song.isEpisode) 1 else 0)
            ps.executeUpdate()
        }

        if (song.artists.isNotEmpty()) {
            conn.prepareStatement("DELETE FROM song_artist_map WHERE songId = ?").use { ps ->
                ps.setString(1, song.id)
                ps.executeUpdate()
            }
            song.artists.forEachIndexed { index, artist ->
                conn.prepareStatement(
                    "INSERT OR IGNORE INTO artist (id, name, lastUpdateTime) VALUES (?, ?, ?)",
                ).use { ps ->
                    ps.setString(1, artist.id)
                    ps.setString(2, artist.name)
                    ps.setLong(3, now)
                    ps.executeUpdate()
                }
                conn.prepareStatement(
                    "INSERT OR REPLACE INTO song_artist_map (songId, artistId, position) VALUES (?, ?, ?)",
                ).use { ps ->
                    ps.setString(1, song.id)
                    ps.setString(2, artist.id)
                    ps.setInt(3, index)
                    ps.executeUpdate()
                }
            }
        }

        if (!song.albumId.isNullOrBlank() && !song.albumName.isNullOrBlank()) {
            conn.prepareStatement(
                """
                INSERT INTO album (id, title, thumbnailUrl, songCount, duration, explicit, lastUpdateTime)
                VALUES (?, ?, ?, 1, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    title = COALESCE(album.title, excluded.title),
                    thumbnailUrl = COALESCE(album.thumbnailUrl, excluded.thumbnailUrl)
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, song.albumId)
                ps.setString(2, song.albumName)
                ps.setString(3, song.thumbnailUrl)
                ps.setInt(4, song.duration.coerceAtLeast(0))
                ps.setInt(5, if (song.explicit) 1 else 0)
                ps.setLong(6, now)
                ps.executeUpdate()
            }
        }
    }

    fun toggleLike(song: DesktopSong): DesktopSong {
        val now = System.currentTimeMillis()
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.autoCommit = false
                try {
                    upsertSongInternal(conn, song)
                    val currentlyLiked = conn.prepareStatement("SELECT liked FROM song WHERE id = ?").use { ps ->
                        ps.setString(1, song.id)
                        ps.executeQuery().use { rs -> rs.next() && rs.getInt(1) != 0 }
                    }
                    val nextLiked = !currentlyLiked
                    conn.prepareStatement(
                        "UPDATE song SET liked = ?, likedDate = ?, inLibrary = COALESCE(inLibrary, ?) WHERE id = ?",
                    ).use { ps ->
                        ps.setInt(1, if (nextLiked) 1 else 0)
                        if (nextLiked) ps.setLong(2, now) else ps.setNull(2, java.sql.Types.INTEGER)
                        if (nextLiked) ps.setLong(3, now) else ps.setNull(3, java.sql.Types.INTEGER)
                        ps.setString(4, song.id)
                        ps.executeUpdate()
                    }
                    conn.commit()
                } catch (t: Throwable) {
                    conn.rollback()
                    throw t
                }
            }
        }
        notifyChanged()
        return getSong(song.id) ?: song
    }

    fun toggleInLibrary(song: DesktopSong): DesktopSong {
        val now = System.currentTimeMillis()
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.autoCommit = false
                try {
                    upsertSongInternal(conn, song)
                    val currentlyInLib = conn.prepareStatement("SELECT inLibrary FROM song WHERE id = ?").use { ps ->
                        ps.setString(1, song.id)
                        ps.executeQuery().use { rs -> rs.next() && rs.getObject(1) != null }
                    }
                    conn.prepareStatement("UPDATE song SET inLibrary = ? WHERE id = ?").use { ps ->
                        if (currentlyInLib) ps.setNull(1, java.sql.Types.INTEGER) else ps.setLong(1, now)
                        ps.setString(2, song.id)
                        ps.executeUpdate()
                    }
                    conn.commit()
                } catch (t: Throwable) {
                    conn.rollback()
                    throw t
                }
            }
        }
        notifyChanged()
        return getSong(song.id) ?: song
    }

    fun setDownloaded(song: DesktopSong, downloaded: Boolean) {
        val now = System.currentTimeMillis()
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.autoCommit = false
                try {
                    upsertSongInternal(conn, song)
                    conn.prepareStatement(
                        "UPDATE song SET isDownloaded = ?, dateDownload = ? WHERE id = ?",
                    ).use { ps ->
                        ps.setInt(1, if (downloaded) 1 else 0)
                        if (downloaded) ps.setLong(2, now) else ps.setNull(2, java.sql.Types.INTEGER)
                        ps.setString(3, song.id)
                        ps.executeUpdate()
                    }
                    conn.commit()
                } catch (t: Throwable) {
                    conn.rollback()
                    throw t
                }
            }
        }
        notifyChanged()
    }

    fun setCached(songId: String, cached: Boolean) {
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.prepareStatement("UPDATE song SET isCached = ? WHERE id = ?").use { ps ->
                    ps.setInt(1, if (cached) 1 else 0)
                    ps.setString(2, songId)
                    ps.executeUpdate()
                }
            }
        }
        notifyChanged()
    }

    fun recordPlay(song: DesktopSong, playedMs: Long, timestampMs: Long = System.currentTimeMillis()) {
        val safePlayMs = playedMs.coerceAtLeast(1000L)
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestampMs }
        val year = cal.get(java.util.Calendar.YEAR)
        val month = cal.get(java.util.Calendar.MONTH) + 1
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.autoCommit = false
                try {
                    upsertSongInternal(conn, song)
                    conn.prepareStatement(
                        "INSERT INTO event (songId, timestamp, playTime) VALUES (?, ?, ?)",
                    ).use { ps ->
                        ps.setString(1, song.id)
                        ps.setLong(2, timestampMs)
                        ps.setLong(3, safePlayMs)
                        ps.executeUpdate()
                    }
                    conn.prepareStatement(
                        "UPDATE song SET totalPlayTime = totalPlayTime + ? WHERE id = ?",
                    ).use { ps ->
                        ps.setLong(1, safePlayMs)
                        ps.setString(2, song.id)
                        ps.executeUpdate()
                    }
                    conn.prepareStatement(
                        """
                        INSERT INTO playCount (song, year, month, count) VALUES (?, ?, ?, 1)
                        ON CONFLICT(song, year, month) DO UPDATE SET count = playCount.count + 1
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setString(1, song.id)
                        ps.setInt(2, year)
                        ps.setInt(3, month)
                        ps.executeUpdate()
                    }
                    conn.commit()
                } catch (t: Throwable) {
                    conn.rollback()
                    throw t
                }
            }
        }
        notifyChanged()
    }

    fun likedSongs(): List<DesktopSong> = querySongs("WHERE liked = 1 ORDER BY COALESCE(likedDate, 0) DESC, title ASC")
    fun librarySongs(): List<DesktopSong> = querySongs("WHERE inLibrary IS NOT NULL ORDER BY inLibrary DESC, title ASC")
    fun downloadedSongs(): List<DesktopSong> = querySongs("WHERE isDownloaded = 1 ORDER BY COALESCE(dateDownload, 0) DESC, title ASC")
    fun topSongs(limit: Int = 50): List<DesktopSong> =
        mostPlayedSongs(sinceMs = 0L, limit = limit).map { it.song }

    fun cachedSongs(cacheDir: File = AppPaths.songCacheDir): List<DesktopSong> = synchronized(dbLock) {
        val fileIds = cacheDir.listFiles()
            ?.filter { it.isFile && it.extension == "cache" && it.length() > 0L }
            ?.map { it.nameWithoutExtension }
            .orEmpty()
            .toSet()
        openConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM song ORDER BY COALESCE(dateModified, 0) DESC").use { ps ->
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val id = rs.getString("id")
                            val isCachedCol = rs.getInt("isCached") != 0
                            if (isCachedCol || id in fileIds) {
                                add(readSong(conn, rs))
                            }
                        }
                    }
                }
            }
        }
    }

    fun recentlyPlayed(limit: Int = 50): List<DesktopSong> = synchronized(dbLock) {
        openConnection().use { conn ->
            val sql = """
                SELECT s.* FROM song s
                JOIN (
                    SELECT songId, MAX(timestamp) AS lastPlayed FROM event GROUP BY songId
                ) e ON e.songId = s.id
                ORDER BY e.lastPlayed DESC
                LIMIT ?
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, limit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(readSong(conn, rs))
                    }
                }
            }
        }
    }

    fun mostPlayedSongs(
        sinceMs: Long = 0L,
        untilMs: Long = Long.MAX_VALUE,
        limit: Int = 50,
    ): List<DesktopSongStat> = synchronized(dbLock) {
        openConnection().use { conn ->
            val sql = """
                SELECT s.*, COUNT(e.id) AS playCount, COALESCE(SUM(e.playTime), 0) AS sumPlayTime
                FROM event e
                JOIN song s ON s.id = e.songId
                WHERE e.timestamp >= ? AND e.timestamp <= ?
                GROUP BY s.id
                ORDER BY sumPlayTime DESC, playCount DESC
                LIMIT ?
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, sinceMs)
                ps.setLong(2, untilMs)
                ps.setInt(3, limit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                DesktopSongStat(
                                    song = readSong(conn, rs),
                                    playCount = rs.getInt("playCount"),
                                    playTimeMs = rs.getLong("sumPlayTime"),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    fun mostPlayedArtists(
        sinceMs: Long = 0L,
        untilMs: Long = Long.MAX_VALUE,
        limit: Int = 30,
    ): List<DesktopArtistStat> = synchronized(dbLock) {
        openConnection().use { conn ->
            val sql = """
                SELECT a.id, a.name, a.thumbnailUrl, a.channelId, a.bookmarkedAt,
                       COUNT(e.id) AS playCount, COALESCE(SUM(e.playTime), 0) AS sumPlayTime,
                       MAX(s.thumbnailUrl) AS fallbackThumb
                FROM event e
                JOIN song_artist_map m ON m.songId = e.songId
                JOIN artist a ON a.id = m.artistId
                LEFT JOIN song s ON s.id = e.songId
                WHERE e.timestamp >= ? AND e.timestamp <= ?
                GROUP BY a.id
                ORDER BY sumPlayTime DESC, playCount DESC
                LIMIT ?
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, sinceMs)
                ps.setLong(2, untilMs)
                ps.setInt(3, limit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                DesktopArtistStat(
                                    artist = DesktopArtist(
                                        id = rs.getString("id"),
                                        name = rs.getString("name"),
                                        thumbnailUrl = rs.getString("thumbnailUrl") ?: rs.getString("fallbackThumb"),
                                        channelId = rs.getString("channelId"),
                                        bookmarkedAt = rs.getObject("bookmarkedAt")?.let { (it as Number).toLong() },
                                    ),
                                    playCount = rs.getInt("playCount"),
                                    playTimeMs = rs.getLong("sumPlayTime"),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    fun mostPlayedAlbums(
        sinceMs: Long = 0L,
        untilMs: Long = Long.MAX_VALUE,
        limit: Int = 30,
    ): List<DesktopAlbumStat> = synchronized(dbLock) {
        openConnection().use { conn ->
            val sql = """
                SELECT s.albumId AS aid,
                       COALESCE(al.title, s.albumName) AS atitle,
                       COALESCE(al.thumbnailUrl, MAX(s.thumbnailUrl)) AS athumb,
                       al.year AS ayear,
                       COUNT(e.id) AS playCount,
                       COALESCE(SUM(e.playTime), 0) AS sumPlayTime
                FROM event e
                JOIN song s ON s.id = e.songId
                LEFT JOIN album al ON al.id = s.albumId
                WHERE e.timestamp >= ? AND e.timestamp <= ?
                  AND s.albumId IS NOT NULL AND s.albumId != ''
                GROUP BY s.albumId
                ORDER BY sumPlayTime DESC, playCount DESC
                LIMIT ?
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, sinceMs)
                ps.setLong(2, untilMs)
                ps.setInt(3, limit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val albumId = rs.getString("aid") ?: continue
                            val title = rs.getString("atitle") ?: "Álbum"
                            add(
                                DesktopAlbumStat(
                                    album = DesktopAlbum(
                                        id = albumId,
                                        title = title,
                                        year = rs.getObject("ayear")?.let { (it as Number).toInt() },
                                        thumbnailUrl = rs.getString("athumb"),
                                    ),
                                    playCount = rs.getInt("playCount"),
                                    playTimeMs = rs.getLong("sumPlayTime"),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    fun listeningSummary(
        sinceMs: Long = 0L,
        untilMs: Long = Long.MAX_VALUE,
    ): ListeningSummary = synchronized(dbLock) {
        openConnection().use { conn ->
            val sql = """
                SELECT COALESCE(SUM(e.playTime), 0) AS totalTime,
                       COUNT(e.id) AS totalPlays,
                       COUNT(DISTINCT e.songId) AS uniqueSongs,
                       (SELECT COUNT(DISTINCT m.artistId) FROM event e2 JOIN song_artist_map m ON m.songId = e2.songId WHERE e2.timestamp >= ? AND e2.timestamp <= ?) AS uniqueArtists,
                       (SELECT COUNT(DISTINCT s2.albumId) FROM event e3 JOIN song s2 ON s2.id = e3.songId WHERE e3.timestamp >= ? AND e3.timestamp <= ? AND s2.albumId IS NOT NULL AND s2.albumId != '') AS uniqueAlbums
                FROM event e
                WHERE e.timestamp >= ? AND e.timestamp <= ?
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, sinceMs)
                ps.setLong(2, untilMs)
                ps.setLong(3, sinceMs)
                ps.setLong(4, untilMs)
                ps.setLong(5, sinceMs)
                ps.setLong(6, untilMs)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        ListeningSummary(
                            totalPlayTimeMs = rs.getLong("totalTime"),
                            totalPlays = rs.getInt("totalPlays"),
                            uniqueSongs = rs.getInt("uniqueSongs"),
                            uniqueArtists = rs.getInt("uniqueArtists"),
                            uniqueAlbums = rs.getInt("uniqueAlbums"),
                        )
                    } else {
                        ListeningSummary()
                    }
                }
            }
        }
    }

    private fun querySongs(whereAndOrder: String): List<DesktopSong> = synchronized(dbLock) {
        openConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM song $whereAndOrder").use { ps ->
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(readSong(conn, rs))
                    }
                }
            }
        }
    }

    private fun readSong(conn: Connection, rs: ResultSet): DesktopSong {
        val songId = rs.getString("id")
        val artists = conn.prepareStatement(
            """
            SELECT a.id, a.name FROM artist a
            JOIN song_artist_map m ON m.artistId = a.id
            WHERE m.songId = ?
            ORDER BY m.position ASC
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, songId)
            ps.executeQuery().use { ars ->
                buildList {
                    while (ars.next()) add(ArtistRef(ars.getString("id"), ars.getString("name")))
                }
            }
        }
        return DesktopSong(
            id = songId,
            title = rs.getString("title"),
            duration = rs.getInt("duration"),
            thumbnailUrl = rs.getString("thumbnailUrl"),
            albumId = rs.getString("albumId"),
            albumName = rs.getString("albumName"),
            explicit = rs.getInt("explicit") != 0,
            year = rs.getObject("year")?.let { (it as Number).toInt() },
            liked = rs.getInt("liked") != 0,
            likedDate = rs.getObject("likedDate")?.let { (it as Number).toLong() },
            inLibrary = rs.getObject("inLibrary")?.let { (it as Number).toLong() },
            dateDownload = rs.getObject("dateDownload")?.let { (it as Number).toLong() },
            isDownloaded = rs.getInt("isDownloaded") != 0,
            isUploaded = runCatching { rs.getInt("isUploaded") != 0 }.getOrDefault(false),
            isVideo = rs.getInt("isVideo") != 0,
            isEpisode = rs.getInt("isEpisode") != 0,
            totalPlayTime = rs.getLong("totalPlayTime"),
            lyricsOffset = runCatching { rs.getInt("lyricsOffset") }.getOrDefault(0),
            romanizeLyrics = runCatching { rs.getInt("romanizeLyrics") != 0 }.getOrDefault(true),
            playbackPosition = runCatching { rs.getObject("playbackPosition")?.let { (it as Number).toLong() } }.getOrNull(),
            uploadEntityId = runCatching { rs.getString("uploadEntityId") }.getOrNull(),
            libraryAddToken = rs.getString("libraryAddToken"),
            libraryRemoveToken = rs.getString("libraryRemoveToken"),
            artists = artists,
        )
    }

    // Artists & Albums
    fun toggleArtistBookmark(artist: DesktopArtist): Boolean {
        val now = System.currentTimeMillis()
        var bookmarked = false
        synchronized(dbLock) {
            openConnection().use { conn ->
                val existing = conn.prepareStatement("SELECT bookmarkedAt FROM artist WHERE id = ?").use { ps ->
                    ps.setString(1, artist.id)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rs.getObject(1)?.let { (it as Number).toLong() } else null
                    }
                }
                val newMark = if (existing == null) now else null
                bookmarked = newMark != null
                conn.prepareStatement(
                    """
                    INSERT INTO artist (id, name, thumbnailUrl, channelId, lastUpdateTime, bookmarkedAt)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        name = excluded.name,
                        thumbnailUrl = COALESCE(excluded.thumbnailUrl, artist.thumbnailUrl),
                        channelId = COALESCE(excluded.channelId, artist.channelId),
                        lastUpdateTime = excluded.lastUpdateTime,
                        bookmarkedAt = ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, artist.id)
                    ps.setString(2, artist.name)
                    ps.setString(3, artist.thumbnailUrl)
                    ps.setString(4, artist.channelId)
                    ps.setLong(5, now)
                    if (newMark != null) ps.setLong(6, newMark) else ps.setNull(6, java.sql.Types.INTEGER)
                    if (newMark != null) ps.setLong(7, newMark) else ps.setNull(7, java.sql.Types.INTEGER)
                    ps.executeUpdate()
                }
            }
        }
        notifyChanged()
        return bookmarked
    }

    fun isArtistBookmarked(artistId: String): Boolean = synchronized(dbLock) {
        openConnection().use { conn ->
            conn.prepareStatement("SELECT bookmarkedAt FROM artist WHERE id = ?").use { ps ->
                ps.setString(1, artistId)
                ps.executeQuery().use { rs -> rs.next() && rs.getObject(1) != null }
            }
        }
    }

    fun bookmarkedArtists(): List<DesktopArtist> = synchronized(dbLock) {
        openConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM artist WHERE bookmarkedAt IS NOT NULL ORDER BY name ASC").use { ps ->
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                DesktopArtist(
                                    id = rs.getString("id"),
                                    name = rs.getString("name"),
                                    thumbnailUrl = rs.getString("thumbnailUrl"),
                                    channelId = rs.getString("channelId"),
                                    bookmarkedAt = rs.getObject("bookmarkedAt")?.let { (it as Number).toLong() },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    fun toggleAlbumBookmark(album: DesktopAlbum): Boolean {
        val now = System.currentTimeMillis()
        var bookmarked = false
        synchronized(dbLock) {
            openConnection().use { conn ->
                val existing = conn.prepareStatement("SELECT bookmarkedAt FROM album WHERE id = ?").use { ps ->
                    ps.setString(1, album.id)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rs.getObject(1)?.let { (it as Number).toLong() } else null
                    }
                }
                val newMark = if (existing == null) now else null
                bookmarked = newMark != null
                conn.prepareStatement(
                    """
                    INSERT INTO album (id, playlistId, title, year, thumbnailUrl, songCount, duration, explicit, lastUpdateTime, bookmarkedAt)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        title = excluded.title,
                        thumbnailUrl = COALESCE(excluded.thumbnailUrl, album.thumbnailUrl),
                        lastUpdateTime = excluded.lastUpdateTime,
                        bookmarkedAt = ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, album.id)
                    ps.setString(2, album.playlistId)
                    ps.setString(3, album.title)
                    if (album.year != null) ps.setInt(4, album.year) else ps.setNull(4, java.sql.Types.INTEGER)
                    ps.setString(5, album.thumbnailUrl)
                    ps.setInt(6, album.songCount)
                    ps.setInt(7, album.duration)
                    ps.setInt(8, if (album.explicit) 1 else 0)
                    ps.setLong(9, now)
                    if (newMark != null) ps.setLong(10, newMark) else ps.setNull(10, java.sql.Types.INTEGER)
                    if (newMark != null) ps.setLong(11, newMark) else ps.setNull(11, java.sql.Types.INTEGER)
                    ps.executeUpdate()
                }
            }
        }
        notifyChanged()
        return bookmarked
    }

    fun isAlbumBookmarked(albumId: String): Boolean = synchronized(dbLock) {
        openConnection().use { conn ->
            conn.prepareStatement("SELECT bookmarkedAt FROM album WHERE id = ?").use { ps ->
                ps.setString(1, albumId)
                ps.executeQuery().use { rs -> rs.next() && rs.getObject(1) != null }
            }
        }
    }

    fun bookmarkedAlbums(): List<DesktopAlbum> = synchronized(dbLock) {
        openConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM album WHERE bookmarkedAt IS NOT NULL ORDER BY title ASC").use { ps ->
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                DesktopAlbum(
                                    id = rs.getString("id"),
                                    playlistId = rs.getString("playlistId"),
                                    title = rs.getString("title"),
                                    year = rs.getObject("year")?.let { (it as Number).toInt() },
                                    thumbnailUrl = rs.getString("thumbnailUrl"),
                                    songCount = rs.getInt("songCount"),
                                    duration = rs.getInt("duration"),
                                    explicit = rs.getInt("explicit") != 0,
                                    bookmarkedAt = rs.getObject("bookmarkedAt")?.let { (it as Number).toLong() },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    // Playlists
    fun createPlaylist(name: String): DesktopPlaylist {
        val id = "LP${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val now = System.currentTimeMillis()
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO playlist (id, name, createdAt, lastUpdateTime, isEditable, isLocal)
                    VALUES (?, ?, ?, ?, 1, 1)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, id)
                    ps.setString(2, name.trim())
                    ps.setLong(3, now)
                    ps.setLong(4, now)
                    ps.executeUpdate()
                }
            }
        }
        notifyChanged()
        return DesktopPlaylist(id = id, name = name.trim(), isEditable = true, isLocal = true, songCount = 0)
    }

    fun upsertRemotePlaylist(playlist: DesktopPlaylist) {
        val now = System.currentTimeMillis()
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO playlist (id, name, browseId, thumbnailUrl, lastUpdateTime, isEditable, isLocal, remoteSongCount)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        name = excluded.name,
                        browseId = COALESCE(excluded.browseId, playlist.browseId),
                        thumbnailUrl = COALESCE(excluded.thumbnailUrl, playlist.thumbnailUrl),
                        lastUpdateTime = excluded.lastUpdateTime,
                        isEditable = excluded.isEditable,
                        remoteSongCount = COALESCE(excluded.remoteSongCount, playlist.remoteSongCount)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, playlist.id)
                    ps.setString(2, playlist.name)
                    ps.setString(3, playlist.browseId)
                    ps.setString(4, playlist.thumbnailUrl)
                    ps.setLong(5, now)
                    ps.setInt(6, if (playlist.isEditable) 1 else 0)
                    ps.setInt(7, if (playlist.isLocal) 1 else 0)
                    if (playlist.remoteSongCount != null) ps.setInt(8, playlist.remoteSongCount) else ps.setNull(8, java.sql.Types.INTEGER)
                    ps.executeUpdate()
                }
            }
        }
        notifyChanged()
    }

    fun renamePlaylist(playlistId: String, newName: String) {
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.prepareStatement("UPDATE playlist SET name = ?, lastUpdateTime = ? WHERE id = ?").use { ps ->
                    ps.setString(1, newName.trim())
                    ps.setLong(2, System.currentTimeMillis())
                    ps.setString(3, playlistId)
                    ps.executeUpdate()
                }
            }
        }
        notifyChanged()
    }

    fun deletePlaylist(playlistId: String) {
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.prepareStatement("DELETE FROM playlist WHERE id = ?").use { ps ->
                    ps.setString(1, playlistId)
                    ps.executeUpdate()
                }
            }
        }
        notifyChanged()
    }

    fun addSongToPlaylist(playlistId: String, song: DesktopSong) {
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.autoCommit = false
                try {
                    upsertSongInternal(conn, song)
                    val nextPos = conn.prepareStatement("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_song_map WHERE playlistId = ?").use { ps ->
                        ps.setString(1, playlistId)
                        ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                    }
                    conn.prepareStatement(
                        "INSERT INTO playlist_song_map (playlistId, songId, position) VALUES (?, ?, ?)",
                    ).use { ps ->
                        ps.setString(1, playlistId)
                        ps.setString(2, song.id)
                        ps.setInt(3, nextPos)
                        ps.executeUpdate()
                    }
                    conn.prepareStatement(
                        "UPDATE playlist SET thumbnailUrl = COALESCE(thumbnailUrl, ?), lastUpdateTime = ? WHERE id = ?",
                    ).use { ps ->
                        ps.setString(1, song.thumbnailUrl)
                        ps.setLong(2, System.currentTimeMillis())
                        ps.setString(3, playlistId)
                        ps.executeUpdate()
                    }
                    conn.commit()
                } catch (t: Throwable) {
                    conn.rollback()
                    throw t
                }
            }
        }
        notifyChanged()
    }

    fun removeSongFromPlaylist(playlistId: String, songId: String) {
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.prepareStatement(
                    "DELETE FROM playlist_song_map WHERE id IN (SELECT id FROM playlist_song_map WHERE playlistId = ? AND songId = ? LIMIT 1)",
                ).use { ps ->
                    ps.setString(1, playlistId)
                    ps.setString(2, songId)
                    ps.executeUpdate()
                }
            }
        }
        notifyChanged()
    }

    fun playlists(): List<DesktopPlaylist> = synchronized(dbLock) {
        openConnection().use { conn ->
            val sql = """
                SELECT p.*, (SELECT COUNT(*) FROM playlist_song_map m WHERE m.playlistId = p.id) AS songCount
                FROM playlist p
                ORDER BY COALESCE(p.lastUpdateTime, p.createdAt, 0) DESC, p.name ASC
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                DesktopPlaylist(
                                    id = rs.getString("id"),
                                    name = rs.getString("name"),
                                    browseId = rs.getString("browseId"),
                                    thumbnailUrl = rs.getString("thumbnailUrl"),
                                    isEditable = rs.getInt("isEditable") != 0,
                                    isLocal = rs.getInt("isLocal") != 0,
                                    remoteSongCount = rs.getObject("remoteSongCount")?.let { (it as Number).toInt() },
                                    songCount = rs.getInt("songCount"),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    fun playlistSongs(playlistId: String): List<DesktopSong> = synchronized(dbLock) {
        openConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT s.* FROM song s
                JOIN playlist_song_map m ON m.songId = s.id
                WHERE m.playlistId = ?
                ORDER BY m.position ASC
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, playlistId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(readSong(conn, rs))
                    }
                }
            }
        }
    }

    // Search history
    fun addSearchQuery(query: String) {
        val cleaned = query.trim()
        if (cleaned.isEmpty()) return
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.prepareStatement("INSERT OR REPLACE INTO search_history (query) VALUES (?)").use { ps ->
                    ps.setString(1, cleaned)
                    ps.executeUpdate()
                }
            }
        }
        notifyChanged()
    }

    fun searchHistory(limit: Int = 20): List<String> = synchronized(dbLock) {
        openConnection().use { conn ->
            conn.prepareStatement("SELECT query FROM search_history ORDER BY id DESC LIMIT ?").use { ps ->
                ps.setInt(1, limit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(rs.getString(1))
                    }
                }
            }
        }
    }

    fun deleteSearchQuery(query: String) {
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.prepareStatement("DELETE FROM search_history WHERE query = ?").use { ps ->
                    ps.setString(1, query)
                    ps.executeUpdate()
                }
            }
        }
        notifyChanged()
    }

    fun clearSearchHistory() {
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.createStatement().use { it.execute("DELETE FROM search_history") }
            }
        }
        notifyChanged()
    }

    // Lyrics cache
    fun getLyrics(songId: String): Pair<String, String>? = synchronized(dbLock) {
        openConnection().use { conn ->
            conn.prepareStatement("SELECT lyrics, provider FROM lyrics WHERE id = ? LIMIT 1").use { ps ->
                ps.setString(1, songId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getString("lyrics") to rs.getString("provider") else null
                }
            }
        }
    }

    fun saveLyrics(songId: String, lyrics: String, provider: String) {
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO lyrics (id, lyrics, provider) VALUES (?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        lyrics = excluded.lyrics,
                        provider = excluded.provider,
                        translatedLyrics = '',
                        translationLanguage = '',
                        translationMode = ''
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, songId)
                    ps.setString(2, lyrics)
                    ps.setString(3, provider)
                    ps.executeUpdate()
                }
            }
        }
    }

    fun getLyricsRecord(songId: String): DesktopLyricsRecord? = synchronized(dbLock) {
        openConnection().use { conn ->
            conn.prepareStatement(
                "SELECT lyrics, provider, translatedLyrics, translationLanguage, translationMode FROM lyrics WHERE id = ? LIMIT 1",
            ).use { ps ->
                ps.setString(1, songId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        DesktopLyricsRecord(
                            lyrics = rs.getString("lyrics"),
                            provider = rs.getString("provider"),
                            translatedLyrics = rs.getString("translatedLyrics").orEmpty(),
                            translationLanguage = rs.getString("translationLanguage").orEmpty(),
                            translationMode = rs.getString("translationMode").orEmpty(),
                        )
                    } else null
                }
            }
        }
    }

    fun saveLyricsTranslation(songId: String, translatedLyrics: String, language: String, mode: String) {
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.prepareStatement(
                    "UPDATE lyrics SET translatedLyrics = ?, translationLanguage = ?, translationMode = ? WHERE id = ?",
                ).use { ps ->
                    ps.setString(1, translatedLyrics)
                    ps.setString(2, language)
                    ps.setString(3, mode)
                    ps.setString(4, songId)
                    ps.executeUpdate()
                }
            }
        }
    }

    fun deleteLyrics(songId: String) {
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.prepareStatement("DELETE FROM lyrics WHERE id = ?").use { ps ->
                    ps.setString(1, songId)
                    ps.executeUpdate()
                }
            }
        }
    }

    fun setSongLyricsOffset(songId: String, offsetMs: Int) {
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.prepareStatement("UPDATE song SET lyricsOffset = ? WHERE id = ?").use { ps ->
                    ps.setInt(1, offsetMs.coerceIn(-10_000, 10_000))
                    ps.setString(2, songId)
                    ps.executeUpdate()
                }
            }
        }
        notifyChanged()
    }

    fun setSongRomanizeLyrics(songId: String, romanize: Boolean) {
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.prepareStatement("UPDATE song SET romanizeLyrics = ? WHERE id = ?").use { ps ->
                    ps.setInt(1, if (romanize) 1 else 0)
                    ps.setString(2, songId)
                    ps.executeUpdate()
                }
            }
        }
        notifyChanged()
    }

    fun setEpisodePlaybackPosition(songId: String, positionMs: Long?) {
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.prepareStatement("UPDATE song SET playbackPosition = ? WHERE id = ?").use { ps ->
                    if (positionMs != null) ps.setLong(1, positionMs) else ps.setNull(1, java.sql.Types.INTEGER)
                    ps.setString(2, songId)
                    ps.executeUpdate()
                }
            }
        }
    }

    fun updateSongMetadata(songId: String, newTitle: String, newArtistName: String?) {
        val cleanTitle = newTitle.trim()
        if (cleanTitle.isEmpty()) return
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.prepareStatement("UPDATE song SET title = ?, dateModified = ? WHERE id = ?").use { ps ->
                    ps.setString(1, cleanTitle)
                    ps.setLong(2, System.currentTimeMillis())
                    ps.setString(3, songId)
                    ps.executeUpdate()
                }
                if (!newArtistName.isNullOrBlank()) {
                    conn.prepareStatement(
                        "SELECT a.id, a.channelId, a.name FROM artist a JOIN song_artist_map m ON m.artistId = a.id WHERE m.songId = ? ORDER BY m.position ASC LIMIT 1",
                    ).use { ps ->
                        ps.setString(1, songId)
                        ps.executeQuery().use { rs ->
                            if (rs.next()) {
                                val aId = rs.getString("id")
                                val chId = rs.getString("channelId")
                                val origName = rs.getString("name")
                                ArtistAliasStore.setAlias(aId, chId, origName, newArtistName.trim())
                            }
                        }
                    }
                }
            }
        }
        notifyChanged()
    }

    fun uploadedSongs(): List<DesktopSong> = querySongs("WHERE isUploaded = 1 ORDER BY COALESCE(dateModified, 0) DESC, title ASC")

    fun episodeSongs(): List<DesktopSong> = querySongs("WHERE isEpisode = 1 ORDER BY COALESCE(dateModified, 0) DESC")

    fun markSongUploaded(song: DesktopSong, uploadEntityId: String? = null) {
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.autoCommit = false
                try {
                    upsertSongInternal(conn, song.copy(isUploaded = true, uploadEntityId = uploadEntityId))
                    conn.prepareStatement("UPDATE song SET isUploaded = 1, uploadEntityId = COALESCE(?, uploadEntityId) WHERE id = ?").use { ps ->
                        ps.setString(1, uploadEntityId)
                        ps.setString(2, song.id)
                        ps.executeUpdate()
                    }
                    conn.commit()
                } catch (t: Throwable) {
                    conn.rollback()
                    throw t
                }
            }
        }
        notifyChanged()
    }

    fun deleteSong(songId: String) {
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.prepareStatement("DELETE FROM song WHERE id = ?").use { ps ->
                    ps.setString(1, songId)
                    ps.executeUpdate()
                }
            }
        }
        notifyChanged()
    }

    fun forgottenFavorites(limit: Int = 20): List<DesktopSong> = synchronized(dbLock) {
        val cutoff = System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000
        openConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT s.* FROM song s
                WHERE (s.liked = 1 OR s.totalPlayTime > 60000)
                  AND COALESCE((SELECT MAX(e.timestamp) FROM event e WHERE e.songId = s.id), 0) < ?
                ORDER BY s.totalPlayTime DESC, s.likedDate DESC
                LIMIT ?
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, cutoff)
                ps.setInt(2, limit)
                ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(readSong(conn, rs)) }
                }
            }
        }
    }

    fun artistLocalSongs(artistId: String): List<DesktopSong> = synchronized(dbLock) {
        openConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT DISTINCT s.* FROM song s
                JOIN song_artist_map m ON m.songId = s.id
                WHERE m.artistId = ? AND (s.inLibrary IS NOT NULL OR s.liked = 1 OR s.isDownloaded = 1)
                ORDER BY s.totalPlayTime DESC, s.title ASC
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, artistId)
                ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(readSong(conn, rs)) }
                }
            }
        }
    }

    // Detailed play history & clear/delete
    fun historyEntries(limit: Int = 150): List<DesktopHistoryEntry> = synchronized(dbLock) {
        openConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT e.id AS eventId, e.timestamp AS ts, e.playTime AS pt, s.*
                FROM event e
                JOIN song s ON s.id = e.songId
                ORDER BY e.timestamp DESC
                LIMIT ?
                """.trimIndent(),
            ).use { ps ->
                ps.setInt(1, limit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                DesktopHistoryEntry(
                                    eventId = rs.getLong("eventId"),
                                    timestampMs = rs.getLong("ts"),
                                    playTimeMs = rs.getLong("pt"),
                                    song = readSong(conn, rs),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    fun deleteHistoryEvent(eventId: Long) {
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.prepareStatement("DELETE FROM event WHERE id = ?").use { ps ->
                    ps.setLong(1, eventId)
                    ps.executeUpdate()
                }
            }
        }
        notifyChanged()
    }

    fun clearListenHistory() {
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.createStatement().use { st ->
                    st.execute("DELETE FROM event")
                    st.execute("DELETE FROM playCount")
                    st.execute("UPDATE song SET totalPlayTime = 0")
                }
            }
        }
        notifyChanged()
    }

    // Time transfer (merge stats from one song to another)
    fun transferSongStats(fromSongId: String, toSongId: String): Boolean {
        if (fromSongId == toSongId) return false
        var transferred = false
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.autoCommit = false
                try {
                    val fromPlayTime = conn.prepareStatement("SELECT totalPlayTime FROM song WHERE id = ?").use { ps ->
                        ps.setString(1, fromSongId)
                        ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
                    }
                    conn.prepareStatement("UPDATE event SET songId = ? WHERE songId = ?").use { ps ->
                        ps.setString(1, toSongId)
                        ps.setString(2, fromSongId)
                        ps.executeUpdate()
                    }
                    conn.prepareStatement(
                        """
                        INSERT INTO playCount (song, year, month, count)
                        SELECT ?, year, month, count FROM playCount WHERE song = ?
                        ON CONFLICT(song, year, month) DO UPDATE SET count = playCount.count + excluded.count
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setString(1, toSongId)
                        ps.setString(2, fromSongId)
                        ps.executeUpdate()
                    }
                    conn.prepareStatement("DELETE FROM playCount WHERE song = ?").use { ps ->
                        ps.setString(1, fromSongId)
                        ps.executeUpdate()
                    }
                    conn.prepareStatement("UPDATE song SET totalPlayTime = totalPlayTime + ? WHERE id = ?").use { ps ->
                        ps.setLong(1, fromPlayTime)
                        ps.setString(2, toSongId)
                        ps.executeUpdate()
                    }
                    conn.prepareStatement("UPDATE song SET totalPlayTime = 0 WHERE id = ?").use { ps ->
                        ps.setString(1, fromSongId)
                        ps.executeUpdate()
                    }
                    conn.commit()
                    transferred = true
                } catch (t: Throwable) {
                    conn.rollback()
                    throw t
                }
            }
        }
        if (transferred) notifyChanged()
        return transferred
    }

    // Playlist extended operations
    fun addSongToPlaylistAt(playlistId: String, song: DesktopSong, atBeginning: Boolean = false, setVideoId: String? = null) {
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.autoCommit = false
                try {
                    upsertSongInternal(conn, song)
                    val pos = if (atBeginning) {
                        conn.prepareStatement("UPDATE playlist_song_map SET position = position + 1 WHERE playlistId = ?").use { ps ->
                            ps.setString(1, playlistId)
                            ps.executeUpdate()
                        }
                        0
                    } else {
                        conn.prepareStatement("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_song_map WHERE playlistId = ?").use { ps ->
                            ps.setString(1, playlistId)
                            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                        }
                    }
                    conn.prepareStatement(
                        "INSERT INTO playlist_song_map (playlistId, songId, position, setVideoId) VALUES (?, ?, ?, ?)",
                    ).use { ps ->
                        ps.setString(1, playlistId)
                        ps.setString(2, song.id)
                        ps.setInt(3, pos)
                        ps.setString(4, setVideoId)
                        ps.executeUpdate()
                    }
                    conn.prepareStatement(
                        "UPDATE playlist SET thumbnailUrl = COALESCE(thumbnailUrl, ?), lastUpdateTime = ? WHERE id = ?",
                    ).use { ps ->
                        ps.setString(1, song.thumbnailUrl)
                        ps.setLong(2, System.currentTimeMillis())
                        ps.setString(3, playlistId)
                        ps.executeUpdate()
                    }
                    conn.commit()
                } catch (t: Throwable) {
                    conn.rollback()
                    throw t
                }
            }
        }
        notifyChanged()
    }

    fun reorderPlaylistSongs(playlistId: String, fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.autoCommit = false
                try {
                    val mapIds = conn.prepareStatement(
                        "SELECT id FROM playlist_song_map WHERE playlistId = ? ORDER BY position ASC",
                    ).use { ps ->
                        ps.setString(1, playlistId)
                        ps.executeQuery().use { rs ->
                            mutableListOf<Long>().apply { while (rs.next()) add(rs.getLong("id")) }
                        }
                    }
                    if (fromIndex !in mapIds.indices || toIndex !in mapIds.indices) {
                        conn.rollback()
                        return
                    }
                    val moved = mapIds.removeAt(fromIndex)
                    mapIds.add(toIndex, moved)
                    conn.prepareStatement("UPDATE playlist_song_map SET position = ? WHERE id = ?").use { ps ->
                        mapIds.forEachIndexed { idx, rowId ->
                            ps.setInt(1, idx)
                            ps.setLong(2, rowId)
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }
                    conn.prepareStatement("UPDATE playlist SET lastUpdateTime = ? WHERE id = ?").use { ps ->
                        ps.setLong(1, System.currentTimeMillis())
                        ps.setString(2, playlistId)
                        ps.executeUpdate()
                    }
                    conn.commit()
                } catch (t: Throwable) {
                    conn.rollback()
                    throw t
                }
            }
        }
        notifyChanged()
    }

    fun updatePlaylistThumbnail(playlistId: String, thumbnailUrl: String?) {
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.prepareStatement("UPDATE playlist SET thumbnailUrl = ?, lastUpdateTime = ? WHERE id = ?").use { ps ->
                    ps.setString(1, thumbnailUrl)
                    ps.setLong(2, System.currentTimeMillis())
                    ps.setString(3, playlistId)
                    ps.executeUpdate()
                }
            }
        }
        notifyChanged()
    }

    fun getPlaylistSetVideoId(playlistId: String, songId: String): String? = synchronized(dbLock) {
        openConnection().use { conn ->
            conn.prepareStatement(
                "SELECT setVideoId FROM playlist_song_map WHERE playlistId = ? AND songId = ? LIMIT 1",
            ).use { ps ->
                ps.setString(1, playlistId)
                ps.setString(2, songId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString("setVideoId") else null }
            }
        }
    }

    // Speed Dial
    fun speedDialItems(): List<DesktopSpeedDialItem> = synchronized(dbLock) {
        openConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM speed_dial_item ORDER BY createDate DESC").use { ps ->
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                DesktopSpeedDialItem(
                                    id = rs.getString("id"),
                                    secondaryId = rs.getString("secondaryId"),
                                    title = rs.getString("title"),
                                    subtitle = rs.getString("subtitle"),
                                    thumbnailUrl = rs.getString("thumbnailUrl"),
                                    type = rs.getString("type"),
                                    explicit = rs.getInt("explicit") != 0,
                                    createDate = rs.getLong("createDate"),
                                    albumId = rs.getString("albumId"),
                                    albumName = rs.getString("albumName"),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    fun isPinnedToSpeedDial(id: String): Boolean = synchronized(dbLock) {
        openConnection().use { conn ->
            conn.prepareStatement("SELECT 1 FROM speed_dial_item WHERE id = ? LIMIT 1").use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { it.next() }
            }
        }
    }

    fun toggleSpeedDial(item: DesktopSpeedDialItem): Boolean {
        var pinned = false
        synchronized(dbLock) {
            openConnection().use { conn ->
                val exists = conn.prepareStatement("SELECT 1 FROM speed_dial_item WHERE id = ? LIMIT 1").use { ps ->
                    ps.setString(1, item.id)
                    ps.executeQuery().use { it.next() }
                }
                if (exists) {
                    conn.prepareStatement("DELETE FROM speed_dial_item WHERE id = ?").use { ps ->
                        ps.setString(1, item.id)
                        ps.executeUpdate()
                    }
                    pinned = false
                } else {
                    conn.prepareStatement(
                        """
                        INSERT OR REPLACE INTO speed_dial_item
                        (id, secondaryId, title, subtitle, subtitleIds, thumbnailUrl, type, explicit, createDate, albumId, albumName)
                        VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setString(1, item.id)
                        ps.setString(2, item.secondaryId)
                        ps.setString(3, item.title)
                        ps.setString(4, item.subtitle)
                        ps.setString(5, item.thumbnailUrl)
                        ps.setString(6, item.type)
                        ps.setInt(7, if (item.explicit) 1 else 0)
                        ps.setLong(8, System.currentTimeMillis())
                        ps.setString(9, item.albumId)
                        ps.setString(10, item.albumName)
                        ps.executeUpdate()
                    }
                    pinned = true
                }
            }
        }
        notifyChanged()
        return pinned
    }

    // Podcasts
    fun bookmarkedPodcasts(): List<DesktopPodcast> = synchronized(dbLock) {
        openConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM podcast WHERE bookmarkedAt IS NOT NULL ORDER BY title ASC").use { ps ->
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                DesktopPodcast(
                                    id = rs.getString("id"),
                                    title = rs.getString("title"),
                                    author = rs.getString("author"),
                                    thumbnailUrl = rs.getString("thumbnailUrl"),
                                    channelId = rs.getString("channelId"),
                                    bookmarkedAt = rs.getObject("bookmarkedAt")?.let { (it as Number).toLong() },
                                    libraryAddToken = rs.getString("libraryAddToken"),
                                    libraryRemoveToken = rs.getString("libraryRemoveToken"),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    fun isPodcastBookmarked(podcastId: String): Boolean = synchronized(dbLock) {
        openConnection().use { conn ->
            conn.prepareStatement("SELECT bookmarkedAt FROM podcast WHERE id = ?").use { ps ->
                ps.setString(1, podcastId)
                ps.executeQuery().use { rs -> rs.next() && rs.getObject("bookmarkedAt") != null }
            }
        }
    }

    fun togglePodcastBookmark(podcast: DesktopPodcast): Boolean {
        val now = System.currentTimeMillis()
        var bookmarked = false
        synchronized(dbLock) {
            openConnection().use { conn ->
                val current = conn.prepareStatement("SELECT bookmarkedAt FROM podcast WHERE id = ?").use { ps ->
                    ps.setString(1, podcast.id)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getObject("bookmarkedAt") else null }
                }
                val newVal = if (current != null) null else now
                bookmarked = newVal != null
                conn.prepareStatement(
                    """
                    INSERT INTO podcast (id, title, author, thumbnailUrl, channelId, bookmarkedAt, lastUpdateTime, libraryAddToken, libraryRemoveToken)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        title = excluded.title,
                        author = COALESCE(excluded.author, podcast.author),
                        thumbnailUrl = COALESCE(excluded.thumbnailUrl, podcast.thumbnailUrl),
                        channelId = COALESCE(excluded.channelId, podcast.channelId),
                        bookmarkedAt = excluded.bookmarkedAt,
                        lastUpdateTime = excluded.lastUpdateTime
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, podcast.id)
                    ps.setString(2, podcast.title)
                    ps.setString(3, podcast.author)
                    ps.setString(4, podcast.thumbnailUrl)
                    ps.setString(5, podcast.channelId)
                    if (newVal != null) ps.setLong(6, newVal) else ps.setNull(6, java.sql.Types.INTEGER)
                    ps.setLong(7, now)
                    ps.setString(8, podcast.libraryAddToken)
                    ps.setString(9, podcast.libraryRemoveToken)
                    ps.executeUpdate()
                }
            }
        }
        notifyChanged()
        return bookmarked
    }

    // Format metadata (for technical details & loudness normalization)
    fun saveFormat(info: DesktopFormatInfo) {
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO format (id, itag, mimeType, codecs, bitrate, sampleRate, contentLength, loudnessDb, perceptualLoudnessDb, playbackUrl)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        itag = excluded.itag,
                        mimeType = excluded.mimeType,
                        codecs = excluded.codecs,
                        bitrate = excluded.bitrate,
                        sampleRate = excluded.sampleRate,
                        contentLength = excluded.contentLength,
                        loudnessDb = COALESCE(excluded.loudnessDb, format.loudnessDb),
                        perceptualLoudnessDb = COALESCE(excluded.perceptualLoudnessDb, format.perceptualLoudnessDb),
                        playbackUrl = excluded.playbackUrl
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, info.id)
                    ps.setInt(2, info.itag)
                    ps.setString(3, info.mimeType)
                    ps.setString(4, info.codecs)
                    ps.setInt(5, info.bitrate)
                    if (info.sampleRate != null) ps.setInt(6, info.sampleRate) else ps.setNull(6, java.sql.Types.INTEGER)
                    ps.setLong(7, info.contentLength)
                    if (info.loudnessDb != null) ps.setDouble(8, info.loudnessDb) else ps.setNull(8, java.sql.Types.REAL)
                    if (info.perceptualLoudnessDb != null) ps.setDouble(9, info.perceptualLoudnessDb) else ps.setNull(9, java.sql.Types.REAL)
                    ps.setString(10, info.playbackUrl)
                    ps.executeUpdate()
                }
            }
        }
    }

    fun getFormat(songId: String): DesktopFormatInfo? = synchronized(dbLock) {
        openConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM format WHERE id = ? LIMIT 1").use { ps ->
                ps.setString(1, songId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return@synchronized null
                    DesktopFormatInfo(
                        id = rs.getString("id"),
                        itag = rs.getInt("itag"),
                        mimeType = rs.getString("mimeType"),
                        codecs = rs.getString("codecs"),
                        bitrate = rs.getInt("bitrate"),
                        sampleRate = rs.getObject("sampleRate")?.let { (it as Number).toInt() },
                        contentLength = rs.getLong("contentLength"),
                        loudnessDb = rs.getObject("loudnessDb")?.let { (it as Number).toDouble() },
                        perceptualLoudnessDb = rs.getObject("perceptualLoudnessDb")?.let { (it as Number).toDouble() },
                        playbackUrl = rs.getString("playbackUrl"),
                    )
                }
            }
        }
    }

    // Recognition history (Shazam)
    fun addRecognitionHistory(item: DesktopRecognitionHistoryItem) {
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO recognition_history
                    (trackId, title, artist, album, coverArtUrl, coverArtHqUrl, genre, releaseDate, label, shazamUrl, appleMusicUrl, spotifyUrl, isrc, youtubeVideoId, recognizedAt, liked)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, item.trackId)
                    ps.setString(2, item.title)
                    ps.setString(3, item.artist)
                    ps.setString(4, item.album)
                    ps.setString(5, item.coverArtUrl)
                    ps.setString(6, item.coverArtHqUrl)
                    ps.setString(7, item.genre)
                    ps.setString(8, item.releaseDate)
                    ps.setString(9, item.label)
                    ps.setString(10, item.shazamUrl)
                    ps.setString(11, item.appleMusicUrl)
                    ps.setString(12, item.spotifyUrl)
                    ps.setString(13, item.isrc)
                    ps.setString(14, item.youtubeVideoId)
                    ps.setLong(15, item.recognizedAt)
                    ps.setInt(16, if (item.liked) 1 else 0)
                    ps.executeUpdate()
                }
            }
        }
        notifyChanged()
    }

    fun recognitionHistory(limit: Int = 100): List<DesktopRecognitionHistoryItem> = synchronized(dbLock) {
        openConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM recognition_history ORDER BY recognizedAt DESC LIMIT ?").use { ps ->
                ps.setInt(1, limit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                DesktopRecognitionHistoryItem(
                                    id = rs.getLong("id"),
                                    trackId = rs.getString("trackId"),
                                    title = rs.getString("title"),
                                    artist = rs.getString("artist"),
                                    album = rs.getString("album"),
                                    coverArtUrl = rs.getString("coverArtUrl"),
                                    coverArtHqUrl = rs.getString("coverArtHqUrl"),
                                    genre = rs.getString("genre"),
                                    releaseDate = rs.getString("releaseDate"),
                                    label = rs.getString("label"),
                                    shazamUrl = rs.getString("shazamUrl"),
                                    appleMusicUrl = rs.getString("appleMusicUrl"),
                                    spotifyUrl = rs.getString("spotifyUrl"),
                                    isrc = rs.getString("isrc"),
                                    youtubeVideoId = rs.getString("youtubeVideoId"),
                                    recognizedAt = rs.getLong("recognizedAt"),
                                    liked = rs.getInt("liked") != 0,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    fun deleteRecognitionHistory(id: Long) {
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.prepareStatement("DELETE FROM recognition_history WHERE id = ?").use { ps ->
                    ps.setLong(1, id)
                    ps.executeUpdate()
                }
            }
        }
        notifyChanged()
    }

    fun clearRecognitionHistory() {
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.createStatement().use { it.execute("DELETE FROM recognition_history") }
            }
        }
        notifyChanged()
    }

    // Local library search
    data class LocalSearchResults(
        val songs: List<DesktopSong> = emptyList(),
        val albums: List<DesktopAlbum> = emptyList(),
        val artists: List<DesktopArtist> = emptyList(),
        val playlists: List<DesktopPlaylist> = emptyList(),
    )

    fun searchLocal(query: String, limit: Int = 50): LocalSearchResults = synchronized(dbLock) {
        val pattern = "%${query.trim()}%"
        if (query.isBlank()) return@synchronized LocalSearchResults()
        openConnection().use { conn ->
            val songs = conn.prepareStatement(
                """
                SELECT DISTINCT s.* FROM song s
                LEFT JOIN song_artist_map m ON m.songId = s.id
                LEFT JOIN artist a ON a.id = m.artistId
                WHERE s.title LIKE ? OR s.albumName LIKE ? OR a.name LIKE ?
                ORDER BY s.liked DESC, s.totalPlayTime DESC, s.title ASC
                LIMIT ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, pattern)
                ps.setString(2, pattern)
                ps.setString(3, pattern)
                ps.setInt(4, limit)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(readSong(conn, rs)) } }
            }
            val albums = conn.prepareStatement(
                "SELECT * FROM album WHERE title LIKE ? ORDER BY bookmarkedAt IS NULL, title ASC LIMIT ?",
            ).use { ps ->
                ps.setString(1, pattern)
                ps.setInt(2, limit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                DesktopAlbum(
                                    id = rs.getString("id"),
                                    playlistId = rs.getString("playlistId"),
                                    title = rs.getString("title"),
                                    year = rs.getObject("year")?.let { (it as Number).toInt() },
                                    thumbnailUrl = rs.getString("thumbnailUrl"),
                                    songCount = rs.getInt("songCount"),
                                    duration = rs.getInt("duration"),
                                    explicit = rs.getInt("explicit") != 0,
                                    bookmarkedAt = rs.getObject("bookmarkedAt")?.let { (it as Number).toLong() },
                                ),
                            )
                        }
                    }
                }
            }
            val artists = conn.prepareStatement(
                "SELECT * FROM artist WHERE name LIKE ? ORDER BY bookmarkedAt IS NULL, name ASC LIMIT ?",
            ).use { ps ->
                ps.setString(1, pattern)
                ps.setInt(2, limit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                DesktopArtist(
                                    id = rs.getString("id"),
                                    name = rs.getString("name"),
                                    thumbnailUrl = rs.getString("thumbnailUrl"),
                                    channelId = rs.getString("channelId"),
                                    bookmarkedAt = rs.getObject("bookmarkedAt")?.let { (it as Number).toLong() },
                                ),
                            )
                        }
                    }
                }
            }
            val playlists = conn.prepareStatement(
                """
                SELECT p.*, (SELECT COUNT(*) FROM playlist_song_map m WHERE m.playlistId = p.id) AS songCount
                FROM playlist p WHERE p.name LIKE ? ORDER BY p.name ASC LIMIT ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, pattern)
                ps.setInt(2, limit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                DesktopPlaylist(
                                    id = rs.getString("id"),
                                    name = rs.getString("name"),
                                    browseId = rs.getString("browseId"),
                                    thumbnailUrl = rs.getString("thumbnailUrl"),
                                    isEditable = rs.getInt("isEditable") != 0,
                                    isLocal = rs.getInt("isLocal") != 0,
                                    remoteSongCount = rs.getObject("remoteSongCount")?.let { (it as Number).toInt() },
                                    songCount = rs.getInt("songCount"),
                                ),
                            )
                        }
                    }
                }
            }
            LocalSearchResults(songs, albums, artists, playlists)
        }
    }

    // Safe backup swap under dbLock
    fun replaceDatabaseFile(newDbBytes: ByteArray) {
        synchronized(dbLock) {
            dbFile.parentFile?.mkdirs()
            File("${dbFile.absolutePath}-wal").delete()
            File("${dbFile.absolutePath}-shm").delete()
            dbFile.writeBytes(newDbBytes)
        }
        ensureSchema()
        notifyChanged()
    }

    fun clearLibraryData() {
        synchronized(dbLock) {
            openConnection().use { conn ->
                conn.createStatement().use { st ->
                    st.execute("UPDATE song SET liked = 0, likedDate = NULL, inLibrary = NULL")
                    st.execute("UPDATE artist SET bookmarkedAt = NULL")
                    st.execute("UPDATE album SET bookmarkedAt = NULL")
                    st.execute("DELETE FROM playlist WHERE isLocal = 0")
                    st.execute("DELETE FROM podcast")
                }
            }
        }
        notifyChanged()
    }

    private data class ExistingSongFlags(
        val liked: Boolean,
        val likedDate: Long?,
        val inLibrary: Long?,
        val dateDownload: Long?,
        val isDownloaded: Boolean,
        val totalPlayTime: Long,
    )
}
