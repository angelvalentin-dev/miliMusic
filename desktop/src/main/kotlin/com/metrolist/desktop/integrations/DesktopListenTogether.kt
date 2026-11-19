package com.metrolist.desktop.integrations

import com.metrolist.desktop.audio.DesktopAudioPlayer
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

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

fun DesktopSong.toLtTrackInfo(): LtTrackInfo =
    LtTrackInfo(
        id = id,
        title = title,
        artist = artistDisplay,
        album = albumName.orEmpty(),
        duration = (duration.coerceAtLeast(0)) * 1000L,
        thumbnail = thumbnailUrl.orEmpty(),
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

data class LtState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val roomCode: String = "",
    val userId: String = "",
    val isHost: Boolean = false,
    val users: List<LtUserInfo> = emptyList(),
    val pendingJoinRequests: List<LtJoinRequest> = emptyList(),
    val statusText: String = "Desconectado",
    val lastError: String? = null,
)

/**
 * Minimal Proto3 wire reader/writer for listentogether.proto compatibility with Android & metroserverx.
 */
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

    fun encodeCreateRoom(username: String): ByteArray =
        ProtoWriter().apply { writeString(1, username) }.toByteArray()

    fun encodeJoinRoom(roomCode: String, username: String): ByteArray =
        ProtoWriter().apply {
            writeString(1, roomCode)
            writeString(2, username)
        }.toByteArray()

    fun encodeUserIdOnly(userId: String): ByteArray =
        ProtoWriter().apply { writeString(1, userId) }.toByteArray()

    fun encodePing(clientTime: Long, seq: Long): ByteArray =
        ProtoWriter().apply {
            writeInt64(1, clientTime)
            writeInt64(2, seq)
        }.toByteArray()

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
        var id = ""
        var title = ""
        var artist = ""
        var album = ""
        var duration = 0L
        var thumb = ""
        var suggestedBy = ""
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
        var userId = ""
        var username = ""
        var isHost = false
        var isConnected = true
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
        revision: Long = 1L,
    ): ByteArray = ProtoWriter().apply {
        writeString(1, action)
        writeString(2, trackId)
        writeInt64(3, positionMs)
        if (trackInfo != null) {
            writeBytes(4, encodeTrackInfo(trackInfo))
        }
        writeFloat(8, 1f)
        writeInt64(10, revision)
    }.toByteArray()

    data class DecodedPlaybackAction(
        val action: String,
        val trackId: String,
        val positionMs: Long,
        val trackInfo: LtTrackInfo?,
    )

    fun decodePlaybackAction(bytes: ByteArray): DecodedPlaybackAction {
        val r = ProtoReader(bytes)
        var action = ""
        var trackId = ""
        var position = 0L
        var track: LtTrackInfo? = null
        while (r.hasRemaining) {
            val (field, wire) = r.readTag() ?: break
            when {
                field == 1 && wire == 2 -> action = r.readString()
                field == 2 && wire == 2 -> trackId = r.readString()
                field == 3 && wire == 0 -> position = r.readVarint()
                field == 4 && wire == 2 -> track = decodeTrackInfo(r.readBytes())
                else -> r.skipField(wire)
            }
        }
        return DecodedPlaybackAction(action, trackId, position, track)
    }

    data class DecodedRoomState(
        val roomCode: String,
        val hostId: String,
        val users: List<LtUserInfo>,
        val currentTrack: LtTrackInfo?,
        val isPlaying: Boolean,
        val positionMs: Long,
    )

    fun decodeRoomState(bytes: ByteArray): DecodedRoomState {
        val r = ProtoReader(bytes)
        var roomCode = ""
        var hostId = ""
        val users = mutableListOf<LtUserInfo>()
        var currentTrack: LtTrackInfo? = null
        var isPlaying = false
        var position = 0L
        while (r.hasRemaining) {
            val (field, wire) = r.readTag() ?: break
            when {
                field == 1 && wire == 2 -> roomCode = r.readString()
                field == 2 && wire == 2 -> hostId = r.readString()
                field == 3 && wire == 2 -> users.add(decodeUserInfo(r.readBytes()))
                field == 4 && wire == 2 -> currentTrack = decodeTrackInfo(r.readBytes())
                field == 5 && wire == 0 -> isPlaying = r.readBool()
                field == 6 && wire == 0 -> position = r.readVarint()
                else -> r.skipField(wire)
            }
        }
        return DecodedRoomState(roomCode, hostId, users, currentTrack, isPlaying, position)
    }

    fun decodeThreeStrings(bytes: ByteArray): Triple<String, String, ByteArray> {
        val r = ProtoReader(bytes)
        var f1 = ""
        var f2 = ""
        var f4 = ByteArray(0)
        while (r.hasRemaining) {
            val (field, wire) = r.readTag() ?: break
            when {
                field == 1 && wire == 2 -> f1 = r.readString()
                field == 2 && wire == 2 -> f2 = r.readString()
                field == 4 && wire == 2 -> f4 = r.readBytes()
                else -> r.skipField(wire)
            }
        }
        return Triple(f1, f2, f4)
    }
}

