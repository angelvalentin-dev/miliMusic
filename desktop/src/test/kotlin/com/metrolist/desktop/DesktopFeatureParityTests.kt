package com.metrolist.desktop

import com.metrolist.desktop.audio.DesktopParametricEq
import com.metrolist.desktop.audio.EqFilterType
import com.metrolist.desktop.audio.InnerTubeStreamResolver
import com.metrolist.desktop.audio.LoudnessNormalizationMath
import com.metrolist.desktop.audio.ParametricEqBand
import com.metrolist.desktop.audio.StreamProxyServer
import com.metrolist.desktop.backup.BackupRestoreService
import com.metrolist.desktop.data.DesktopAudioQuality
import com.metrolist.desktop.data.DesktopLoudnessLevel
import com.metrolist.desktop.data.DesktopPrefKeys
import com.metrolist.desktop.data.DesktopPreferences
import com.metrolist.desktop.db.ArtistAliasStore
import com.metrolist.desktop.db.ArtistRef
import com.metrolist.desktop.db.DesktopDatabase
import com.metrolist.desktop.db.DesktopFormatInfo
import com.metrolist.desktop.db.DesktopPodcast
import com.metrolist.desktop.db.DesktopRecognitionHistoryItem
import com.metrolist.desktop.db.DesktopSong
import com.metrolist.desktop.db.DesktopSpeedDialItem
import com.metrolist.desktop.integrations.ListenTogetherProtoCodec
import com.metrolist.desktop.integrations.LtTrackInfo
import com.metrolist.desktop.lyrics.DesktopLyricsCardRenderer
import com.metrolist.desktop.lyrics.DesktopLyricsRomanizer
import com.metrolist.desktop.lyrics.DesktopLyricsService
import com.metrolist.desktop.lyrics.DesktopLyricsTranslator
import com.metrolist.desktop.recognition.DesktopShazamSignatureGenerator
import com.metrolist.desktop.util.DesktopUpdater
import com.metrolist.desktop.util.ParsedYouTubeLink
import com.metrolist.desktop.util.PlaylistTransferService
import com.metrolist.desktop.util.YouTubeUrlParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class DesktopFeatureParityTests {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `parametric EQ parses Equalizer APO text, evaluates biquad curve, and projects to libVLC 10 bands`() {
        val apo = """
            Preamp: -4.2 dB
            Filter 1: ON LSC Fc 105 Hz Gain 5.5 dB Q 0.70
            Filter 2: ON PK Fc 1000 Hz Gain -3.0 dB Q 1.41
            Filter 3: OFF PK Fc 3000 Hz Gain 12.0 dB Q 2.0
            Filter 4: ON HSC Fc 8000 Hz Gain 2.5 dB Q 0.71
        """.trimIndent()

        val profile = DesktopParametricEq.parseApoText(apo)
        assertEquals(-4.2, profile.preamp, 0.01)
        assertEquals(3, profile.bands.size) // Filter 3 is OFF and ignored
        assertEquals(EqFilterType.LSC, profile.bands[0].filterType)
        assertEquals(EqFilterType.PK, profile.bands[1].filterType)
        assertEquals(EqFilterType.HSC, profile.bands[2].filterType)

        // Peaking band at 1000 Hz with -3 dB should evaluate very close to -3 dB at 1000 Hz
        val pkDb = DesktopParametricEq.evaluateBandDb(
            ParametricEqBand(EqFilterType.PK, frequency = 1000.0, gain = -3.0, q = 1.41),
            freqHz = 1000.0,
        )
        assertEquals(-3.0, pkDb, 0.15)

        val (preamp, bands) = DesktopParametricEq.projectToVlc10Bands(profile)
        assertEquals(10, bands.size)
        assertTrue(preamp in -20f..20f)
        // Bass band (60 Hz) should be boosted by LSC, 1 kHz band (index 4) should be dipped
        assertTrue("Expected low shelf boost at 60 Hz, got ${bands[0]}", bands[0] > 2.5f)
        assertTrue("Expected dip at 1 kHz, got ${bands[4]}", bands[4] < -1.5f)

        val curve = DesktopParametricEq.evaluateGraphicCurve(preamp, bands, points = 32)
        assertEquals(32, curve.size)
    }

    @Test
    fun `loudness normalization and pitch semitone math match targets`() {
        // YouTube loudnessDb is relative to -14 LUFS: 0.0 dB => -14 LUFS => unity gain under BALANCED (-14 LUFS)
        val unity = LoudnessNormalizationMath.computeGainFactor(0.0, DesktopLoudnessLevel.BALANCED)
        assertEquals(1.0f, unity, 0.02f)

        // Loud track (+8.0 dB => -6 LUFS) attenuated under BALANCED (-14 LUFS) => < 0.5
        val attenuated = LoudnessNormalizationMath.computeGainFactor(8.0, DesktopLoudnessLevel.BALANCED)
        assertTrue("Expected attenuation < 0.5, got $attenuated", attenuated in 0.25f..0.5f)

        // Quiet track (-8.0 dB => -22 LUFS) boosted under LOUD (-11 LUFS), clamped safely <= 1.5
        val boosted = LoudnessNormalizationMath.computeGainFactor(-8.0, DesktopLoudnessLevel.LOUD)
        assertTrue("Expected boost > 1.0, got $boosted", boosted > 1.0f && boosted <= 1.5f)

        // Missing loudness metadata defaults to 1.0
        assertEquals(1.0f, LoudnessNormalizationMath.computeGainFactor(null, DesktopLoudnessLevel.AGGRESSIVE), 0.001f)

        // Pitch ratio: 2.0 = +12 semitones, 0.5 = -12 semitones, 1.0 = 0 semitones
        assertEquals(0.0f, LoudnessNormalizationMath.ratioToSemitones(1.0f), 0.01f)
        assertEquals(12.0f, LoudnessNormalizationMath.ratioToSemitones(2.0f), 0.01f)
        assertEquals(-12.0f, LoudnessNormalizationMath.ratioToSemitones(0.5f), 0.01f)
    }

    @Test
    fun `TTML lyrics parser extracts word karaoke timings, duet agents, and background vocals`() {
        val dbFile = File(tempFolder.newFolder("db_ttml"), "song.db")
        val db = DesktopDatabase(dbFile)
        val service = DesktopLyricsService(db)
        try {
            val ttml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
                  <body>
                    <div>
                      <p begin="00:01.000" end="00:03.500" ttm:agent="v1">
                        <span begin="00:01.000" end="00:02.000">Hello</span>
                        <span begin="00:02.100" end="00:03.500">world</span>
                      </p>
                      <p begin="00:04.000" end="00:06.000" ttm:agent="v2">
                        <span begin="00:04.000" end="00:05.000">Answer</span>
                      </p>
                      <p begin="00:05.100" end="00:06.000" ttm:role="x-bg">(echo)</p>
                    </div>
                  </body>
                </tt>
            """.trimIndent()

            val extendedLrc = service.parseTtmlToExtendedLrc(ttml)
            assertNotNull("TTML should parse into extended LRC", extendedLrc)
            val (entries, synced) = service.parseLyrics(extendedLrc!!)
            assertTrue(synced)
            assertTrue("Expected at least 2 entries, got ${entries.size}", entries.size >= 2)

            assertEquals(1_000L, entries[0].timeMs)
            assertEquals("v1", entries[0].agent)
            assertEquals(2, entries[0].words.size)
            assertEquals("Hello", entries[0].words[0].text)
            assertEquals("world", entries[0].words[1].text)

            assertEquals(4_000L, entries[1].timeMs)
            assertEquals("v2", entries[1].agent)
            assertTrue("Expected background vocal entry", entries.any { it.isBackground && it.text.contains("echo") })
        } finally {
            service.close()
        }
    }

    @Test
    fun `offline romanizer handles Japanese, Korean, Cyrillic, Hindi, Punjabi, and filters credits`() {
        val allLangs = setOf("ja", "ko", "ru", "uk", "sr", "bg", "be", "ky", "mk", "hi", "pa")

        val jp = DesktopLyricsRomanizer.romanizeLine("こんにちは世界", allLangs)
        assertNotNull(jp)
        assertTrue("Expected konnichiha in '$jp'", jp!!.contains("konnichiha"))

        val ko = DesktopLyricsRomanizer.romanizeLine("사랑해", allLangs)
        assertNotNull(ko)
        assertTrue("Expected Latin output for Hangul, got '$ko'", ko!!.all { it.code < 128 })

        val ru = DesktopLyricsRomanizer.romanizeLine("Привет мир", setOf("ru"))
        assertNotNull(ru)
        assertTrue("Expected Privet in '$ru'", ru!!.lowercase().contains("privet"))

        val hi = DesktopLyricsRomanizer.romanizeLine("नमस्ते", setOf("hi"))
        assertNotNull(hi)
        assertTrue("Expected Latin output for Devanagari, got '$hi'", hi!!.any { it in 'a'..'z' })

        val pa = DesktopLyricsRomanizer.romanizeLine("ਸਤਿ", setOf("pa"))
        assertNotNull(pa)
        assertTrue("Expected Latin output for Gurmukhi, got '$pa'", pa!!.any { it in 'a'..'z' })

        // Pure Latin text returns null (no romanization needed)
        assertNull(DesktopLyricsRomanizer.romanizeLine("Just English lyrics", allLangs))

        val filtered = DesktopLyricsRomanizer.filterCreditLines(
            "[00:01.00]Lyrics by: Someone\n[00:05.00]Real first verse\n[00:10.00]Composed by: Another",
        )
        assertFalse(filtered.contains("Lyrics by"))
        assertFalse(filtered.contains("Composed by"))
        assertTrue(filtered.contains("Real first verse"))
    }

    @Test
    fun `AI lyrics translator parses JSON object, bare array, and markdown fences cleanly`() {
        val fenced = """
            ```json
            {"lines": ["Hola mundo", "Segunda línea"]}
            ```
        """.trimIndent()
        val res1 = DesktopLyricsTranslator.parseTranslationContent(fenced, 2).getOrThrow()
        assertEquals(listOf("Hola mundo", "Segunda línea"), res1)

        val bareArray = """["Line A", "Line B", "Line C"]"""
        val res2 = DesktopLyricsTranslator.parseTranslationContent(bareArray, 3).getOrThrow()
        assertEquals(listOf("Line A", "Line B", "Line C"), res2)

        // Pads/aligns cleanly if fewer lines returned
        val shortRes = DesktopLyricsTranslator.parseTranslationContent("""{"lines": ["Only one"]}""", 2).getOrThrow()
        assertEquals(2, shortRes.size)
        assertEquals("Only one", shortRes[0])
    }

    @Test
    fun `pure-JVM Shazam fingerprinter resamples 44_1kHz PCM and generates valid base64 signature`() {
        // Generate 3 seconds of 44.1 kHz synthetic multi-tone PCM (16-bit LE)
        val srcRate = 44_100
        val srcSamples = srcRate * 3
        val srcBuf = ByteBuffer.allocate(srcSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until srcSamples) {
            val t = i.toDouble() / srcRate
            val wave = 0.4 * sin(2.0 * PI * (440.0 + 300.0 * t) * t) +
                0.35 * sin(2.0 * PI * 1200.0 * t) +
                0.25 * sin(2.0 * PI * 2600.0 * t)
            srcBuf.putShort((wave * 24_000.0).toInt().coerceIn(-32767, 32767).toShort())
        }

        val resampled = DesktopShazamSignatureGenerator.resampleTo16kMono(srcBuf.array(), srcRate)
        val expectedBytes = (srcSamples.toDouble() * 16_000.0 / srcRate).toInt() * 2
        assertTrue(abs(resampled.size - expectedBytes) <= 16)

        val signatureUri = DesktopShazamSignatureGenerator.fromI16(resampled)
        assertTrue(
            "Expected data:audio/vnd.shazam.sig;base64, URI",
            signatureUri.startsWith("data:audio/vnd.shazam.sig;base64,"),
        )
        val decoded = java.util.Base64.getDecoder().decode(signatureUri.substringAfter("base64,"))
        assertTrue("Signature payload should be non-trivial", decoded.size > 48)
        // Shazam signature magic header 0xCAFE2580 little-endian
        val magic = ByteBuffer.wrap(decoded, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(0xCAFE2580.toInt(), magic)
    }

    @Test
    fun `CSV and M3U playlist export and import preserve metadata and quoted fields`() {
        val dir = tempFolder.newFolder("transfer")
        val songs = listOf(
            DesktopSong(
                id = "vidA1234567",
                title = "Hello, \"World\"",
                artists = listOf(ArtistRef("a1", "Artist One"), ArtistRef("a2", "Feat Two")),
                albumName = "Album, Vol. 1",
                duration = 205,
            ),
            DesktopSong(
                id = "vidB7654321",
                title = "Second Song",
                artists = listOf(ArtistRef("a3", "Solo Act")),
                albumName = "Solo",
                duration = 180,
            ),
        )

        val csvFile = File(dir, "export.csv")
        PlaylistTransferService.exportToCsv(csvFile, songs).getOrThrow()
        val preview = PlaylistTransferService.previewCsv(csvFile).getOrThrow()
        assertEquals(2, preview.totalRows)
        assertTrue(preview.headers.contains("Title"))
        assertTrue(preview.headers.contains("YouTube Video ID"))

        val extracted = PlaylistTransferService.extractCsvCandidates(
            content = csvFile.readText(Charsets.UTF_8),
            titleCol = preview.suggestedTitleCol,
            artistCol = preview.suggestedArtistCol,
            urlCol = preview.suggestedUrlCol,
            hasHeader = true,
        )
        assertEquals(2, extracted.size)
        assertEquals("Hello, \"World\"", extracted[0].title)
        assertEquals("vidA1234567", extracted[0].videoId)
        assertEquals("Second Song", extracted[1].title)

        val m3uFile = File(dir, "export.m3u8")
        PlaylistTransferService.exportToM3u(m3uFile, songs).getOrThrow()
        val parsedM3u = PlaylistTransferService.parseM3u(m3uFile.readText())
        assertEquals(2, parsedM3u.size)
        assertEquals("vidA1234567", parsedM3u[0].videoId)
        assertEquals("vidB7654321", parsedM3u[1].videoId)
    }

    @Test
    fun `YouTube URL parser recognizes watch, music, youtu_be, shorts, playlist, album, artist, and podcast links`() {
        assertEquals(
            ParsedYouTubeLink.Video("dQw4w9WgXcQ"),
            YouTubeUrlParser.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&feature=share"),
        )
        assertEquals(
            ParsedYouTubeLink.Video("dQw4w9WgXcQ"),
            YouTubeUrlParser.parse("https://youtu.be/dQw4w9WgXcQ?t=42"),
        )
        assertEquals(
            ParsedYouTubeLink.Video("dQw4w9WgXcQ", "RDAMVM"),
            YouTubeUrlParser.parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDAMVM"),
        )
        assertEquals(
            ParsedYouTubeLink.Video("abc123XYZ_-"),
            YouTubeUrlParser.parse("https://youtube.com/shorts/abc123XYZ_-"),
        )
        assertEquals(
            ParsedYouTubeLink.Playlist("PL1234567890"),
            YouTubeUrlParser.parse("https://music.youtube.com/playlist?list=PL1234567890"),
        )
        assertEquals(
            ParsedYouTubeLink.Album("MPREb_12345"),
            YouTubeUrlParser.parse("https://music.youtube.com/browse/MPREb_12345"),
        )
        assertEquals(
            ParsedYouTubeLink.Artist("UC1234567890abcdef"),
            YouTubeUrlParser.parse("https://music.youtube.com/channel/UC1234567890abcdef"),
        )
        assertEquals(
            ParsedYouTubeLink.Podcast("MPSPP12345"),
            YouTubeUrlParser.parse("https://music.youtube.com/browse/MPSPP12345"),
        )
        assertNull(YouTubeUrlParser.parse("just a normal search query"))
    }

    @Test
    fun `DesktopUpdater compares semantic versions accurately`() {
        assertTrue(DesktopUpdater.compareVersions("13.8.0", "13.7.0") > 0)
        assertTrue(DesktopUpdater.compareVersions("v13.7.1", "13.7.0") > 0)
        assertEquals(0, DesktopUpdater.compareVersions("v13.7.0", "13.7.0"))
        assertTrue(DesktopUpdater.compareVersions("13.6.9", "13.7.0") < 0)
        assertTrue(DesktopUpdater.isUpdateAvailable(currentVersion = "13.7.0", latestVersion = "v13.8.0"))
        assertFalse(DesktopUpdater.isUpdateAvailable(currentVersion = "13.7.0", latestVersion = "13.7.0"))
    }

    @Test
    fun `database v38 parity operations work cleanly without modifying schema`() {
        val dbFile = File(tempFolder.newFolder("db_v38"), "song.db")
        val db = DesktopDatabase(dbFile)

        val song1 = DesktopSong(
            id = "s_old",
            title = "Old Song",
            artists = listOf(ArtistRef("ar1", "Artist Alpha")),
            albumId = "al1",
            albumName = "Album One",
            duration = 200,
        )
        val song2 = DesktopSong(
            id = "s_new",
            title = "New Song",
            artists = listOf(ArtistRef("ar1", "Artist Alpha")),
            albumId = "al1",
            albumName = "Album One",
            duration = 210,
        )
        db.upsertSong(song1)
        db.upsertSong(song2)
        db.recordPlay(song1, 150_000L)
        db.recordPlay(song1, 120_000L)

        // History listing + Time Transfer
        val history = db.historyEntries()
        assertEquals(2, history.size)
        assertTrue(db.transferSongStats("s_old", "s_new"))
        val topSongs = db.mostPlayedSongs(0L)
        assertEquals("s_new", topSongs.first().song.id)
        assertEquals(270_000L, topSongs.first().playTimeMs)

        // Speed Dial pin/unpin
        assertFalse(db.isPinnedToSpeedDial("s_new"))
        val dialItem = DesktopSpeedDialItem(
            id = "s_new",
            title = "New Song",
            subtitle = "Artist Alpha",
            thumbnailUrl = null,
            type = "SONG",
        )
        db.toggleSpeedDial(dialItem)
        assertTrue(db.isPinnedToSpeedDial("s_new"))
        assertEquals(1, db.speedDialItems().size)
        db.toggleSpeedDial(dialItem)
        assertFalse(db.isPinnedToSpeedDial("s_new"))

        // Podcasts bookmark
        val pod = DesktopPodcast(id = "MPSPP99", title = "Tech Talk", author = "Host", thumbnailUrl = null)
        assertTrue(db.togglePodcastBookmark(pod))
        assertTrue(db.isPodcastBookmarked("MPSPP99"))
        assertEquals(1, db.bookmarkedPodcasts().size)

        // Recognition history
        db.addRecognitionHistory(
            DesktopRecognitionHistoryItem(
                trackId = "shz1",
                title = "Detected Song",
                artist = "Band",
                album = "Record",
                coverArtUrl = null,
                genre = "Rock",
                releaseDate = "2024",
                label = null,
                shazamUrl = null,
                appleMusicUrl = null,
                spotifyUrl = null,
                isrc = null,
                youtubeVideoId = "s_new",
                recognizedAt = System.currentTimeMillis(),
            ),
        )
        val recList = db.recognitionHistory()
        assertEquals(1, recList.size)
        db.deleteRecognitionHistory(recList.first().id)
        assertEquals(0, db.recognitionHistory().size)

        // Format info persistence
        db.saveFormat(
            DesktopFormatInfo(
                id = "s_new",
                itag = 251,
                mimeType = "audio/webm",
                codecs = "opus",
                bitrate = 160_000,
                sampleRate = 48_000,
                contentLength = 3_200_000L,
                loudnessDb = -10.5,
                perceptualLoudnessDb = -11.2,
            ),
        )
        val fmt = db.getFormat("s_new")
        assertNotNull(fmt)
        assertEquals(251, fmt!!.itag)
        assertEquals(-10.5, fmt.loudnessDb!!, 0.01)

        // Lyrics offset, romanization toggle, and translation
        db.saveLyrics("s_new", "[00:01.00]Hello", "LrcLib")
        db.saveLyricsTranslation("s_new", "[00:01.00]Hola", "es", "Literal")
        db.setSongLyricsOffset("s_new", 350)
        db.setSongRomanizeLyrics("s_new", false)
        val lyrRec = db.getLyricsRecord("s_new")
        assertEquals("[00:01.00]Hola", lyrRec?.translatedLyrics)
        assertEquals("es", lyrRec?.translationLanguage)
        assertEquals("Literal", lyrRec?.translationMode)
        val reloaded = db.getSong("s_new")!!
        assertEquals(350, reloaded.lyricsOffset)
        assertFalse(reloaded.romanizeLyrics)

        // Song metadata edit & playlist reorder & local search
        db.updateSongMetadata("s_new", "Edited Title", "Edited Artist")
        assertEquals("Edited Title", db.getSong("s_new")!!.title)

        val pl = db.createPlaylist("Reorder Test")
        db.addSongToPlaylist(pl.id, song1)
        db.addSongToPlaylist(pl.id, db.getSong("s_new")!!)
        db.reorderPlaylistSongs(pl.id, 0, 1)
        assertEquals(listOf("s_new", "s_old"), db.playlistSongs(pl.id).map { it.id })

        val localSearch = db.searchLocal("Edited")
        assertTrue(localSearch.songs.any { it.id == "s_new" })
    }

    @Test
    fun `stream proxy detects magic-byte MIME types for cache files and enforces LRU cache limit`() {
        val dlDir = tempFolder.newFolder("proxy_dl")
        val cacheDir = tempFolder.newFolder("proxy_cache")

        val mp4Cache = File(cacheDir, "a.cache").apply {
            writeBytes(byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 0, 0, 0, 0))
        }
        val oggCache = File(cacheDir, "b.cache").apply {
            writeBytes(byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte(), 0, 0, 0, 0))
        }
        val mp3Cache = File(cacheDir, "c.cache").apply {
            writeBytes(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0))
        }
        val webmCache = File(cacheDir, "d.cache").apply {
            writeBytes(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0, 0, 0, 0))
        }

        assertEquals("audio/mp4", StreamProxyServer.detectMimeType(mp4Cache))
        assertEquals("audio/ogg", StreamProxyServer.detectMimeType(oggCache))
        assertEquals("audio/mpeg", StreamProxyServer.detectMimeType(mp3Cache))
        assertEquals("audio/webm", StreamProxyServer.detectMimeType(webmCache))

        // LRU eviction: 1 MB limit with two 700 KB files -> oldest deleted
        val lruDir = tempFolder.newFolder("proxy_lru")
        val oldFile = File(lruDir, "old.cache").apply {
            writeBytes(ByteArray(700 * 1024))
            setLastModified(System.currentTimeMillis() - 60_000L)
        }
        val newFile = File(lruDir, "new.cache").apply {
            writeBytes(ByteArray(700 * 1024))
            setLastModified(System.currentTimeMillis())
        }
        val proxy = StreamProxyServer(
            resolver = InnerTubeStreamResolver(),
            qualityProvider = { DesktopAudioQuality.HIGH },
            downloadsDirProvider = { dlDir },
            cacheEnabledProvider = { true },
            cacheDirProvider = { lruDir },
            maxCacheSizeMbProvider = { 1 },
        )
        try {
            proxy.enforceCacheLimit()
            assertFalse("Oldest cache file should be evicted", oldFile.exists())
            assertTrue("Newest cache file should remain", newFile.exists())
            assertEquals(1, proxy.clearSongCache())
            assertFalse(newFile.exists())
        } finally {
            proxy.close()
        }
    }

    @Test
    fun `Listen Together Proto3 codec handles capabilities, queue, volume, ping, and track suggestions`() {
        val caps = ListenTogetherProtoCodec.encodeClientCapabilities("13.7.0")
        assertTrue(caps.isNotEmpty())

        val q1 = LtTrackInfo("id1", "Track 1", "Artist 1", "Album 1", 180_000L, "")
        val q2 = LtTrackInfo("id2", "Track 2", "Artist 2", "Album 2", 200_000L, "", suggestedBy = "Alice")
        val actionBytes = ListenTogetherProtoCodec.encodePlaybackAction(
            action = "sync_queue",
            queue = listOf(q1, q2),
            volume = 0.75f,
            revision = 42L,
        )
        val decoded = ListenTogetherProtoCodec.decodePlaybackAction(actionBytes)
        assertEquals("sync_queue", decoded.action)
        assertEquals(2, decoded.queue.size)
        assertEquals("Alice", decoded.queue[1].suggestedBy)
        assertEquals(0.75f, decoded.volume, 0.01f)

        // TrackInfo round-trip preserves suggestedBy
        val trackBytes = ListenTogetherProtoCodec.encodeTrackInfo(q2)
        val decodedTrack = ListenTogetherProtoCodec.decodeTrackInfo(trackBytes)
        assertEquals(q2, decodedTrack)

        // Envelope compression + decompression round-trip
        val envelope = ListenTogetherProtoCodec.encodeEnvelope("sync_playback", actionBytes, compress = true)
        val (envType, envPayload) = ListenTogetherProtoCodec.decodeEnvelope(envelope)
        assertEquals("sync_playback", envType)
        assertEquals(2, ListenTogetherProtoCodec.decodePlaybackAction(envPayload).queue.size)

        // Ping + SuggestTrack encoders produce non-empty valid wire bytes
        assertTrue(ListenTogetherProtoCodec.encodePing(1000L, 5L).isNotEmpty())
        assertTrue(ListenTogetherProtoCodec.encodeSuggestTrack(q2).isNotEmpty())
    }

    @Test
    fun `backup preview inspects DB metadata and round-trips artist aliases`() = runBlocking {
        val dir = tempFolder.newFolder("backup_parity")
        val dbFile = File(dir, "song.db")
        val prefsFile = File(dir, "settings.preferences_pb")
        val backupFile = File(dir, "test.backup")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val previousAliases = ArtistAliasStore.aliases.value
        try {
            val db = DesktopDatabase(dbFile)
            val prefs = DesktopPreferences(scope, prefsFile)
            prefs.set(DesktopPrefKeys.DynamicTheme, false)
            val service = BackupRestoreService(db, prefs, dbFile, prefsFile)

            db.upsertSong(DesktopSong(id = "bk1", title = "Backup Track"))
            db.createPlaylist("Backup Playlist")

            ArtistAliasStore.restore(emptyMap())
            ArtistAliasStore.setAlias("ar_alias", null, "Orig", "Custom Alias")
            assertEquals("Custom Alias", ArtistAliasStore.resolve("ar_alias", "Orig"))

            service.exportBackup(backupFile).getOrThrow()

            val preview = service.previewBackup(backupFile).getOrThrow()
            assertTrue(preview.hasDatabase)
            assertTrue(preview.hasSettings)
            assertTrue(preview.hasAliases)
            assertTrue(preview.compatible)
            assertEquals(38, preview.dbVersion)
            assertEquals(1, preview.songCount)
            assertEquals(1, preview.playlistCount)

            // Wipe aliases and restore from backup
            ArtistAliasStore.restore(emptyMap())
            assertEquals("Orig", ArtistAliasStore.resolve("ar_alias", "Orig"))

            service.importBackup(backupFile).getOrThrow()
            assertEquals("Custom Alias", ArtistAliasStore.resolve("ar_alias", "Orig"))
            assertEquals("Backup Track", db.getSong("bk1")?.title)
        } finally {
            ArtistAliasStore.restore(previousAliases)
            scope.cancel()
        }
    }

    @Test
    fun `lyrics share card and wrapped card renderers produce valid PNG images`() {
        val dir = tempFolder.newFolder("cards")
        val lyricsPng = File(dir, "lyrics_card.png")
        val lyricsImg = DesktopLyricsCardRenderer.renderLyricsCard(
            lines = listOf("First memorable line", "Second memorable line"),
            title = "Starlight",
            artist = "Synth Band",
            thumbnailUrl = null,
            accentColorArgb = 0xFF6750A4.toInt(),
        )
        DesktopLyricsCardRenderer.savePng(lyricsImg, lyricsPng).getOrThrow()
        assertTrue(lyricsPng.exists() && lyricsPng.length() > 1000L)
        val img1 = ImageIO.read(lyricsPng)
        assertEquals(1080, img1.width)
        assertEquals(1350, img1.height)

        val wrappedPng = File(dir, "wrapped_card.png")
        val wrappedImg = DesktopLyricsCardRenderer.renderWrappedCard(
            yearLabel = "2026",
            totalMinutes = 12_345L,
            topSong = "Starlight — Synth Band",
            topArtist = "Synth Band",
            topSongsList = listOf("Second Track", "Third Track"),
            accentColorArgb = 0xFF1DB954.toInt(),
        )
        DesktopLyricsCardRenderer.savePng(wrappedImg, wrappedPng).getOrThrow()
        assertTrue(wrappedPng.exists() && wrappedPng.length() > 1000L)
        val img2 = ImageIO.read(wrappedPng)
        assertEquals(1080, img2.width)
        assertEquals(1350, img2.height)
    }
}
