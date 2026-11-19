package com.metrolist.desktop

import com.metrolist.desktop.audio.InnerTubeStreamResolver
import com.metrolist.desktop.audio.StreamProxyServer
import com.metrolist.desktop.backup.BackupRestoreService
import com.metrolist.desktop.data.DesktopAudioQuality
import com.metrolist.desktop.data.DesktopPrefKeys
import com.metrolist.desktop.data.DesktopPreferences
import com.metrolist.desktop.db.ArtistRef
import com.metrolist.desktop.db.DesktopDatabase
import com.metrolist.desktop.db.DesktopPlaylist
import com.metrolist.desktop.db.DesktopSong
import com.metrolist.desktop.lyrics.DesktopLyricsService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.sql.DriverManager
import java.util.zip.ZipFile

class DesktopCoreTests {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `database initializes exact room v38 schema and supports crud`() {
        val dbFile = tempFolder.newFile("song.db").apply { delete() }
        val db = DesktopDatabase(dbFile)

        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            val userVersion = conn.createStatement().use { st ->
                st.executeQuery("PRAGMA user_version").use { rs ->
                    assertTrue(rs.next())
                    rs.getInt(1)
                }
            }
            assertEquals(38, userVersion)

            val roomHash = conn.createStatement().use { st ->
                st.executeQuery("SELECT identity_hash FROM room_master_table WHERE id = 42").use { rs ->
                    assertTrue(rs.next())
                    rs.getString(1)
                }
            }
            assertEquals("bcf76ed36a3cb785f239e5cfbd28440e", roomHash)
        }

        val song = DesktopSong(
            id = "dQw4w9WgXcQ",
            title = "Track One",
            duration = 213,
            albumId = "MPREb_test",
            albumName = "Album Test",
            artists = listOf(ArtistRef("UC_artist1", "Artist One")),
        )
        db.upsertSong(song)
        val loaded = db.getSong(song.id)
        assertNotNull(loaded)
        assertEquals("Track One", loaded?.title)
        assertEquals("Artist One", loaded?.artistDisplay)

        val liked = db.toggleLike(song)
        assertTrue(liked.liked)
        assertNotNull(liked.inLibrary)
        assertEquals(1, db.likedSongs().size)

        val playlist = db.createPlaylist("Favoritas Desktop")
        db.addSongToPlaylist(playlist.id, song)
        assertEquals(1, db.playlistSongs(playlist.id).size)

        db.upsertRemotePlaylist(
            DesktopPlaylist(
                id = "VLPL_remote",
                name = "Remote Mix",
                browseId = "VLPL_remote",
                isEditable = false,
                isLocal = false,
            ),
        )
        assertTrue(db.playlists().any { it.id == "VLPL_remote" && !it.isLocal })

        db.removeSongFromPlaylist(playlist.id, song.id)
        assertEquals(0, db.playlistSongs(playlist.id).size)
        db.renamePlaylist(playlist.id, "Renombrada")
        assertTrue(db.playlists().any { it.id == playlist.id && it.name == "Renombrada" })
        db.deletePlaylist(playlist.id)
        assertFalse(db.playlists().any { it.id == playlist.id })

        db.addSearchQuery("synthwave")
        assertEquals(listOf("synthwave"), db.searchHistory())
        db.clearSearchHistory()
        assertTrue(db.searchHistory().isEmpty())

