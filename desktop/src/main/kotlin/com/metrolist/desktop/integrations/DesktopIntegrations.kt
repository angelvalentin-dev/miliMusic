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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.TimeUnit

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
            .header("User-Agent", "Metrolist Desktop (https://github.com/MetrolistGroup/Metrolist)")
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

    private var gatewaySocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var activeGatewayToken: String? = null
    private var gatewayReady: Boolean = false

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

    fun updatePresence(
        song: DesktopSong?,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long,
        settings: DesktopSettings,
    ) {
        if (!settings.enableDiscordRpc || song == null || !isPlaying) {
            clearPresence()
            return
        }
        val details = renderTemplate(settings.discordDetailsTemplate.ifBlank { "{song.name}" }, song)
        val state = renderTemplate(settings.discordStateTemplate.ifBlank { "{artist.name}" }, song)
        val now = System.currentTimeMillis()
        val startMs = (now - positionMs.coerceAtLeast(0L))
        val endMs = if (durationMs > 0L) startMs + durationMs else null
        val watchUrl = "https://music.youtube.com/watch?v=${song.id}"

        scope.launch {
            val ipcOk = if (AppPaths.isWindows) {
                sendWindowsIpcActivity(song, details, state, startMs, endMs, watchUrl)
            } else false

            if (ipcOk) {
                _connected.value = true
            } else if (settings.discordToken.isNotBlank()) {
                sendGatewayActivity(settings.discordToken.trim(), song, details, state, startMs, endMs, watchUrl)
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
                            put("status", "online")
                            put("afk", false)
                        },
                    )
                }.toString()
                runCatching { gatewaySocket?.send(payload) }
            }
        }
    }

    private fun sendWindowsIpcActivity(
        song: DesktopSong,
        details: String,
        state: String,
        startMs: Long,
        endMs: Long?,
        watchUrl: String,
    ): Boolean {
        val activity = buildJsonObject {
            put("type", 2)
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
            put(
                "buttons",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("label", "Listen on YouTube Music")
                            put("url", watchUrl)
                        },
                    )
                },
            )
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
        return writeDiscordIpcCommand(cmd)
    }

    private fun sendWindowsIpcClear(): Boolean {
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
        return writeDiscordIpcCommand(cmd)
    }

    private fun writeDiscordIpcCommand(cmdJson: String): Boolean {
        for (idx in 0..9) {
            val pipePath = "\\\\.\\pipe\\discord-ipc-$idx"
            val ok = runCatching {
                RandomAccessFile(pipePath, "rw").use { pipe ->
                    val handshake = """{"v":1,"client_id":"$DISCORD_APP_ID"}"""
                    writeIpcFrame(pipe, 0, handshake)
                    readIpcFrame(pipe)
                    writeIpcFrame(pipe, 1, cmdJson)
                    readIpcFrame(pipe)
                    true
                }
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
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
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buf.int // op
        val len = buf.int.coerceIn(0, 65536)
        val body = ByteArray(len)
        pipe.readFully(body)
        return String(body, Charsets.UTF_8)
    }

    private fun sendGatewayActivity(
        rawToken: String,
        song: DesktopSong,
        details: String,
        state: String,
        startMs: Long,
        endMs: Long?,
        watchUrl: String,
    ) {
        ensureGatewayConnected(rawToken)
        if (!gatewayReady) return
        val presenceJson = buildJsonObject {
            put("op", 3)
            put(
                "d",
                buildJsonObject {
                    put("since", 0)
                    put("status", "online")
                    put("afk", false)
                    put(
                        "activities",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("name", song.artistDisplay.take(128))
                                    put("type", 2)
                                    put("details", details.take(128))
                                    put("state", state.take(128))
                                    put(
                                        "timestamps",
                                        buildJsonObject {
                                            put("start", startMs)
                                            if (endMs != null) put("end", endMs)
                                        },
                                    )
                                    put(
                                        "buttons",
                                        buildJsonArray {
                                            add(JsonPrimitive("Listen on YouTube Music"))
                                        },
                                    )
                                    put(
                                        "metadata",
                                        buildJsonObject {
                                            put(
                                                "button_urls",
                                                buildJsonArray {
                                                    add(JsonPrimitive(watchUrl))
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }.toString()
        runCatching { gatewaySocket?.send(presenceJson) }
    }

    private fun ensureGatewayConnected(token: String) {
        if (gatewaySocket != null && activeGatewayToken == token && gatewayReady) return
        closeGateway()
        activeGatewayToken = token
        val req = Request.Builder().url(GATEWAY_URL).build()
        gatewaySocket = httpClient.newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val root = Json.parseToJsonElement(text).jsonObject
                        val op = root["op"]?.jsonPrimitive?.intOrNull ?: -1
                        if (op == 10) {
                            val interval = root["d"]?.jsonObject?.get("heartbeat_interval")?.jsonPrimitive?.longOrNull ?: 41250L
                            startGatewayHeartbeat(webSocket, interval)
                            val identify = buildJsonObject {
                                put("op", 2)
                                put(
                                    "d",
                                    buildJsonObject {
                                        put("token", token)
                                        put("intents", 0)
                                        put(
                                            "properties",
                                            buildJsonObject {
                                                put("os", "windows")
                                                put("browser", "Discord Client")
                                                put("device", DISCORD_APP_ID)
                                            },
                                        )
                                    },
                                )
                            }.toString()
                            webSocket.send(identify)
                            gatewayReady = true
                            _connected.value = true
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    gatewayReady = false
                    _connected.value = false
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    gatewayReady = false
                    _connected.value = false
                }
            },
        )
    }

    private fun startGatewayHeartbeat(ws: WebSocket, intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                runCatching { ws.send("""{"op":1,"d":null}""") }
            }
        }
    }

    private fun closeGateway() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        gatewayReady = false
        runCatching { gatewaySocket?.close(1000, null) }
        gatewaySocket = null
        _connected.value = false
    }

    fun shutdown() {
        clearPresence()
        closeGateway()
    }
}

class DesktopAlarmScheduler(
    private val database: DesktopDatabase,
    private val audioPlayer: DesktopAudioPlayer,
    private val onStatusMessage: (String) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parseAlarms(rawJson: String): List<DesktopAlarmEntry> {
            if (rawJson.isBlank()) return emptyList()
            return runCatching {
                val arr = json.parseToJsonElement(rawJson)
                if (arr !is JsonArray) return emptyList()
                arr.mapNotNull { elem ->
                    val obj = elem as? JsonObject ?: return@mapNotNull null
                    DesktopAlarmEntry(
                        id = obj["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                        enabled = obj["enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
                        hour = (obj["hour"]?.jsonPrimitive?.intOrNull ?: 7).coerceIn(0, 23),
                        minute = (obj["minute"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, 59),
                        playlistId = obj["playlistId"]?.jsonPrimitive?.content ?: "TOP",
                        randomSong = obj["randomSong"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                        nextTriggerAt = obj["nextTriggerAt"]?.jsonPrimitive?.longOrNull ?: -1L,
                    )
                }
            }.getOrDefault(emptyList())
        }

        fun serializeAlarms(entries: List<DesktopAlarmEntry>): String =
            json.encodeToString(entries)
    }

    private val _alarms = MutableStateFlow<List<DesktopAlarmEntry>>(emptyList())
    val alarms: StateFlow<List<DesktopAlarmEntry>> = _alarms.asStateFlow()

    private val triggeredToday = mutableSetOf<String>()
    private var lastTriggerDate: LocalDate = LocalDate.now()
    private var schedulerJob: Job? = null

    init {
        startWatcher()
    }

    fun parseAlarms(rawJson: String): List<DesktopAlarmEntry> = Companion.parseAlarms(rawJson)

    fun serializeAlarms(entries: List<DesktopAlarmEntry>): String = Companion.serializeAlarms(entries)

    fun updateFromRawJson(rawJson: String) {
        _alarms.value = parseAlarms(rawJson)
    }

    private fun startWatcher() {
        schedulerJob = scope.launch {
            while (isActive) {
                val today = LocalDate.now()
                if (today != lastTriggerDate) {
                    lastTriggerDate = today
                    triggeredToday.clear()
                }
                val now = LocalTime.now()
                val currentAlarms = _alarms.value
                for (alarm in currentAlarms) {
                    val triggerKey = "${alarm.id}_${today}_${alarm.hour}_${alarm.minute}"
                    if (alarm.enabled &&
                        alarm.hour == now.hour &&
                        alarm.minute == now.minute &&
                        triggerKey !in triggeredToday
                    ) {
                        triggeredToday.add(triggerKey)
                        triggerAlarm(alarm)
                    }
                }
                delay(15_000L)
            }
        }
    }

    fun triggerAlarm(alarm: DesktopAlarmEntry) {
        scope.launch {
            val songs = when (alarm.playlistId) {
                "LIKED", "" -> database.likedSongs()
                "TOP" -> database.topSongs(50)
                "DOWNLOADED" -> database.downloadedSongs()
                "CACHED" -> database.cachedSongs()
                else -> database.playlistSongs(alarm.playlistId)
            }.ifEmpty {
                database.likedSongs().ifEmpty { database.topSongs(50) }.ifEmpty { database.recentlyPlayed(30) }
            }
            if (songs.isNotEmpty()) {
                val playList = if (alarm.randomSong) songs.shuffled() else songs
                audioPlayer.playQueue(playList, 0)
                onStatusMessage("Alarma musical (%02d:%02d): Reproduciendo %s".format(alarm.hour, alarm.minute, playList.first().title))
            } else {
                onStatusMessage("Alarma musical (%02d:%02d): No hay canciones en la lista seleccionada".format(alarm.hour, alarm.minute))
            }
        }
    }

    fun stop() {
        schedulerJob?.cancel()
    }
}
