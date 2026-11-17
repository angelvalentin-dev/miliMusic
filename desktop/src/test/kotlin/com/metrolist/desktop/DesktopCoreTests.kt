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
}
