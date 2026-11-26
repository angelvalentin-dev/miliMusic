package com.metrolist.desktop.integrations

import com.metrolist.desktop.audio.DesktopAudioPlayer
import com.metrolist.desktop.data.DesktopSettings
import com.metrolist.desktop.db.ArtistRef
import com.metrolist.desktop.db.DesktopSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object ListenTogetherPresetServers {
    val servers = listOf(
        "Metroserverx (Europa)" to "wss://metroserverx.meowery.eu/ws",
        "Metrolist Official" to "wss://metroserver.meowery.eu/ws",
        "Localhost (Desarrollo)" to "ws://127.0.0.1:8080/ws",
    )
}

data class LtTrackInfo(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val duration: Long = 0L,
    val thumbnail: String = "",
    val suggestedBy: String = "",
) {
    fun toDesktopSong(): DesktopSong =
        DesktopSong(
            id = id,
            title = title,
            duration = (duration / 1000L).toInt().takeIf { it > 0 } ?: duration.toInt(),
            thumbnailUrl = thumbnail.ifBlank { null },
            albumName = album.ifBlank { null },
            artists = listOf(ArtistRef("lt_$id", artist.ifBlank { "Artista" })),
        )
}

fun DesktopSong.toLtTrackInfo(suggestedBy: String = ""): LtTrackInfo =
    LtTrackInfo(
        id = id,
        title = title,
        artist = artistDisplay,
        album = albumName.orEmpty(),
        duration = (duration.coerceAtLeast(0)) * 1000L,
        thumbnail = thumbnailUrl.orEmpty(),
        suggestedBy = suggestedBy,
    )

data class LtUserInfo(
    val userId: String = "",
    val username: String = "",
    val isHost: Boolean = false,
    val isConnected: Boolean = true,
)

data class LtJoinRequest(
    val userId: String,
    val username: String,
)

data class LtSuggestion(
    val suggestionId: String,
    val fromUserId: String,
    val fromUsername: String,
    val trackInfo: LtTrackInfo,
)

data class LtState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val roomCode: String = "",
    val userId: String = "",
    val sessionToken: String = "",
    val isHost: Boolean = false,
    val users: List<LtUserInfo> = emptyList(),
    val pendingJoinRequests: List<LtJoinRequest> = emptyList(),
    val pendingSuggestions: List<LtSuggestion> = emptyList(),
    val clockOffsetMs: Long = 0L,
    val statusText: String = "Desconectado",
    val lastError: String? = null,
)

/** Full Proto3 wire codec compatible with Android listentogether.proto & metroserverx. */
object ListenTogetherProtoCodec {
    private class ProtoWriter {
        private val out = ByteArrayOutputStream()

        fun writeVarint(value: Long) {
            var v = value
            while (true) {
                if ((v and 0x7FL.inv()) == 0L) {
                    out.write(v.toInt())
                    return
                }
                out.write(((v.toInt() and 0x7F) or 0x80))
                v = v ushr 7
            }
        }

        fun writeTag(fieldNumber: Int, wireType: Int) {
            writeVarint(((fieldNumber shl 3) or wireType).toLong())
        }

        fun writeString(fieldNumber: Int, value: String) {
            if (value.isEmpty()) return
            writeBytes(fieldNumber, value.toByteArray(Charsets.UTF_8))
        }

        fun writeBytes(fieldNumber: Int, value: ByteArray) {
            if (value.isEmpty()) return
            writeTag(fieldNumber, 2)
            writeVarint(value.size.toLong())
            out.write(value)
        }

        fun writeBool(fieldNumber: Int, value: Boolean) {
            if (!value) return
            writeTag(fieldNumber, 0)
            writeVarint(1L)
        }

        fun writeInt64(fieldNumber: Int, value: Long) {
            if (value == 0L) return
            writeTag(fieldNumber, 0)
            writeVarint(value)
        }

