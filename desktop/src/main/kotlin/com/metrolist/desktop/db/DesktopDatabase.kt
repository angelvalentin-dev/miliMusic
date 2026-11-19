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
                st.execute("PRAGMA journal_mode = WAL")
                st.execute("PRAGMA synchronous = NORMAL")
            }
        }
    }

    private fun ensureSchema() = synchronized(dbLock) {
        openConnection().use { conn ->
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
            liked = rs.getInt("liked") != 0,
            likedDate = rs.getObject("likedDate")?.let { (it as Number).toLong() },
            inLibrary = rs.getObject("inLibrary")?.let { (it as Number).toLong() },
            dateDownload = rs.getObject("dateDownload")?.let { (it as Number).toLong() },
            isDownloaded = rs.getInt("isDownloaded") != 0,
            isVideo = rs.getInt("isVideo") != 0,
            isEpisode = rs.getInt("isEpisode") != 0,
            totalPlayTime = rs.getLong("totalPlayTime"),
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
                    ON CONFLICT(id) DO UPDATE SET lyrics = excluded.lyrics, provider = excluded.provider
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

    private data class ExistingSongFlags(
        val liked: Boolean,
        val likedDate: Long?,
        val inLibrary: Long?,
        val dateDownload: Long?,
        val isDownloaded: Boolean,
        val totalPlayTime: Long,
    )
}