        db.saveLyrics(song.id, "[00:01.00]Hola", "LrcLib")
        assertEquals("[00:01.00]Hola" to "LrcLib", db.getLyrics(song.id))
    }

    @Test
    fun `backup and restore round-trips settings and sqlite database`() = runBlocking {
        val dir = tempFolder.newFolder("backup_test")
        val dbFile = File(dir, "song.db")
        val prefsFile = File(dir, "settings.preferences_pb")
        val archive = File(dir, "miliMusic.backup")

        val db = DesktopDatabase(dbFile)
        val prefs = DesktopPreferences(preferencesFile = prefsFile)
        val service = BackupRestoreService(db, prefs, dbFile = dbFile, preferencesFile = prefsFile)

        val song = DesktopSong(id = "song_restore_1", title = "Saved Before Backup", duration = 180)
        db.upsertSong(song)
        prefs.set(DesktopPrefKeys.AccountName, "Angel Tester")

        assertTrue(service.exportBackup(archive).isSuccess)
        assertTrue(archive.exists())
        ZipFile(archive).use { zip ->
            assertNotNull(zip.getEntry(BackupRestoreService.DB_NAME))
            assertNotNull(zip.getEntry(BackupRestoreService.SETTINGS_FILENAME))
        }

        // Mutate state and restore
        db.upsertSong(DesktopSong(id = "temp_only", title = "Temp"))
        prefs.set(DesktopPrefKeys.AccountName, "Modified")
        dbFile.delete()

        val restored = service.importBackup(archive)
        assertTrue(restored.exceptionOrNull()?.message ?: "ok", restored.isSuccess)
        assertNotNull(db.getSong("song_restore_1"))
        assertNull(db.getSong("temp_only"))
        assertEquals("Angel Tester", prefs.settingsFlow.first().accountName)
    }

    @Test
    fun `lyrics parser handles synced LRC, word tags, and plain lyrics`() {
        val dbFile = tempFolder.newFile("lyrics.db").apply { delete() }
        val service = DesktopLyricsService(DesktopDatabase(dbFile))

        val syncedRaw = """
            [00:12.500]{agent:v1}Second <00:12.800>line
            [00:05.25]{bg}First line
        """.trimIndent()
        val (syncedEntries, isSynced) = service.parseLyrics(syncedRaw)
        assertTrue(isSynced)
        assertEquals(2, syncedEntries.size)
        assertEquals(5250L, syncedEntries[0].timeMs)
        assertEquals("First line", syncedEntries[0].text)
        assertEquals(12500L, syncedEntries[1].timeMs)
        assertEquals("Second line", syncedEntries[1].text)

        val (plainEntries, isPlainSynced) = service.parseLyrics("Line A\nLine B")
        assertFalse(isPlainSynced)
        assertEquals(2, plainEntries.size)
        assertEquals("Line A", plainEntries[0].text)
    }

    @Test
    fun `stream proxy serves local files with full 200 and 206 range responses`() {
        val downloadsDir = tempFolder.newFolder("downloads")
        val cacheDir = tempFolder.newFolder("cache")
        val payload = "0123456789ABCDEF".toByteArray()
        File(downloadsDir, "vid123.m4a").writeBytes(payload)

        val proxy = StreamProxyServer(
            resolver = InnerTubeStreamResolver(),
            qualityProvider = { DesktopAudioQuality.HIGH },
            downloadsDirProvider = { downloadsDir },
            cacheEnabledProvider = { false },
            cacheDirProvider = { cacheDir },
        )
        try {
            val url = URI(proxy.streamUrlFor("vid123")).toURL()

            val fullConn = url.openConnection() as HttpURLConnection
            assertEquals(200, fullConn.responseCode)
            assertEquals("audio/mp4", fullConn.contentType)
            assertArrayEquals(payload, fullConn.inputStream.use { it.readBytes() })
            fullConn.disconnect()

            val rangeConn = url.openConnection() as HttpURLConnection
            rangeConn.setRequestProperty("Range", "bytes=4-9")
            assertEquals(206, rangeConn.responseCode)
            assertEquals("bytes 4-9/16", rangeConn.getHeaderField("Content-Range"))
            assertArrayEquals("456789".toByteArray(), rangeConn.inputStream.use { it.readBytes() })
            rangeConn.disconnect()
        } finally {
            proxy.close()
        }
    }

    @Test
    fun `statistics queries and auto-playlists aggregate plays, artists, albums, and cached songs`() {
        val dbFile = tempFolder.newFile("stats.db").apply { delete() }
        val cacheDir = tempFolder.newFolder("song_cache")
        val db = DesktopDatabase(dbFile)

        val song1 = DesktopSong(
            id = "s1",
            title = "Alpha Song",
            duration = 200,
            albumId = "alb1",
            albumName = "Alpha Album",
            artists = listOf(ArtistRef("art1", "Alpha Artist")),
        )
        val song2 = DesktopSong(
            id = "s2",
            title = "Beta Song",
            duration = 180,
            albumId = "alb2",
            albumName = "Beta Album",
            artists = listOf(ArtistRef("art2", "Beta Artist")),
        )

        db.recordPlay(song1, 180_000L)
        db.recordPlay(song1, 120_000L)
        db.recordPlay(song2, 90_000L)

        val summary = db.listeningSummary(0L)
        assertEquals(390_000L, summary.totalPlayTimeMs)
        assertEquals(3, summary.totalPlays)
        assertEquals(2, summary.uniqueSongs)
        assertEquals(2, summary.uniqueArtists)
        assertEquals(2, summary.uniqueAlbums)

        val topSongs = db.mostPlayedSongs(0L)
        assertEquals(2, topSongs.size)
        assertEquals("s1", topSongs[0].song.id)
        assertEquals(2, topSongs[0].playCount)
        assertEquals(300_000L, topSongs[0].playTimeMs)

        val topArtists = db.mostPlayedArtists(0L)
        assertEquals("art1", topArtists[0].artist.id)
        assertEquals(2, topArtists[0].playCount)

        val topAlbums = db.mostPlayedAlbums(0L)
        assertEquals("alb1", topAlbums[0].album.id)
        assertEquals("Alpha Album", topAlbums[0].album.title)

        File(cacheDir, "s2.cache").writeBytes("cached-audio".toByteArray())
        val cached = db.cachedSongs(cacheDir)
        assertEquals(listOf("s2"), cached.map { it.id })
    }

    @Test
    fun `listen together proto3 codec, lastfm md5, and alarm json round-trip accurately`() {
        val track = com.metrolist.desktop.integrations.LtTrackInfo(
            id = "vid999",
            title = "Proto Track",
            artist = "Proto Artist",
            album = "Proto Album",
            duration = 215_000L,
            thumbnail = "https://example.com/t.jpg",
        )
        val actionPayload = com.metrolist.desktop.integrations.ListenTogetherProtoCodec.encodePlaybackAction(
            action = "change_track",
            trackId = track.id,
            positionMs = 42_000L,
            trackInfo = track,
            revision = 7L,
        )
        val env = com.metrolist.desktop.integrations.ListenTogetherProtoCodec.encodeEnvelope("sync_playback", actionPayload, compress = true)
        val (decodedType, decodedBytes) = com.metrolist.desktop.integrations.ListenTogetherProtoCodec.decodeEnvelope(env)
        assertEquals("sync_playback", decodedType)
        val decodedAction = com.metrolist.desktop.integrations.ListenTogetherProtoCodec.decodePlaybackAction(decodedBytes)
        assertEquals("change_track", decodedAction.action)
        assertEquals("vid999", decodedAction.trackId)
        assertEquals(42_000L, decodedAction.positionMs)
        assertEquals("Proto Track", decodedAction.trackInfo?.title)
        assertEquals(215_000L, decodedAction.trackInfo?.duration)

        val sig = com.metrolist.desktop.integrations.DesktopLastFmService.md5Sig(
            mapOf("method" to "auth.getToken", "api_key" to "testKey"),
            "secret123",
        )
        assertEquals(32, sig.length)

        val rawAlarms = """[{"id":"a1","enabled":true,"hour":8,"minute":30,"playlistId":"TOP","randomSong":true,"nextTriggerAt":-1}]"""
        val parsed = com.metrolist.desktop.integrations.DesktopAlarmScheduler.parseAlarms(rawAlarms)
        assertEquals(1, parsed.size)
        assertEquals(8, parsed[0].hour)
        assertEquals(30, parsed[0].minute)
        assertTrue(parsed[0].randomSong)
        val roundTripped = com.metrolist.desktop.integrations.DesktopAlarmScheduler.parseAlarms(
            com.metrolist.desktop.integrations.DesktopAlarmScheduler.serializeAlarms(parsed),
        )
        assertEquals(parsed, roundTripped)
    }
}