class DesktopListenTogetherManager(
    private val audioPlayer: DesktopAudioPlayer,
    private val onStatusMessage: (String) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _state = MutableStateFlow(LtState())
    val state: StateFlow<LtState> = _state.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var pingJob: Job? = null
    private val pingSeq = AtomicLong(0L)
    private val revisionCounter = AtomicLong(1L)
    @Volatile
    private var applyingRemoteSync: Boolean = false

    fun createRoom(serverUrl: String, username: String) {
        val cleanName = username.trim().ifBlank { "Usuario Windows" }
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
        connectAndSend(serverUrl) {
            sendEnvelope("join_room", ListenTogetherProtoCodec.encodeJoinRoom(cleanCode, cleanName))
        }
    }

    fun approveJoin(userId: String) {
        sendEnvelope("approve_join", ListenTogetherProtoCodec.encodeUserIdOnly(userId))
        _state.update { st -> st.copy(pendingJoinRequests = st.pendingJoinRequests.filterNot { it.userId == userId }) }
    }

    fun rejectJoin(userId: String) {
        sendEnvelope("reject_join", ListenTogetherProtoCodec.encodeUserIdOnly(userId))
        _state.update { st -> st.copy(pendingJoinRequests = st.pendingJoinRequests.filterNot { it.userId == userId }) }
    }

    fun leaveRoom() {
        runCatching { sendEnvelope("leave_room", ByteArray(0)) }
        disconnect()
    }

    fun broadcastHostPlayback(song: DesktopSong?, isPlaying: Boolean, positionMs: Long, trackChanged: Boolean) {
        val st = _state.value
        if (!st.connected || !st.isHost || applyingRemoteSync || song == null) return
        val rev = revisionCounter.incrementAndGet()
        if (trackChanged) {
            sendEnvelope(
                "playback_action",
                ListenTogetherProtoCodec.encodePlaybackAction(
                    action = "change_track",
                    trackId = song.id,
                    positionMs = 0L,
                    trackInfo = song.toLtTrackInfo(),
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
                revision = rev,
            ),
        )
    }

    private fun connectAndSend(serverUrl: String, onConnected: () -> Unit) {
        disconnect()
        val targetUrl = serverUrl.trim().ifBlank { "wss://metroserverx.meowery.eu/ws" }
        _state.update { it.copy(connecting = true, statusText = "Conectando...", lastError = null) }
        val req = Request.Builder()
            .url(targetUrl)
            .header("User-Agent", "com.metrolist.music")
            .build()
        webSocket = httpClient.newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _state.update { it.copy(connected = true, connecting = false, statusText = "Conectado al servidor") }
                    startPingLoop()
                    onConnected()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleBinaryMessage(bytes.toByteArray())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _state.update {
                        it.copy(
                            connected = false,
                            connecting = false,
                            statusText = "Error de conexión",
                            lastError = t.message ?: "No se pudo conectar al servidor Listen Together",
                        )
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _state.update { LtState(statusText = "Desconectado") }
                }
            },
        )
    }

    private fun sendEnvelope(type: String, payload: ByteArray) {
        val encoded = ListenTogetherProtoCodec.encodeEnvelope(type, payload)
        webSocket?.send(encoded.toByteString())
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(25_000L)
                val payload = ListenTogetherProtoCodec.encodePing(System.currentTimeMillis(), pingSeq.incrementAndGet())
                runCatching { sendEnvelope("ping", payload) }
            }
        }
    }

    private fun handleBinaryMessage(raw: ByteArray) {
        val (type, payload) = runCatching { ListenTogetherProtoCodec.decodeEnvelope(raw) }.getOrNull() ?: return
        when (type) {
            "room_created" -> {
                val (roomCode, userId, _) = ListenTogetherProtoCodec.decodeThreeStrings(payload)
                _state.update {
                    it.copy(
                        roomCode = roomCode,
                        userId = userId,
                        isHost = true,
                        users = listOf(LtUserInfo(userId, "Tú (Host)", isHost = true)),
                        statusText = "Sala creada: $roomCode",
                    )
                }
                onStatusMessage("Sala Escuchar Juntos creada: $roomCode")
            }
            "join_request" -> {
                val (userId, username, _) = ListenTogetherProtoCodec.decodeThreeStrings(payload)
                _state.update { st ->
                    st.copy(
                        pendingJoinRequests = (st.pendingJoinRequests + LtJoinRequest(userId, username)).distinctBy { it.userId },
                    )
                }
                onStatusMessage("$username solicita unirse a la sala")
            }
            "join_approved" -> {
                val (roomCode, userId, roomStateBytes) = ListenTogetherProtoCodec.decodeThreeStrings(payload)
                val roomState = if (roomStateBytes.isNotEmpty()) ListenTogetherProtoCodec.decodeRoomState(roomStateBytes) else null
                _state.update {
                    it.copy(
                        roomCode = roomCode,
                        userId = userId,
                        isHost = false,
                        users = roomState?.users.orEmpty(),
                        statusText = "Unido a sala $roomCode",
                    )
                }
                onStatusMessage("Te uniste a la sala $roomCode")
                if (roomState?.currentTrack != null && roomState.currentTrack.id.isNotBlank()) {
                    applyRemoteTrackAndState(roomState.currentTrack, roomState.isPlaying, roomState.positionMs)
                }
            }
            "join_rejected" -> {
                val (reason, _, _) = ListenTogetherProtoCodec.decodeThreeStrings(payload)
                onStatusMessage("Solicitud rechazada: ${reason.ifBlank { "El host rechazó la solicitud" }}")
                disconnect()
            }
            "user_joined" -> {
                val (userId, username, _) = ListenTogetherProtoCodec.decodeThreeStrings(payload)
                _state.update { st ->
                    st.copy(users = (st.users + LtUserInfo(userId, username, isHost = false)).distinctBy { it.userId })
                }
                onStatusMessage("$username se unió a la sala")
            }
            "user_left" -> {
                val (userId, username, _) = ListenTogetherProtoCodec.decodeThreeStrings(payload)
                _state.update { st -> st.copy(users = st.users.filterNot { it.userId == userId }) }
                onStatusMessage("$username salió de la sala")
            }
            "sync_playback" -> {
                if (_state.value.isHost) return
                val act = ListenTogetherProtoCodec.decodePlaybackAction(payload)
                applyRemotePlaybackAction(act)
            }
            "error" -> {
                val (_, msg, _) = ListenTogetherProtoCodec.decodeThreeStrings(payload)
                _state.update { it.copy(lastError = msg) }
                if (msg.isNotBlank()) onStatusMessage("Listen Together: $msg")
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
                if (positionMs > 0L) audioPlayer.seekTo(positionMs)
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
                        if (act.positionMs > 0L) audioPlayer.seekTo(act.positionMs)
                        audioPlayer.resume()
                    }
                    "pause" -> {
                        if (act.positionMs > 0L) audioPlayer.seekTo(act.positionMs)
                        audioPlayer.pause()
                    }
                    "seek" -> {
                        audioPlayer.seekTo(act.positionMs)
                    }
                }
            } finally {
                delay(300L)
                applyingRemoteSync = false
            }
        }
    }

    fun disconnect() {
        pingJob?.cancel()
        pingJob = null
        runCatching { webSocket?.close(1000, null) }
        webSocket = null
        _state.value = LtState()
    }
}
