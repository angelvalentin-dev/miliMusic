package com.metrolist.desktop.integrations

import com.metrolist.desktop.AppPaths
import com.metrolist.desktop.audio.DesktopAudioPlayer
import com.metrolist.desktop.data.DesktopSettings
import com.metrolist.desktop.db.DesktopAlarmEntry
import com.metrolist.desktop.db.DesktopDatabase
import com.metrolist.desktop.db.DesktopSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object DesktopLastFmService {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun resolveApiKey(settings: DesktopSettings): String =
        settings.lastFmApiKey.ifBlank { System.getenv("LASTFM_API_KEY").orEmpty() }

    private fun resolveSecret(settings: DesktopSettings): String =
        settings.lastFmSecret.ifBlank { System.getenv("LASTFM_SECRET").orEmpty() }

    fun md5Sig(params: Map<String, String>, secret: String): String {
        val toHash = params.toSortedMap().entries.joinToString("") { it.key + it.value } + secret
        val digest = MessageDigest.getInstance("MD5").digest(toHash.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun postSigned(
        method: String,
        apiKey: String,
        secret: String,
        sessionKey: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): Result<JsonObject> = runCatching {
        require(apiKey.isNotBlank() && secret.isNotBlank()) {
            "Configura API Key y Secret de Last.fm en Ajustes"
        }
        val sigParams = mutableMapOf("method" to method, "api_key" to apiKey).apply {
            if (!sessionKey.isNullOrBlank()) put("sk", sessionKey)
            putAll(extra)
        }
        val sig = md5Sig(sigParams, secret)
        val form = FormBody.Builder().apply {
            sigParams.forEach { (k, v) -> add(k, v) }
            add("api_sig", sig)
            add("format", "json")
        }.build()
        val req = Request.Builder()
            .url("https://ws.audioscrobbler.com/2.0/")
            .header("User-Agent", "miliMusic Desktop (https://github.com/angelvalentin-dev/miliMusic)")
            .post(form)
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            val obj = json.parseToJsonElement(body).jsonObject
            val err = obj["error"]?.jsonPrimitive?.intOrNull
            if (err != null) {
                val msg = obj["message"]?.jsonPrimitive?.content ?: "Error Last.fm ($err)"
                error(msg)
            }
            obj
        }
    }

    fun authenticateMobileSession(
        username: String,
        password: String,
        apiKey: String,
        secret: String,
    ): Result<Pair<String, String>> = runCatching {
        val obj = postSigned(
            method = "auth.getMobileSession",
            apiKey = apiKey,
            secret = secret,
            extra = mapOf("username" to username.trim(), "password" to password),
        ).getOrThrow()
        val session = obj["session"]?.jsonObject ?: error("Respuesta inválida de Last.fm")
        val name = session["name"]?.jsonPrimitive?.content ?: username.trim()
        val key = session["key"]?.jsonPrimitive?.content ?: error("No se recibió session key")
        name to key
    }

    fun updateNowPlaying(song: DesktopSong, settings: DesktopSettings) {
        if (!settings.enableLastFmScrobbling || !settings.lastFmUseNowPlaying || settings.lastFmSession.isBlank()) return
        val apiKey = resolveApiKey(settings)
        val secret = resolveSecret(settings)
        if (apiKey.isBlank() || secret.isBlank()) return
        val extra = buildMap {
            put("artist", song.artistDisplay)
            put("track", song.title)
            song.albumName?.takeIf { it.isNotBlank() }?.let { put("album", it) }
            if (song.duration > 0) put("duration", song.duration.toString())
        }
        postSigned("track.updateNowPlaying", apiKey, secret, settings.lastFmSession, extra)
    }

    fun scrobble(song: DesktopSong, timestampEpochSec: Long, settings: DesktopSettings) {
        if (!settings.enableLastFmScrobbling || settings.lastFmSession.isBlank()) return
        val apiKey = resolveApiKey(settings)
        val secret = resolveSecret(settings)
        if (apiKey.isBlank() || secret.isBlank()) return
        val extra = buildMap {
            put("artist[0]", song.artistDisplay)
            put("track[0]", song.title)
            put("timestamp[0]", timestampEpochSec.toString())
            song.albumName?.takeIf { it.isNotBlank() }?.let { put("album[0]", it) }
            if (song.duration > 0) put("duration[0]", song.duration.toString())
        }
        postSigned("track.scrobble", apiKey, secret, settings.lastFmSession, extra)
    }

    fun setLoveStatus(song: DesktopSong, liked: Boolean, settings: DesktopSettings) {
        if (!settings.enableLastFmScrobbling || !settings.lastFmUseSendLikes || settings.lastFmSession.isBlank()) return
        val apiKey = resolveApiKey(settings)
        val secret = resolveSecret(settings)
        if (apiKey.isBlank() || secret.isBlank()) return
        val method = if (liked) "track.love" else "track.unlove"
        postSigned(
            method = method,
            apiKey = apiKey,
            secret = secret,
            sessionKey = settings.lastFmSession,
            extra = mapOf("artist" to song.artistDisplay, "track" to song.title),
        )
    }

    fun close() {
        runCatching { httpClient.dispatcher.cancelAll() }
        runCatching { httpClient.dispatcher.executorService.shutdownNow() }
        runCatching { httpClient.connectionPool.evictAll() }
    }
}

class DesktopDiscordRpc(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    companion object {
        const val DISCORD_APP_ID = "1447278780795064401"
        private const val GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"
    }

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // Persistent Windows IPC named pipe (Discord clears presence immediately if closed!)
    private val ipcLock = Any()
    private var ipcPipe: RandomAccessFile? = null

    private var gatewaySocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var activeGatewayToken: String? = null
    private var gatewayReady: Boolean = false
    private val lastGatewaySeq = AtomicReference<Long?>(null)
    private var lastStatus: String = "online"

    fun renderTemplate(template: String, song: DesktopSong): String =
        template
            .replace("{song.name}", song.title)
            .replace("{title}", song.title)
            .replace("{artist.name}", song.artistDisplay)
            .replace("{artist}", song.artistDisplay)
            .replace("{album.name}", song.albumName.orEmpty())
            .replace("{album}", song.albumName.orEmpty())
            .replace("{song.id}", song.id)
            .trim()
            .ifBlank { song.title }

    fun activityTypeCode(typeName: String): Int = when (typeName.uppercase()) {
        "PLAYING" -> 0
        "WATCHING" -> 3
        "COMPETING" -> 5
        else -> 2 // LISTENING
    }

    fun updatePresence(
        song: DesktopSong?,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long,
        settings: DesktopSettings,
    ) {
        lastStatus = settings.discordUserStatus.ifBlank { "online" }
        if (!settings.enableDiscordRpc || song == null || !isPlaying) {
            clearPresence()
            return
        }
        val details = renderTemplate(settings.discordDetailsTemplate.ifBlank { "{song.name}" }, song)
        val state = renderTemplate(settings.discordStateTemplate.ifBlank { "{artist.name}" }, song)
        val actName = renderTemplate(settings.discordActivityName.ifBlank { "miliMusic" }, song)
        val actType = activityTypeCode(settings.discordActivityType)
        val now = System.currentTimeMillis()
        val startMs = (now - positionMs.coerceAtLeast(0L))
        val endMs = if (durationMs > 0L) startMs + durationMs else null

        val buttons = buildList {
            if (settings.discordButton1Enabled && settings.discordButton1Text.isNotBlank() && settings.discordButton1Url.isNotBlank()) {
                add(renderTemplate(settings.discordButton1Text, song).take(32) to renderTemplate(settings.discordButton1Url, song))
            }
            if (settings.discordButton2Enabled && settings.discordButton2Text.isNotBlank() && settings.discordButton2Url.isNotBlank()) {
                add(renderTemplate(settings.discordButton2Text, song).take(32) to renderTemplate(settings.discordButton2Url, song))
            }
        }

        scope.launch {
            val ipcOk = if (AppPaths.isWindows) {
                sendWindowsIpcActivity(song, actType, actName, details, state, startMs, endMs, buttons)
            } else false

            if (ipcOk) {
                _connected.value = true
            } else if (settings.discordToken.isNotBlank()) {
                sendGatewayActivity(
                    token = settings.discordToken.trim(),
                    song = song,
                    actType = actType,
                    actName = actName,
                    details = details,
                    state = state,
                    startMs = startMs,
                    endMs = endMs,
                    status = lastStatus,
                )
            } else {
                _connected.value = false
            }
        }
    }

    fun clearPresence() {
        scope.launch {
            if (AppPaths.isWindows) {
                runCatching { sendWindowsIpcClear() }
            }
            if (gatewayReady) {
                val payload = buildJsonObject {
                    put("op", 3)
                    put(
                        "d",
                        buildJsonObject {
                            put("since", 0)
                            put("activities", buildJsonArray {})
                            put("status", lastStatus)
                            put("afk", false)
                        },
                    )
                }.toString()
                runCatching { gatewaySocket?.send(payload) }
            }
        }
    }

    private fun ensureWindowsIpcPipe(): RandomAccessFile? {
        ipcPipe?.let { return it }
        for (idx in 0..9) {
            val pipePath = "\\\\.\\pipe\\discord-ipc-$idx"
            val opened = runCatching {
                val pipe = RandomAccessFile(pipePath, "rw")
                val handshake = """{"v":1,"client_id":"$DISCORD_APP_ID"}"""
                writeIpcFrame(pipe, 0, handshake)
                readIpcFrame(pipe)
                pipe
            }.getOrNull()
            if (opened != null) {
                ipcPipe = opened
                return opened
            }
        }
        return null
    }

    private fun closeIpcPipeLocked() {
        runCatching { ipcPipe?.close() }
        ipcPipe = null
    }

    private fun sendWindowsIpcCommand(cmdJson: String): Boolean = synchronized(ipcLock) {
        repeat(2) {
            val pipe = ensureWindowsIpcPipe() ?: return false
            val ok = runCatching {
                writeIpcFrame(pipe, 1, cmdJson)
                readIpcFrame(pipe)
                true
            }.getOrElse {
                closeIpcPipeLocked()
                false
            }
            if (ok) return true
        }
        false
    }

    private fun sendWindowsIpcActivity(
        song: DesktopSong,
        actType: Int,
        actName: String,
        details: String,
        state: String,
        startMs: Long,
        endMs: Long?,
        buttons: List<Pair<String, String>>,
    ): Boolean {
        val activity = buildJsonObject {
            put("type", actType)
            put("name", actName.take(128))
            put("details", details.take(128))
            put("state", state.take(128))
            put(
                "timestamps",
                buildJsonObject {
                    put("start", startMs / 1000L)
                    if (endMs != null) put("end", endMs / 1000L)
                },
            )
            put(
                "assets",
                buildJsonObject {
                    if (!song.thumbnailUrl.isNullOrBlank()) {
                        put("large_image", song.thumbnailUrl)
                    }
                    put("large_text", (song.albumName ?: song.title).take(128))
                },
            )
            if (buttons.isNotEmpty()) {
                put(
                    "buttons",
                    buildJsonArray {
                        buttons.forEach { (label, url) ->
                            add(
                                buildJsonObject {
                                    put("label", label)
                                    put("url", url)
                                },
                            )
                        }
                    },
                )
            }
        }
        val cmd = buildJsonObject {
            put("cmd", "SET_ACTIVITY")
            put(
                "args",
                buildJsonObject {
                    put("pid", ProcessHandle.current().pid().toInt())
                    put("activity", activity)
                },
            )
            put("nonce", UUID.randomUUID().toString())
        }.toString()
        return sendWindowsIpcCommand(cmd)
    }

    private fun sendWindowsIpcClear(): Boolean {
        synchronized(ipcLock) {
            if (ipcPipe == null) return false
        }
        val cmd = buildJsonObject {
            put("cmd", "SET_ACTIVITY")
            put(
                "args",
                buildJsonObject {
                    put("pid", ProcessHandle.current().pid().toInt())
                    put("activity", JsonNull)
                },
            )
            put("nonce", UUID.randomUUID().toString())
        }.toString()
        return sendWindowsIpcCommand(cmd)
    }

    private fun writeIpcFrame(pipe: RandomAccessFile, op: Int, jsonPayload: String) {
        val bytes = jsonPayload.toByteArray(Charsets.UTF_8)
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(op)
            .putInt(bytes.size)
            .array()
        pipe.write(header)
        pipe.write(bytes)
    }

    private fun readIpcFrame(pipe: RandomAccessFile): String {
        val header = ByteArray(8)
        pipe.readFully(header)
        val len = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(4).coerceIn(0, 65536)
        val body = ByteArray(len)
        pipe.readFully(body)
        return String(body, Charsets.UTF_8)
    }

    private fun sendGatewayActivity(
        token: String,
        song: DesktopSong,
        actType: Int,
        actName: String,
        details: String,
        state: String,
        startMs: Long,
        endMs: Long?,
        status: String,
    ) {
        ensureGatewayConnected(token)
        if (!gatewayReady) return
        val payload = buildJsonObject {
            put("op", 3)
            put(
                "d",
                buildJsonObject {
                    put("since", 0)
                    put(
                        "activities",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("name", actName.take(128))
                                    put("type", actType)
                                    put("application_id", DISCORD_APP_ID)
                                    put("details", details.take(128))
                                    put("state", state.take(128))
                                    put(
                                        "timestamps",
                                        buildJsonObject {
                                            put("start", startMs)
                                            if (endMs != null) put("end", endMs)
                                        },
                                    )
                                },
                            )
                        },
                    )
                    put("status", status)
                    put("afk", false)
                },
            )
        }.toString()
        runCatching { gatewaySocket?.send(payload) }
    }

    private fun ensureGatewayConnected(token: String) {
        if (gatewaySocket != null && activeGatewayToken == token) return
        disconnectGateway()
        activeGatewayToken = token
        val req = Request.Builder().url(GATEWAY_URL).build()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        gatewaySocket = httpClient.newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                    obj["s"]?.jsonPrimitive?.longOrNull?.let { lastGatewaySeq.set(it) }
                    val op = obj["op"]?.jsonPrimitive?.intOrNull ?: return
                    when (op) {
                        10 -> {
                            val interval = obj["d"]?.jsonObject?.get("heartbeat_interval")?.jsonPrimitive?.longOrNull ?: 41_250L
                            startHeartbeat(webSocket, interval)
                            val identify = buildJsonObject {
                                put("op", 2)
                                put(
                                    "d",
                                    buildJsonObject {
                                        put("token", token)
                                        put("capabilities", 0)
                                        put(
                                            "properties",
                                            buildJsonObject {
                                                put("os", "Windows")
                                                put("browser", "miliMusic Desktop")
                                                put("device", "miliMusic Desktop")
                                            },
                                        )
                                    },
                                )
                            }.toString()
                            webSocket.send(identify)
                        }
                        0 -> {
                            if (obj["t"]?.jsonPrimitive?.contentOrNull == "READY") {
                                gatewayReady = true
                                _connected.value = true
                            }
                        }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    gatewayReady = false
                    _connected.value = false
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    gatewayReady = false
                    _connected.value = false
                }
            },
        )
    }

    private fun startHeartbeat(ws: WebSocket, intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                val seq = lastGatewaySeq.get()
                val msg = if (seq != null) """{"op":1,"d":$seq}""" else """{"op":1,"d":null}"""
                runCatching { ws.send(msg) }
            }
        }
    }

    private fun disconnectGateway() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        gatewayReady = false
        lastGatewaySeq.set(null)
        runCatching { gatewaySocket?.close(1000, null) }
        gatewaySocket = null
        activeGatewayToken = null
        _connected.value = false
    }

    fun shutdown() {
        synchronized(ipcLock) {
            runCatching {
                if (ipcPipe != null) {
                    sendWindowsIpcClear()
                }
            }
            closeIpcPipeLocked()
        }
        disconnectGateway()
        runCatching { httpClient.dispatcher.cancelAll() }
        runCatching { httpClient.dispatcher.executorService.shutdownNow() }
        runCatching { httpClient.connectionPool.evictAll() }
    }
}