        fun writeFloat(fieldNumber: Int, value: Float) {
            if (value == 0f) return
            writeTag(fieldNumber, 5)
            val bits = value.toRawBits()
            out.write(bits and 0xFF)
            out.write((bits ushr 8) and 0xFF)
            out.write((bits ushr 16) and 0xFF)
            out.write((bits ushr 24) and 0xFF)
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private class ProtoReader(private val bytes: ByteArray) {
        private var pos = 0

        val hasRemaining: Boolean get() = pos < bytes.size

        fun readTag(): Pair<Int, Int>? {
            if (!hasRemaining) return null
            val tag = readVarint().toInt()
            if (tag == 0) return null
            return (tag ushr 3) to (tag and 0x07)
        }

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (pos < bytes.size && shift < 64) {
                val b = bytes[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            return result
        }

        fun readBytes(): ByteArray {
            val len = readVarint().toInt().coerceIn(0, bytes.size - pos)
            val slice = bytes.copyOfRange(pos, pos + len)
            pos += len
            return slice
        }

        fun readString(): String = String(readBytes(), Charsets.UTF_8)

        fun readBool(): Boolean = readVarint() != 0L

        fun readFloat(): Float {
            if (pos + 4 > bytes.size) {
                pos = bytes.size
                return 0f
            }
            val bits = (bytes[pos].toInt() and 0xFF) or
                ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return Float.fromBits(bits)
        }

        fun skipField(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> pos = (pos + 8).coerceAtMost(bytes.size)
                2 -> readBytes()
                5 -> pos = (pos + 4).coerceAtMost(bytes.size)
            }
        }
    }

    fun encodeEnvelope(type: String, payloadBytes: ByteArray, compress: Boolean = true): ByteArray {
        var body = payloadBytes
        var compressed = false
        if (compress && body.size > 100) {
            val gz = ByteArrayOutputStream().use { bos ->
                GZIPOutputStream(bos).use { it.write(body) }
                bos.toByteArray()
            }
            if (gz.size < body.size) {
                body = gz
                compressed = true
            }
        }
        val w = ProtoWriter()
        w.writeString(1, type)
        w.writeBytes(2, body)
        w.writeBool(3, compressed)
        return w.toByteArray()
    }

    fun decodeEnvelope(raw: ByteArray): Pair<String, ByteArray> {
        val r = ProtoReader(raw)
        var type = ""
        var payload = ByteArray(0)
        var compressed = false
        while (r.hasRemaining) {
            val (field, wire) = r.readTag() ?: break
            when {
                field == 1 && wire == 2 -> type = r.readString()
                field == 2 && wire == 2 -> payload = r.readBytes()
                field == 3 && wire == 0 -> compressed = r.readBool()
                else -> r.skipField(wire)
            }
        }
        val finalPayload = if (compressed && payload.isNotEmpty()) {
            runCatching { GZIPInputStream(ByteArrayInputStream(payload)).use { it.readBytes() } }.getOrDefault(payload)
        } else payload
        return type to finalPayload
    }

    fun encodeClientCapabilities(clientVersion: String = "13.7.0"): ByteArray =
        ProtoWriter().apply {
            writeBool(1, true)
            writeBool(2, true)
            writeString(3, clientVersion)
        }.toByteArray()

    fun encodeCreateRoom(username: String): ByteArray =
        ProtoWriter().apply { writeString(1, username) }.toByteArray()

    fun encodeJoinRoom(roomCode: String, username: String): ByteArray =
        ProtoWriter().apply {
            writeString(1, roomCode)
            writeString(2, username)
        }.toByteArray()

    fun encodeUserIdOnly(userId: String): ByteArray =
        ProtoWriter().apply { writeString(1, userId) }.toByteArray()

    fun encodeTwoStrings(first: String, second: String): ByteArray =
        ProtoWriter().apply {
            writeString(1, first)
            writeString(2, second)
        }.toByteArray()

    fun encodeSuggestTrack(track: LtTrackInfo): ByteArray =
        ProtoWriter().apply { writeBytes(1, encodeTrackInfo(track)) }.toByteArray()

    fun encodePing(clientTime: Long, seq: Long): ByteArray =
        ProtoWriter().apply {
            writeInt64(1, clientTime)
            writeInt64(2, seq)
        }.toByteArray()

    data class DecodedPong(val clientTime: Long, val serverReceiveTime: Long, val serverSendTime: Long, val sequence: Long)

    fun decodePong(bytes: ByteArray): DecodedPong {
        val r = ProtoReader(bytes)
        var ct = 0L; var srt = 0L; var sst = 0L; var seq = 0L
        while (r.hasRemaining) {
            val (field, wire) = r.readTag() ?: break
            when {
                field == 1 && wire == 0 -> ct = r.readVarint()
                field == 2 && wire == 0 -> srt = r.readVarint()
                field == 3 && wire == 0 -> sst = r.readVarint()
                field == 4 && wire == 0 -> seq = r.readVarint()
                else -> r.skipField(wire)
            }
        }
        return DecodedPong(ct, srt, sst, seq)
    }

    fun encodeTrackInfo(track: LtTrackInfo): ByteArray =
        ProtoWriter().apply {
            writeString(1, track.id)
            writeString(2, track.title)
            writeString(3, track.artist)
            writeString(4, track.album)
            writeInt64(5, track.duration)
            writeString(6, track.thumbnail)
            writeString(7, track.suggestedBy)
        }.toByteArray()

    fun decodeTrackInfo(bytes: ByteArray): LtTrackInfo {
        val r = ProtoReader(bytes)
        var id = ""; var title = ""; var artist = ""; var album = ""; var duration = 0L; var thumb = ""; var suggestedBy = ""
        while (r.hasRemaining) {
            val (field, wire) = r.readTag() ?: break
            when {
                field == 1 && wire == 2 -> id = r.readString()
                field == 2 && wire == 2 -> title = r.readString()
                field == 3 && wire == 2 -> artist = r.readString()
                field == 4 && wire == 2 -> album = r.readString()
                field == 5 && wire == 0 -> duration = r.readVarint()
                field == 6 && wire == 2 -> thumb = r.readString()
                field == 7 && wire == 2 -> suggestedBy = r.readString()
                else -> r.skipField(wire)
            }
        }
        return LtTrackInfo(id, title, artist, album, duration, thumb, suggestedBy)
    }

    fun decodeUserInfo(bytes: ByteArray): LtUserInfo {
        val r = ProtoReader(bytes)
        var userId = ""; var username = ""; var isHost = false; var isConnected = true
        while (r.hasRemaining) {
            val (field, wire) = r.readTag() ?: break
            when {
                field == 1 && wire == 2 -> userId = r.readString()
                field == 2 && wire == 2 -> username = r.readString()
                field == 3 && wire == 0 -> isHost = r.readBool()
                field == 4 && wire == 0 -> isConnected = r.readBool()
                else -> r.skipField(wire)
            }
        }
        return LtUserInfo(userId, username, isHost, isConnected)
    }

    fun encodePlaybackAction(
        action: String,
        trackId: String = "",
        positionMs: Long = 0L,
        trackInfo: LtTrackInfo? = null,
        queue: List<LtTrackInfo> = emptyList(),
        volume: Float = 1f,
        revision: Long = 1L,
    ): ByteArray = ProtoWriter().apply {
        writeString(1, action)
        writeString(2, trackId)
        writeInt64(3, positionMs)
        if (trackInfo != null) writeBytes(4, encodeTrackInfo(trackInfo))
        queue.forEach { writeBytes(6, encodeTrackInfo(it)) }
        writeFloat(8, volume)
        writeInt64(10, revision)
    }.toByteArray()

    data class DecodedPlaybackAction(
        val action: String,
        val trackId: String,
        val positionMs: Long,
        val trackInfo: LtTrackInfo?,
        val queue: List<LtTrackInfo> = emptyList(),
        val volume: Float = 0f,
    )

    fun decodePlaybackAction(bytes: ByteArray): DecodedPlaybackAction {
        val r = ProtoReader(bytes)
        var action = ""; var trackId = ""; var position = 0L; var track: LtTrackInfo? = null; var volume = 0f
        val queue = mutableListOf<LtTrackInfo>()
        while (r.hasRemaining) {
            val (field, wire) = r.readTag() ?: break
            when {
                field == 1 && wire == 2 -> action = r.readString()
                field == 2 && wire == 2 -> trackId = r.readString()
                field == 3 && wire == 0 -> position = r.readVarint()
                field == 4 && wire == 2 -> track = decodeTrackInfo(r.readBytes())
                field == 6 && wire == 2 -> queue += decodeTrackInfo(r.readBytes())
                field == 8 && wire == 5 -> volume = r.readFloat()
                else -> r.skipField(wire)
            }
        }
        return DecodedPlaybackAction(action, trackId, position, track, queue, volume)
    }

    data class DecodedRoomState(
        val roomCode: String,
        val hostId: String,
        val users: List<LtUserInfo>,
        val currentTrack: LtTrackInfo?,
        val isPlaying: Boolean,
        val positionMs: Long,
        val volume: Float = 1f,
        val queue: List<LtTrackInfo> = emptyList(),
    )

    fun decodeRoomState(bytes: ByteArray): DecodedRoomState {
        val r = ProtoReader(bytes)
        var roomCode = ""; var hostId = ""; var currentTrack: LtTrackInfo? = null
        var isPlaying = false; var position = 0L; var volume = 1f
        val users = mutableListOf<LtUserInfo>()
        val queue = mutableListOf<LtTrackInfo>()
        while (r.hasRemaining) {
            val (field, wire) = r.readTag() ?: break
            when {
                field == 1 && wire == 2 -> roomCode = r.readString()
                field == 2 && wire == 2 -> hostId = r.readString()
                field == 3 && wire == 2 -> users.add(decodeUserInfo(r.readBytes()))
                field == 4 && wire == 2 -> currentTrack = decodeTrackInfo(r.readBytes())
                field == 5 && wire == 0 -> isPlaying = r.readBool()
                field == 6 && wire == 0 -> position = r.readVarint()
                field == 8 && wire == 5 -> volume = r.readFloat()
                field == 9 && wire == 2 -> queue.add(decodeTrackInfo(r.readBytes()))
                else -> r.skipField(wire)
            }
        }
        return DecodedRoomState(roomCode, hostId, users, currentTrack, isPlaying, position, volume, queue)
    }

    data class DecodedSyncState(
        val currentTrack: LtTrackInfo?,
        val isPlaying: Boolean,
        val positionMs: Long,
        val queue: List<LtTrackInfo>,
        val volume: Float,
    )

    fun decodeSyncState(bytes: ByteArray): DecodedSyncState {
        val r = ProtoReader(bytes)
        var track: LtTrackInfo? = null; var isPlaying = false; var pos = 0L; var vol = 1f
        val queue = mutableListOf<LtTrackInfo>()
        while (r.hasRemaining) {
            val (field, wire) = r.readTag() ?: break
            when {
                field == 1 && wire == 2 -> track = decodeTrackInfo(r.readBytes())
                field == 2 && wire == 0 -> isPlaying = r.readBool()
                field == 3 && wire == 0 -> pos = r.readVarint()
                field == 5 && wire == 2 -> queue += decodeTrackInfo(r.readBytes())
                field == 6 && wire == 5 -> vol = r.readFloat()
                else -> r.skipField(wire)
            }
        }
        return DecodedSyncState(track, isPlaying, pos, queue, vol)
    }

    fun decodeSuggestionReceived(bytes: ByteArray): LtSuggestion {
        val r = ProtoReader(bytes)
        var sid = ""; var uid = ""; var uname = ""; var track = LtTrackInfo()
        while (r.hasRemaining) {
            val (field, wire) = r.readTag() ?: break
            when {
                field == 1 && wire == 2 -> sid = r.readString()
                field == 2 && wire == 2 -> uid = r.readString()
                field == 3 && wire == 2 -> uname = r.readString()
                field == 4 && wire == 2 -> track = decodeTrackInfo(r.readBytes())
                else -> r.skipField(wire)
            }
        }
        return LtSuggestion(sid, uid, uname, track)
    }

    data class DecodedStringsAndState(val f1: String, val f2: String, val f3: String, val f4Bytes: ByteArray, val f4Bool: Boolean)

    fun decodeFourFields(bytes: ByteArray): DecodedStringsAndState {
        val r = ProtoReader(bytes)
        var f1 = ""; var f2 = ""; var f3 = ""; var f4Bytes = ByteArray(0); var f4Bool = false
        while (r.hasRemaining) {
            val (field, wire) = r.readTag() ?: break
            when {
                field == 1 && wire == 2 -> f1 = r.readString()
                field == 2 && wire == 2 -> f2 = r.readString()
                field == 3 && wire == 2 -> f3 = r.readString()
                field == 4 && wire == 2 -> f4Bytes = r.readBytes()
                field == 4 && wire == 0 -> f4Bool = r.readBool()
                else -> r.skipField(wire)
            }
        }
        return DecodedStringsAndState(f1, f2, f3, f4Bytes, f4Bool)
    }
}

class DesktopListenTogetherManager(
    private val audioPlayer: DesktopAudioPlayer,
    private val settingsProvider: () -> DesktopSettings = { DesktopSettings() },
    private val onBlockUsername: (String) -> Unit = {},
    private val onStatusMessage: (String) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _state = MutableStateFlow(LtState())
    val state: StateFlow<LtState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var lastServerUrl: String = "wss://metroserverx.meowery.eu/ws"
    private val pingSeq = AtomicLong(0L)
    private val revisionCounter = AtomicLong(1L)
    @Volatile
    private var applyingRemoteSync: Boolean = false
    @Volatile
    private var manualDisconnect: Boolean = false

    private fun appendLog(msg: String) {
        val ts = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        _logs.update { (it + "[$ts] $msg").takeLast(120) }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun createRoom(serverUrl: String, username: String) {
        val cleanName = username.trim().ifBlank { "Usuario Windows" }
        manualDisconnect = false
        connectAndSend(serverUrl) {
            sendEnvelope("create_room", ListenTogetherProtoCodec.encodeCreateRoom(cleanName))
        }
    }

    fun joinRoom(serverUrl: String, roomCode: String, username: String) {
        val cleanCode = roomCode.trim().uppercase()
        val cleanName = username.trim().ifBlank { "Usuario Windows" }
        if (cleanCode.isBlank()) {
            onStatusMessage("Ingresa un código de sala válido")
            return
        }
        manualDisconnect = false
        connectAndSend(serverUrl) {
            sendEnvelope("join_room", ListenTogetherProtoCodec.encodeJoinRoom(cleanCode, cleanName))
        }
    }

    fun approveJoin(userId: String) {
        sendEnvelope("approve_join", ListenTogetherProtoCodec.encodeUserIdOnly(userId))
        _state.update { st -> st.copy(pendingJoinRequests = st.pendingJoinRequests.filterNot { it.userId == userId }) }
        appendLog("Solicitud aprobada: $userId")
    }

    fun rejectJoin(userId: String, reason: String = "") {
        sendEnvelope("reject_join", ListenTogetherProtoCodec.encodeTwoStrings(userId, reason))
        _state.update { st -> st.copy(pendingJoinRequests = st.pendingJoinRequests.filterNot { it.userId == userId }) }
        appendLog("Solicitud rechazada: $userId")
    }

    fun kickUser(userId: String, username: String, permanentBlock: Boolean = false) {
        sendEnvelope("kick_user", ListenTogetherProtoCodec.encodeTwoStrings(userId, if (permanentBlock) "Bloqueado" else "Expulsado"))
        if (permanentBlock && username.isNotBlank()) {
            onBlockUsername(username)
        }
        _state.update { st -> st.copy(users = st.users.filterNot { it.userId == userId }) }
        appendLog("Usuario expulsado: $username (bloqueo=$permanentBlock)")
    }

    fun transferHost(newHostId: String) {
        sendEnvelope("transfer_host", ListenTogetherProtoCodec.encodeUserIdOnly(newHostId))
        appendLog("Transfiriendo host a $newHostId")
    }

    fun suggestTrack(song: DesktopSong) {
        val st = _state.value
        if (!st.connected || st.roomCode.isBlank()) {
            onStatusMessage("Únete primero a una sala de Escuchar Juntos")
            return
        }
        if (st.isHost) {
            audioPlayer.addToQueue(song)
            onStatusMessage("Añadido a la cola de la sala: ${song.title}")
            return
        }
        sendEnvelope("suggest_track", ListenTogetherProtoCodec.encodeSuggestTrack(song.toLtTrackInfo()))
        onStatusMessage("Sugerencia enviada al host: ${song.title}")
        appendLog("Sugerencia enviada: ${song.title}")
    }

    fun approveSuggestion(suggestion: LtSuggestion) {
        sendEnvelope("approve_suggestion", ListenTogetherProtoCodec.encodeUserIdOnly(suggestion.suggestionId))
        _state.update { st -> st.copy(pendingSuggestions = st.pendingSuggestions.filterNot { it.suggestionId == suggestion.suggestionId }) }
        audioPlayer.addToQueue(suggestion.trackInfo.toDesktopSong())
        appendLog("Sugerencia aprobada: ${suggestion.trackInfo.title}")
    }

    fun rejectSuggestion(suggestionId: String, reason: String = "") {
        sendEnvelope("reject_suggestion", ListenTogetherProtoCodec.encodeTwoStrings(suggestionId, reason))
        _state.update { st -> st.copy(pendingSuggestions = st.pendingSuggestions.filterNot { it.suggestionId == suggestionId }) }
        appendLog("Sugerencia rechazada: $suggestionId")
    }

    fun requestResync() {
        val st = _state.value
        if (!st.connected || st.isHost) return
        sendEnvelope("request_sync", ByteArray(0))
        appendLog("Resincronización solicitada")
    }

    fun leaveRoom() {
        manualDisconnect = true
        runCatching { sendEnvelope("leave_room", ByteArray(0)) }
        disconnect()
    }

    fun broadcastHostPlayback(song: DesktopSong?, isPlaying: Boolean, positionMs: Long, trackChanged: Boolean) {
        val st = _state.value
        if (!st.connected || !st.isHost || applyingRemoteSync || song == null) return
        val rev = revisionCounter.incrementAndGet()
        val vol = audioPlayer.state.value.volume
        if (trackChanged) {
            sendEnvelope(
                "playback_action",
                ListenTogetherProtoCodec.encodePlaybackAction(
                    action = "change_track",
                    trackId = song.id,
                    positionMs = 0L,
                    trackInfo = song.toLtTrackInfo(),
                    volume = vol,
                    revision = rev,
                ),
            )
        }
        val act = if (isPlaying) "play" else "pause"
        sendEnvelope(
            "playback_action",
            ListenTogetherProtoCodec.encodePlaybackAction(
                action = act,
                trackId = song.id,
                positionMs = positionMs,
                trackInfo = song.toLtTrackInfo(),
                volume = vol,
                revision = rev,
            ),
        )
    }

    fun broadcastHostSeek(positionMs: Long) {
        val st = _state.value
        val song = audioPlayer.state.value.currentSong ?: return
        if (!st.connected || !st.isHost || applyingRemoteSync) return
        val rev = revisionCounter.incrementAndGet()
        sendEnvelope(
            "playback_action",
            ListenTogetherProtoCodec.encodePlaybackAction(
                action = "seek",
                trackId = song.id,
                positionMs = positionMs,
                trackInfo = song.toLtTrackInfo(),
                revision = rev,
            ),
        )
    }

    fun broadcastHostQueue(queue: List<DesktopSong>) {
        val st = _state.value
        val current = audioPlayer.state.value.currentSong ?: return
        if (!st.connected || !st.isHost || applyingRemoteSync) return
        val rev = revisionCounter.incrementAndGet()
        sendEnvelope(
            "playback_action",
            ListenTogetherProtoCodec.encodePlaybackAction(
                action = "sync_queue",
                trackId = current.id,
                positionMs = audioPlayer.state.value.positionMs,
                trackInfo = current.toLtTrackInfo(),
                queue = queue.take(50).map { it.toLtTrackInfo() },
                revision = rev,
            ),
        )
    }

    fun broadcastHostVolume(volume: Float) {
        val st = _state.value
        if (!st.connected || !st.isHost || applyingRemoteSync || !settingsProvider().listenTogetherSyncVolume) return
        sendEnvelope(
            "playback_action",
            ListenTogetherProtoCodec.encodePlaybackAction(
                action = "volume",
                volume = volume,
                revision = revisionCounter.incrementAndGet(),
            ),
        )
    }

    private fun connectAndSend(serverUrl: String, onConnected: () -> Unit) {
        disconnect(clearSession = false)
        val targetUrl = serverUrl.trim().ifBlank { "wss://metroserverx.meowery.eu/ws" }
        lastServerUrl = targetUrl
        _state.update { it.copy(connecting = true, statusText = "Conectando...", lastError = null) }
        appendLog("Conectando a $targetUrl")
        val req = Request.Builder()
            .url(targetUrl)
            .header("User-Agent", "com.metrolist.music")
            .build()
        webSocket = httpClient.newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _state.update { it.copy(connected = true, connecting = false, statusText = "Conectado al servidor") }
                    sendEnvelope("client_capabilities", ListenTogetherProtoCodec.encodeClientCapabilities())
                    startPingLoop()
                    onConnected()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleBinaryMessage(bytes.toByteArray())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val token = _state.value.sessionToken
                    appendLog("Fallo WebSocket: ${t.message}")
                    _state.update {
                        it.copy(
                            connected = false,
                            connecting = false,
                            statusText = "Desconectado (error)",
                            lastError = t.message ?: "No se pudo conectar al servidor Listen Together",
                        )
                    }
                    if (!manualDisconnect && token.isNotBlank()) {
                        scheduleReconnect(token)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    val token = _state.value.sessionToken
                    appendLog("Conexión cerrada ($code): $reason")
                    if (!manualDisconnect && token.isNotBlank()) {
                        _state.update { it.copy(connected = false, statusText = "Reconectando...") }
                        scheduleReconnect(token)
                    } else {
                        _state.value = LtState(statusText = "Desconectado")
                    }
                }
            },
        )
    }

    private fun scheduleReconnect(sessionToken: String) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(3_000L)
            if (manualDisconnect) return@launch
            appendLog("Intentando reconexión con token de sesión")
            connectAndSend(lastServerUrl) {
                sendEnvelope("reconnect", ListenTogetherProtoCodec.encodeUserIdOnly(sessionToken))
            }
        }
    }

    private fun sendEnvelope(type: String, payload: ByteArray) {
        val encoded = ListenTogetherProtoCodec.encodeEnvelope(type, payload)
        webSocket?.send(encoded.toByteString())
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(20_000L)
                val payload = ListenTogetherProtoCodec.encodePing(System.currentTimeMillis(), pingSeq.incrementAndGet())
                runCatching { sendEnvelope("ping", payload) }
            }
        }
    }

    private fun handleBinaryMessage(raw: ByteArray) {
        val (type, payload) = runCatching { ListenTogetherProtoCodec.decodeEnvelope(raw) }.getOrNull() ?: return
        when (type) {
            "pong" -> {
                val pong = ListenTogetherProtoCodec.decodePong(payload)
                val now = System.currentTimeMillis()
                val offset = ((pong.serverReceiveTime - pong.clientTime) + (pong.serverSendTime - now)) / 2L
                _state.update { it.copy(clockOffsetMs = offset) }
            }
            "room_created" -> {
                val f = ListenTogetherProtoCodec.decodeFourFields(payload)
                _state.update {
                    it.copy(
                        roomCode = f.f1,
                        userId = f.f2,
                        sessionToken = f.f3,
                        isHost = true,
                        users = listOf(LtUserInfo(f.f2, "Tú (Host)", isHost = true)),
                        statusText = "Sala creada: ${f.f1}",
                    )
                }
                appendLog("Sala creada: ${f.f1}")
                onStatusMessage("Sala Escuchar Juntos creada: ${f.f1}")
            }
            "join_request" -> {
                val f = ListenTogetherProtoCodec.decodeFourFields(payload)
                val cfg = settingsProvider()
                if (cfg.listenTogetherBlockedUsers.any { it.equals(f.f2, ignoreCase = true) }) {
                    rejectJoin(f.f1, "Bloqueado")
                    return
                }
                if (cfg.listenTogetherAutoApproval) {
                    approveJoin(f.f1)
                    onStatusMessage("${f.f2} aprobado automáticamente")
                } else {
                    _state.update { st ->
                        st.copy(pendingJoinRequests = (st.pendingJoinRequests + LtJoinRequest(f.f1, f.f2)).distinctBy { it.userId })
                    }
                    onStatusMessage("${f.f2} solicita unirse a la sala")
                }
            }
            "join_approved" -> {
                val f = ListenTogetherProtoCodec.decodeFourFields(payload)
                val roomState = if (f.f4Bytes.isNotEmpty()) ListenTogetherProtoCodec.decodeRoomState(f.f4Bytes) else null
                _state.update {
                    it.copy(
                        roomCode = f.f1,
                        userId = f.f2,
                        sessionToken = f.f3,
                        isHost = false,
                        users = roomState?.users.orEmpty(),
                        statusText = "Unido a sala ${f.f1}",
                    )
                }
                appendLog("Unido a sala ${f.f1}")
                onStatusMessage("Te uniste a la sala ${f.f1}")
                if (roomState?.currentTrack != null && roomState.currentTrack.id.isNotBlank()) {
                    applyRemoteTrackAndState(roomState.currentTrack, roomState.isPlaying, roomState.positionMs)
                }
            }
            "reconnected" -> {
                val f = ListenTogetherProtoCodec.decodeFourFields(payload)
                val roomState = if (f.f3.isNotEmpty()) ListenTogetherProtoCodec.decodeRoomState(f.f3.toByteArray(Charsets.ISO_8859_1)) else null
                _state.update {
                    it.copy(
                        roomCode = f.f1,
                        userId = f.f2,
                        isHost = f.f4Bool,
                        users = roomState?.users ?: it.users,
                        statusText = "Reconectado a sala ${f.f1}",
                    )
                }
                appendLog("Reconectado a sala ${f.f1}")
            }
            "join_rejected" -> {
                val f = ListenTogetherProtoCodec.decodeFourFields(payload)
                onStatusMessage("Solicitud rechazada: ${f.f1.ifBlank { "El host rechazó la solicitud" }}")
                manualDisconnect = true
                disconnect()
            }
            "kicked" -> {
                val f = ListenTogetherProtoCodec.decodeFourFields(payload)
                onStatusMessage("Expulsado de la sala: ${f.f1.ifBlank { "por el host" }}")
                manualDisconnect = true
                disconnect()
            }
            "host_changed" -> {
                val f = ListenTogetherProtoCodec.decodeFourFields(payload)
                _state.update { st ->
                    val amHost = st.userId == f.f1
                    st.copy(
                        isHost = amHost,
                        users = st.users.map { it.copy(isHost = it.userId == f.f1) },
                        statusText = if (amHost) "Ahora eres el host de ${st.roomCode}" else "Nuevo host: ${f.f2}",
                    )
                }
                onStatusMessage("Nuevo host de la sala: ${f.f2}")
            }
            "user_joined" -> {
                val f = ListenTogetherProtoCodec.decodeFourFields(payload)
                _state.update { st ->
                    st.copy(users = (st.users + LtUserInfo(f.f1, f.f2, isHost = false)).distinctBy { it.userId })
                }
                onStatusMessage("${f.f2} se unió a la sala")
            }
            "user_left" -> {
                val f = ListenTogetherProtoCodec.decodeFourFields(payload)
                _state.update { st -> st.copy(users = st.users.filterNot { it.userId == f.f1 }) }
                onStatusMessage("${f.f2} salió de la sala")
            }
            "suggestion_received" -> {
                val suggestion = ListenTogetherProtoCodec.decodeSuggestionReceived(payload)
                if (settingsProvider().listenTogetherAutoApproveSuggestions && _state.value.isHost) {
                    approveSuggestion(suggestion)
                    onStatusMessage("Sugerencia añadida automáticamente: ${suggestion.trackInfo.title}")
                } else {
                    _state.update { st ->
                        st.copy(pendingSuggestions = (st.pendingSuggestions + suggestion).distinctBy { it.suggestionId })
                    }
                    onStatusMessage("${suggestion.fromUsername} sugirió: ${suggestion.trackInfo.title}")
                }
            }
            "sync_playback" -> {
                if (_state.value.isHost) return
                val act = ListenTogetherProtoCodec.decodePlaybackAction(payload)
                applyRemotePlaybackAction(act)
            }
            "sync_state" -> {
                if (_state.value.isHost) return
                val sync = ListenTogetherProtoCodec.decodeSyncState(payload)
                sync.currentTrack?.takeIf { it.id.isNotBlank() }?.let { track ->
                    applyRemoteTrackAndState(track, sync.isPlaying, sync.positionMs)
                }
            }
            "error" -> {
                val f = ListenTogetherProtoCodec.decodeFourFields(payload)
                _state.update { it.copy(lastError = f.f2) }
                appendLog("Error servidor (${f.f1}): ${f.f2}")
                if (f.f2.isNotBlank()) onStatusMessage("Listen Together: ${f.f2}")
            }
        }
    }

    private fun applyRemoteTrackAndState(track: LtTrackInfo, isPlaying: Boolean, positionMs: Long) {
        scope.launch {
            applyingRemoteSync = true
            try {
                val song = track.toDesktopSong()
                if (audioPlayer.state.value.currentSong?.id != song.id) {
                    audioPlayer.playSong(song, loadRadio = false)
                    delay(400L)
                }
                if (positionMs > 0L) audioPlayer.seekTo(positionMs, broadcast = false)
                if (isPlaying) audioPlayer.resume() else audioPlayer.pause()
            } finally {
                delay(300L)
                applyingRemoteSync = false
            }
        }
    }

    private fun applyRemotePlaybackAction(act: ListenTogetherProtoCodec.DecodedPlaybackAction) {
        scope.launch {
            applyingRemoteSync = true
            try {
                when (act.action) {
                    "change_track" -> {
                        val track = act.trackInfo ?: if (act.trackId.isNotBlank()) LtTrackInfo(id = act.trackId, title = act.trackId) else null
                        if (track != null && track.id.isNotBlank()) {
                            audioPlayer.playSong(track.toDesktopSong(), loadRadio = false)
                            sendEnvelope("buffer_ready", ListenTogetherProtoCodec.encodeUserIdOnly(track.id))
                        }
                    }
                    "play" -> {
                        val track = act.trackInfo
                        if (track != null && track.id.isNotBlank() && audioPlayer.state.value.currentSong?.id != track.id) {
                            audioPlayer.playSong(track.toDesktopSong(), loadRadio = false)
                            delay(350L)
                        }
                        if (act.positionMs > 0L) audioPlayer.seekTo(act.positionMs, broadcast = false)
                        audioPlayer.resume()
                    }
                    "pause" -> {
                        if (act.positionMs > 0L) audioPlayer.seekTo(act.positionMs, broadcast = false)
                        audioPlayer.pause()
                    }
                    "seek" -> {
                        audioPlayer.seekTo(act.positionMs, broadcast = false)
                    }
                    "volume" -> {
                        if (settingsProvider().listenTogetherSyncVolume && act.volume in 0f..1f) {
                            audioPlayer.setVolume(act.volume, persist = false)
                        }
                    }
                    "sync_queue" -> {
                        if (act.queue.isNotEmpty()) {
                            val songs = act.queue.map { it.toDesktopSong() }
                            val idx = songs.indexOfFirst { it.id == act.trackId }.coerceAtLeast(0)
                            audioPlayer.playQueue(songs, startIndex = idx, loadRadio = false)
                        }
                    }
                }
            } finally {
                delay(300L)
                applyingRemoteSync = false
            }
        }
    }

    fun disconnect(clearSession: Boolean = true) {
        pingJob?.cancel()
        pingJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        runCatching { webSocket?.close(1000, null) }
        webSocket = null
        if (clearSession) {
            _state.value = LtState()
        }
    }

    fun shutdown() {
        manualDisconnect = true
        disconnect()
        runCatching { httpClient.dispatcher.cancelAll() }
        runCatching { httpClient.dispatcher.executorService.shutdownNow() }
        runCatching { httpClient.connectionPool.evictAll() }
    }
}