class DesktopAlarmScheduler(
    private val database: DesktopDatabase,
    private val audioPlayer: DesktopAudioPlayer,
    private val onStatusMessage: (String) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _alarms = MutableStateFlow<List<DesktopAlarmEntry>>(emptyList())
    val alarms: StateFlow<List<DesktopAlarmEntry>> = _alarms.asStateFlow()

    private val _ringingAlarm = MutableStateFlow<DesktopAlarmEntry?>(null)
    val ringingAlarm: StateFlow<DesktopAlarmEntry?> = _ringingAlarm.asStateFlow()

    private var tickerJob: Job? = null
    private var snoozeJob: Job? = null
    private val firedKeys = mutableSetOf<String>()

    init {
        startTicker()
    }

    fun parseAlarms(rawJson: String): List<DesktopAlarmEntry> = Companion.parseAlarms(rawJson)

    fun serializeAlarms(entries: List<DesktopAlarmEntry>): String = Companion.serializeAlarms(entries)

    fun updateFromRawJson(rawJson: String) {
        _alarms.value = parseAlarms(rawJson)
    }

    companion object {
        private val alarmJson = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parseAlarms(rawJson: String): List<DesktopAlarmEntry> {
            if (rawJson.isBlank()) return emptyList()
            return runCatching {
                alarmJson.decodeFromString<List<DesktopAlarmEntry>>(rawJson)
            }.getOrDefault(emptyList())
        }

        fun serializeAlarms(entries: List<DesktopAlarmEntry>): String =
            alarmJson.encodeToString(entries)
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                val nowDt = LocalDateTime.now()
                val now = nowDt.toLocalTime()
                val today = nowDt.toLocalDate()
                val dayOfWeek = nowDt.dayOfWeek.value // 1=Mon..7=Sun
                for (alarm in _alarms.value) {
                    if (!alarm.enabled) continue
                    if (alarm.daysOfWeek.isNotEmpty() && dayOfWeek !in alarm.daysOfWeek) continue
                    if (alarm.hour == now.hour && alarm.minute == now.minute) {
                        val key = "${alarm.id}_${today}_${alarm.hour}_${alarm.minute}"
                        if (firedKeys.add(key)) {
                            triggerAlarm(alarm)
                        }
                    }
                }
                delay(15_000L)
            }
        }
    }

    fun triggerAlarm(alarm: DesktopAlarmEntry) {
        scope.launch {
            val songs = resolveSongsForAlarm(alarm)
            if (songs.isEmpty()) {
                onStatusMessage("Alarma ${"%02d:%02d".format(alarm.hour, alarm.minute)}: sin canciones disponibles")
                return@launch
            }
            val startIndex = if (alarm.randomSong) songs.indices.random() else 0
            val targetVol = audioPlayer.state.value.volume.coerceAtLeast(0.4f)
            if (alarm.volumeRamp) {
                audioPlayer.setVolume(0.1f, persist = false)
            }
            audioPlayer.playQueue(songs, startIndex = startIndex, loadRadio = false)
            _ringingAlarm.value = alarm
            val labelSuffix = alarm.label.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
            onStatusMessage("Alarma musical activada (${"%02d:%02d".format(alarm.hour, alarm.minute)}$labelSuffix)")
            if (alarm.volumeRamp) {
                for (step in 1..10) {
                    delay(1_500L)
                    if (_ringingAlarm.value?.id != alarm.id) break
                    val vol = 0.1f + (targetVol - 0.1f) * (step / 10f)
                    audioPlayer.setVolume(vol, persist = false)
                }
            }
        }
    }

    fun snoozeRingingAlarm(minutes: Int = 5) {
        val current = _ringingAlarm.value ?: return
        _ringingAlarm.value = null
        audioPlayer.pause()
        onStatusMessage("Alarma pospuesta $minutes min")
        snoozeJob?.cancel()
        snoozeJob = scope.launch {
            delay(minutes * 60_000L)
            triggerAlarm(current)
        }
    }

    fun dismissRingingAlarm() {
        snoozeJob?.cancel()
        _ringingAlarm.value = null
    }

    private fun resolveSongsForAlarm(alarm: DesktopAlarmEntry): List<DesktopSong> = when (alarm.playlistId) {
        "TOP", "" -> database.topSongs(50).ifEmpty { database.likedSongs() }.ifEmpty { database.librarySongs() }
        "LIKED" -> database.likedSongs().ifEmpty { database.librarySongs() }
        "DOWNLOADED" -> database.downloadedSongs().ifEmpty { database.librarySongs() }
        else -> database.playlistSongs(alarm.playlistId).ifEmpty { database.topSongs(50) }
    }

    fun stop() {
        tickerJob?.cancel()
        snoozeJob?.cancel()
        tickerJob = null
        snoozeJob = null
    }
}
