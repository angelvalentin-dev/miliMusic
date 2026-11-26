package com.metrolist.desktop.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.metrolist.desktop.AppPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toOkioPath
import okio.buffer
import okio.source
import java.io.ByteArrayInputStream

object DesktopPrefKeys {
    // Theme & Appearance (shared with Android PreferenceKeys.kt)
    val DarkMode = stringPreferencesKey("darkMode")
    val PureBlack = booleanPreferencesKey("pureBlack")
    val DynamicTheme = booleanPreferencesKey("dynamicTheme")
    val SelectedThemeColor = intPreferencesKey("selectedThemeColor")
    val PlayerBackgroundStyle = stringPreferencesKey("playerBackground")
    val SliderStyle = stringPreferencesKey("sliderStyle")
    val GridItemsSize = stringPreferencesKey("gridItemSize")
    val DensityScale = floatPreferencesKey("density_scale_factor")
    val DefaultOpenTab = stringPreferencesKey("defaultOpenTab")
    val ShowLikedPlaylist = booleanPreferencesKey("show_liked_playlist")
    val ShowDownloadedPlaylist = booleanPreferencesKey("show_downloaded_playlist")
    val ShowTopPlaylist = booleanPreferencesKey("show_top_playlist")
    val ShowCachedPlaylist = booleanPreferencesKey("show_cached_playlist")
    val ShowUploadedPlaylist = booleanPreferencesKey("show_uploaded_playlist")
    val ShowWrappedCard = booleanPreferencesKey("show_wrapped_card")

    // Lyrics appearance & behavior
    val LyricsTextSize = floatPreferencesKey("lyricsTextSize")
    val LyricsLineSpacing = floatPreferencesKey("lyricsLineSpacing")
    val LyricsTextPosition = stringPreferencesKey("lyricsPosition")
    val LyricsAnimationStyle = stringPreferencesKey("lyricsAnimationStyle")
    val RespectAgentPositioning = booleanPreferencesKey("respectAgentPositioning")
    val ShowIntervalIndicator = booleanPreferencesKey("showIntervalIndicator")
    val LyricsClick = booleanPreferencesKey("lyricsClick")
    val LyricsScroll = booleanPreferencesKey("lyricsScroll")

    // Content & Locale & Proxy
    val ContentLanguage = stringPreferencesKey("contentLanguage")
    val ContentCountry = stringPreferencesKey("contentCountry")
    val HideExplicit = booleanPreferencesKey("hideExplicit")
    val HideVideoSongs = booleanPreferencesKey("hideVideoSongs")
    val HideYoutubeShorts = booleanPreferencesKey("hideYoutubeShorts")
    val AddToPlaylistPosition = stringPreferencesKey("addToPlaylistPosition")
    val ShowArtistDescription = booleanPreferencesKey("showArtistDescription")
    val ShowArtistSubscriberCount = booleanPreferencesKey("showArtistSubscriberCount")
    val ShowMonthlyListeners = booleanPreferencesKey("showMonthlyListeners")
    val RandomizeHomeOrder = booleanPreferencesKey("randomizeHomeOrder")
    val TopSize = stringPreferencesKey("topSize")
    val QuickPicks = stringPreferencesKey("quickPicks")
    val ProxyEnabled = booleanPreferencesKey("proxyEnabled")
    val ProxyType = stringPreferencesKey("proxyType")
    val ProxyUrl = stringPreferencesKey("proxyUrl")
    val ProxyUsername = stringPreferencesKey("proxyUsername")
    val ProxyPassword = stringPreferencesKey("proxyPassword")

    // Audio, Cache, Privacy & Playback
    val AudioQuality = stringPreferencesKey("audioQuality")
    val EnableSongCache = booleanPreferencesKey("enableSongCache")
    val MaxSongCacheSize = intPreferencesKey("maxSongCacheSize")
    val MaxImageCacheSize = intPreferencesKey("maxImageCacheSize")
    val PauseListenHistory = booleanPreferencesKey("pauseListenHistory")
    val PauseSearchHistory = booleanPreferencesKey("pauseSearchHistory")
    val HistoryDuration = floatPreferencesKey("historyDuration")
    val CrossfadeEnabled = booleanPreferencesKey("crossfadeEnabled")
    val CrossfadeDuration = floatPreferencesKey("crossfadeDuration")
    val CrossfadeGapless = booleanPreferencesKey("crossfadeGapless")
    val SkipSilence = booleanPreferencesKey("skipSilence")
    val AudioNormalization = booleanPreferencesKey("audioNormalization")
    val LoudnessLevel = stringPreferencesKey("loudnessLevel")
    val Varispeed = booleanPreferencesKey("varispeed")
    val PlaybackPitch = floatPreferencesKey("desktopPlaybackPitch")
    val SeekExtraSeconds = booleanPreferencesKey("seekExtraSeconds")
    val PersistentQueue = booleanPreferencesKey("persistentQueue")
    val Autoplay = booleanPreferencesKey("autoplay")
    val AutoLoadMore = booleanPreferencesKey("autoLoadMore")
    val DisableLoadMoreWhenRepeatAll = booleanPreferencesKey("disableLoadMoreWhenRepeatAll")
    val AutoDownloadOnLike = booleanPreferencesKey("autoDownloadOnLike")
    val SimilarContent = booleanPreferencesKey("similarContent")
    val RememberShuffleAndRepeat = booleanPreferencesKey("rememberShuffleAndRepeat")
    val PersistentShuffleAcrossQueues = booleanPreferencesKey("persistentShuffleAcrossQueues")
    val ShufflePlaylistFirst = booleanPreferencesKey("shufflePlaylistFirst")
    val PreventDuplicateTracksInQueue = booleanPreferencesKey("preventDuplicateTracksInQueue")
    val AutoSkipNextOnError = booleanPreferencesKey("autoSkipNextOnError")
    val PauseOnMute = booleanPreferencesKey("pauseOnMute")
    val PlaybackSpeed = floatPreferencesKey("desktopPlaybackSpeed")
    val EqEnabled = booleanPreferencesKey("desktopEqEnabled")
    val EqPreset = stringPreferencesKey("desktopEqPreset")
    val EqPreamp = floatPreferencesKey("desktopEqPreamp")
    val EqBands = stringPreferencesKey("desktopEqBands")

    // Sleep timer
    val SleepTimerEnabled = booleanPreferencesKey("sleepTimerEnabled")
    val SleepTimerRepeat = stringPreferencesKey("sleepTimerRepeat")
    val SleepTimerStartTime = stringPreferencesKey("sleepTimerStartTime")
    val SleepTimerEndTime = stringPreferencesKey("sleepTimerEndTime")
    val SleepTimerCustomDays = stringPreferencesKey("sleepTimerCustomDays")
    val SleepTimerStopAfterCurrentSong = booleanPreferencesKey("sleepTimerStopAfterCurrentSong")
    val SleepTimerFadeOut = booleanPreferencesKey("sleepTimerFadeOut")
    val SleepTimerDefault = intPreferencesKey("sleepTimerDefault")

    // Lyrics providers & Romanization
    val EnableLrcLib = booleanPreferencesKey("enableLrclib")
    val EnableKugou = booleanPreferencesKey("enableKugou")
    val EnableBetterLyrics = booleanPreferencesKey("enableBetterLyrics")
    val EnablePaxsenix = booleanPreferencesKey("enablePaxsenix")
    val EnableLyricsPlus = booleanPreferencesKey("enableLyricsPlus")
    val EnableZemer = booleanPreferencesKey("enableZemer")
    val LyricsProviderOrder = stringPreferencesKey("lyricsProviderOrder")
    val LyricsRomanizeAsMain = booleanPreferencesKey("lyricsRomanizeAsMain")
    val LyricsRomanizeCyrillicByLine = booleanPreferencesKey("lyricsRomanizeCyrillicByLine")
    val LyricsRomanizeList = stringPreferencesKey("lyricsRomanizeList")

    // AI Lyrics Translation
    val AiProvider = stringPreferencesKey("aiProvider")
    val OpenRouterBaseUrl = stringPreferencesKey("openRouterBaseUrl")
    val OpenRouterApiKey = stringPreferencesKey("openRouterApiKey")
    val OpenRouterModel = stringPreferencesKey("openRouterModel")
    val DeeplApiKey = stringPreferencesKey("deeplApiKey")
    val DeeplFormality = stringPreferencesKey("deeplFormality")
    val TranslateMode = stringPreferencesKey("translateMode")
    val TranslateLanguage = stringPreferencesKey("translateLanguage")
    val AiSystemPrompt = stringPreferencesKey("aiSystemPrompt")

    // YouTube Music Auth & Sync
    val InnerTubeCookie = stringPreferencesKey("innerTubeCookie")
    val VisitorData = stringPreferencesKey("visitorData")
    val DataSyncId = stringPreferencesKey("dataSyncId")
    val InnerTubeAuthUser = stringPreferencesKey("innerTubeAuthUser")
    val AccountName = stringPreferencesKey("accountName")
    val AccountEmail = stringPreferencesKey("accountEmail")
    val AccountChannelHandle = stringPreferencesKey("accountChannelHandle")
    val YtmSync = booleanPreferencesKey("ytmSync")
    val UseLoginForBrowse = booleanPreferencesKey("useLoginForBrowse")

    // Discord RPC
    val EnableDiscordRPC = booleanPreferencesKey("discordRPCEnable")
    val DiscordToken = stringPreferencesKey("discordToken")
    val DiscordUsername = stringPreferencesKey("discordUsername")
    val DiscordStateTemplate = stringPreferencesKey("discordStateTemplate")
    val DiscordDetailsTemplate = stringPreferencesKey("discordDetailsTemplate")
    val DiscordActivityType = stringPreferencesKey("discordActivityType")
    val DiscordActivityName = stringPreferencesKey("discordActivityName")
    val DiscordButton1Enabled = booleanPreferencesKey("discordButton1Enabled")
    val DiscordButton1Text = stringPreferencesKey("discordButton1Text")
    val DiscordButton1Url = stringPreferencesKey("discordButton1Url")
    val DiscordButton2Enabled = booleanPreferencesKey("discordButton2Enabled")
    val DiscordButton2Text = stringPreferencesKey("discordButton2Text")
    val DiscordButton2Url = stringPreferencesKey("discordButton2Url")
    val DiscordUserStatus = stringPreferencesKey("discordUserStatus")

    // Last.fm
    val EnableLastFMScrobbling = booleanPreferencesKey("lastfmScrobblingEnable")
    val LastFMSession = stringPreferencesKey("lastfmSession")
    val LastFMUsername = stringPreferencesKey("lastfmUsername")
    val LastFMUseNowPlaying = booleanPreferencesKey("lastfmUseNowPlaying")
    val LastFMUseSendLikes = booleanPreferencesKey("lastfmUseSendLikes")
    val LastFMApiKey = stringPreferencesKey("desktopLastfmApiKey")
    val LastFMSecret = stringPreferencesKey("desktopLastfmSecret")
    val ScrobbleMinSongDuration = intPreferencesKey("scrobbleMinSongDuration")
    val ScrobbleDelayPercent = floatPreferencesKey("scrobbleDelayPercent")
    val ScrobbleDelaySeconds = intPreferencesKey("scrobbleDelaySeconds")

    // Listen Together
    val ListenTogetherServerUrl = stringPreferencesKey("listenTogetherServerUrl")
    val ListenTogetherUsername = stringPreferencesKey("listenTogetherUsername")
    val ListenTogetherAutoApproval = booleanPreferencesKey("listenTogetherAutoApproval")
    val ListenTogetherAutoApproveSuggestions = booleanPreferencesKey("listenTogetherAutoApproveSuggestions")
    val ListenTogetherSyncVolume = booleanPreferencesKey("listenTogetherSyncVolume")
    val ListenTogetherBlockedUsers = stringPreferencesKey("listenTogetherBlockedUsers")

    // Alarms
    val AlarmEntries = stringPreferencesKey("alarmEntries")

    // Updater & Desktop-specific
    val CheckForUpdates = booleanPreferencesKey("checkForUpdates")
    val Volume = floatPreferencesKey("desktopVolume")
    val DownloadsDir = stringPreferencesKey("desktopDownloadsDir")
    val MinimizeToTray = booleanPreferencesKey("desktopMinimizeToTray")
    val EnableSmtc = booleanPreferencesKey("desktopEnableSmtc")
    val WindowWidth = intPreferencesKey("desktopWindowWidth")
    val WindowHeight = intPreferencesKey("desktopWindowHeight")
}

enum class DesktopDarkMode { SYSTEM, LIGHT, DARK }
enum class DesktopAudioQuality { AUTO, LOW, HIGH }
enum class DesktopLoudnessLevel(val targetLufs: Double, val label: String) {
    AGGRESSIVE(-7.0, "Agresivo (-7 LUFS)"),
    LOUD(-11.0, "Alto (-11 LUFS)"),
    BALANCED(-14.0, "Equilibrado (-14 LUFS)"),
    QUIET(-19.0, "Suave (-19 LUFS)"),
}
enum class DesktopPlayerBackground { DEFAULT, GRADIENT, BLUR }
enum class DesktopSliderStyle { DEFAULT, WAVY, SLIM }
enum class DesktopLyricsPosition { LEFT, CENTER, RIGHT }
enum class DesktopGridSize { BIG, SMALL }
enum class DesktopQuickPicksMode { QUICK_PICKS, LAST_LISTEN }
enum class DesktopPlaylistAddPosition { BEGINNING, END }
enum class DesktopProxyType { HTTP, SOCKS }

val DefaultLyricsProviderOrder = listOf(
    "BetterLyrics",
    "LrcLib",
    "Paxsenix",
    "LyricsPlus",
    "KuGou",
    "Zemer",
    "YouTubeSubtitle",
    "YouTube",
)

val AllRomanizationLanguages = listOf(
    "ja" to "Japonés",
    "ko" to "Coreano",
    "zh" to "Chino",
    "hi" to "Hindi",
    "pa" to "Punjabi",
    "ru" to "Ruso",
    "uk" to "Ucraniano",
    "sr" to "Serbio",
    "bg" to "Búlgaro",
    "be" to "Bielorruso",
    "ky" to "Kirguís",
    "mk" to "Macedonio",
)

data class DesktopSettings(
    val darkMode: DesktopDarkMode = DesktopDarkMode.DARK,
    val pureBlack: Boolean = false,
    val dynamicTheme: Boolean = true,
    val selectedThemeColor: Int = 0xFFED5564.toInt(),
    val playerBackground: DesktopPlayerBackground = DesktopPlayerBackground.GRADIENT,
    val sliderStyle: DesktopSliderStyle = DesktopSliderStyle.DEFAULT,
    val gridSize: DesktopGridSize = DesktopGridSize.BIG,
    val densityScale: Float = 1.0f,
    val defaultOpenTab: String = "HOME",
    val showLikedPlaylist: Boolean = true,
    val showDownloadedPlaylist: Boolean = true,
    val showTopPlaylist: Boolean = true,
    val showCachedPlaylist: Boolean = true,
    val showUploadedPlaylist: Boolean = true,
    val showWrappedCard: Boolean = true,

    val lyricsTextSize: Float = 18f,
    val lyricsLineSpacing: Float = 1.3f,
    val lyricsPosition: DesktopLyricsPosition = DesktopLyricsPosition.CENTER,
    val lyricsAnimationStyle: String = "KARAOKE",
    val respectAgentPositioning: Boolean = true,
    val showIntervalIndicator: Boolean = true,
    val lyricsClick: Boolean = true,
    val lyricsScroll: Boolean = true,

    val contentLanguage: String = "SYSTEM_DEFAULT",
    val contentCountry: String = "SYSTEM_DEFAULT",
    val hideExplicit: Boolean = false,
    val hideVideoSongs: Boolean = false,
    val hideYoutubeShorts: Boolean = false,
    val addToPlaylistPosition: DesktopPlaylistAddPosition = DesktopPlaylistAddPosition.END,
    val showArtistDescription: Boolean = true,
    val showArtistSubscriberCount: Boolean = true,
    val showMonthlyListeners: Boolean = true,
    val randomizeHomeOrder: Boolean = false,
    val topSize: Int = 50,
    val quickPicksMode: DesktopQuickPicksMode = DesktopQuickPicksMode.QUICK_PICKS,
    val proxyEnabled: Boolean = false,
    val proxyType: DesktopProxyType = DesktopProxyType.HTTP,
    val proxyUrl: String = "",
    val proxyUsername: String = "",
    val proxyPassword: String = "",

    val audioQuality: DesktopAudioQuality = DesktopAudioQuality.HIGH,
    val enableSongCache: Boolean = true,
    val maxSongCacheSizeMb: Int = 1024,
    val maxImageCacheSizeMb: Int = 512,
    val pauseListenHistory: Boolean = false,
    val pauseSearchHistory: Boolean = false,
    val historyDurationSec: Float = 10f,
    val crossfadeEnabled: Boolean = false,
    val crossfadeDurationSec: Float = 5f,
    val crossfadeGapless: Boolean = true,
    val skipSilence: Boolean = false,
    val audioNormalization: Boolean = true,
    val loudnessLevel: DesktopLoudnessLevel = DesktopLoudnessLevel.BALANCED,
    val varispeed: Boolean = false,
    val playbackPitch: Float = 1.0f,
    val seekExtraSeconds: Boolean = false,
    val persistentQueue: Boolean = true,
    val autoplay: Boolean = true,
    val autoLoadMore: Boolean = true,
    val disableLoadMoreWhenRepeatAll: Boolean = false,
    val autoDownloadOnLike: Boolean = false,
    val similarContent: Boolean = true,
    val rememberShuffleAndRepeat: Boolean = true,
    val persistentShuffleAcrossQueues: Boolean = false,
    val shufflePlaylistFirst: Boolean = false,
    val preventDuplicateTracksInQueue: Boolean = true,
    val autoSkipNextOnError: Boolean = true,
    val pauseOnMute: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val eqEnabled: Boolean = false,
    val eqPreset: String = "Flat",
    val eqPreamp: Float = 0f,
    val eqBands: List<Float> = List(10) { 0f },

    val sleepTimerEnabled: Boolean = false,
    val sleepTimerRepeat: String = "DAILY",
    val sleepTimerStartTime: String = "23:00",
    val sleepTimerEndTime: String = "06:00",
    val sleepTimerCustomDays: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
    val sleepTimerStopAfterCurrentSong: Boolean = false,
    val sleepTimerFadeOut: Boolean = true,
    val sleepTimerDefaultMinutes: Int = 30,

    val enableLrcLib: Boolean = true,
    val enableKugou: Boolean = true,
    val enableBetterLyrics: Boolean = true,
    val enablePaxsenix: Boolean = true,
    val enableLyricsPlus: Boolean = true,
    val enableZemer: Boolean = true,
    val lyricsProviderOrder: List<String> = DefaultLyricsProviderOrder,
    val lyricsRomanizeAsMain: Boolean = false,
    val lyricsRomanizeCyrillicByLine: Boolean = false,
    val lyricsRomanizeLanguages: Set<String> = setOf("ja", "ko", "ru", "uk", "hi", "pa"),

    val aiProvider: String = "OpenRouter",
    val openRouterBaseUrl: String = "https://openrouter.ai/api/v1/chat/completions",
    val openRouterApiKey: String = "",
    val openRouterModel: String = "google/gemini-2.5-flash",
    val deeplApiKey: String = "",
    val deeplFormality: String = "default",
    val translateMode: String = "Literal",
    val translateLanguage: String = "es",
    val aiSystemPrompt: String = "",

    val cookie: String = "",
    val visitorData: String = "",
    val dataSyncId: String = "",
    val authUser: String = "0",
    val accountName: String = "",
    val accountEmail: String = "",
    val accountChannelHandle: String = "",
    val ytmSync: Boolean = true,
    val useLoginForBrowse: Boolean = true,

    val enableDiscordRpc: Boolean = false,
    val discordToken: String = "",
    val discordUsername: String = "",
    val discordStateTemplate: String = "{artist.name}",
    val discordDetailsTemplate: String = "{song.name}",
    val discordActivityType: String = "LISTENING",
    val discordActivityName: String = "miliMusic",
    val discordButton1Enabled: Boolean = true,
    val discordButton1Text: String = "Escuchar en YouTube Music",
    val discordButton1Url: String = "https://music.youtube.com/watch?v={song.id}",
    val discordButton2Enabled: Boolean = false,
    val discordButton2Text: String = "miliMusic",
    val discordButton2Url: String = "https://github.com/angelvalentin-dev/miliMusic",
    val discordUserStatus: String = "online",

    val enableLastFmScrobbling: Boolean = false,
    val lastFmSession: String = "",
    val lastFmUsername: String = "",
    val lastFmUseNowPlaying: Boolean = true,
    val lastFmUseSendLikes: Boolean = false,
    val lastFmApiKey: String = "",
    val lastFmSecret: String = "",
    val scrobbleMinSongDurationSec: Int = 30,
    val scrobbleDelayPercent: Float = 0.5f,
    val scrobbleDelaySeconds: Int = 180,

    val listenTogetherServerUrl: String = "wss://metroserverx.meowery.eu/ws",
    val listenTogetherUsername: String = "",
    val listenTogetherAutoApproval: Boolean = false,
    val listenTogetherAutoApproveSuggestions: Boolean = false,
    val listenTogetherSyncVolume: Boolean = false,
    val listenTogetherBlockedUsers: Set<String> = emptySet(),

    val alarmEntriesJson: String = "",

    val checkForUpdates: Boolean = true,
    val volume: Float = 0.85f,
    val downloadsDir: String = AppPaths.defaultDownloadsDir.absolutePath,
    val minimizeToTray: Boolean = false,
    val enableSmtc: Boolean = true,
    val windowWidth: Int = 1280,
    val windowHeight: Int = 820,
) {
    val isLoggedIn: Boolean get() = cookie.contains("SAPISID")
}

class DesktopPreferences(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val preferencesFile: java.io.File = AppPaths.preferencesFile,
) {
    private var store: DataStore<Preferences> = createStore()

    private fun createStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.createWithPath(
            scope = scope,
            produceFile = { preferencesFile.apply { parentFile?.mkdirs() }.toOkioPath() },
        )

    val settingsFlow: Flow<DesktopSettings>
        get() = store.data.map { prefs ->
            val rawBands = prefs[DesktopPrefKeys.EqBands].orEmpty()
                .split(",")
                .mapNotNull { it.trim().toFloatOrNull() }
            val eqBands = if (rawBands.size == 10) rawBands else List(10) { 0f }

            val savedOrder = prefs[DesktopPrefKeys.LyricsProviderOrder].orEmpty()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val providerOrder = if (savedOrder.isNotEmpty()) {
                (savedOrder + DefaultLyricsProviderOrder.filter { it !in savedOrder }).distinct()
            } else {
                DefaultLyricsProviderOrder
            }

            val romanizeRaw = prefs[DesktopPrefKeys.LyricsRomanizeList]
            val romanizeSet = if (romanizeRaw != null) {
                romanizeRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            } else {
                setOf("ja", "ko", "ru", "uk", "hi", "pa")
            }

            val customDays = prefs[DesktopPrefKeys.SleepTimerCustomDays]?.split(",")
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?.filter { it in 1..7 }
                ?.toSet()
                ?.takeIf { it.isNotEmpty() }
                ?: setOf(1, 2, 3, 4, 5, 6, 7)

            val blockedUsers = prefs[DesktopPrefKeys.ListenTogetherBlockedUsers].orEmpty()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()

            DesktopSettings(
                darkMode = runCatching {
                    val raw = prefs[DesktopPrefKeys.DarkMode] ?: "DARK"
                    when (raw.uppercase()) {
                        "ON", "DARK" -> DesktopDarkMode.DARK
                        "OFF", "LIGHT" -> DesktopDarkMode.LIGHT
                        else -> DesktopDarkMode.SYSTEM
                    }
                }.getOrDefault(DesktopDarkMode.DARK),
                pureBlack = prefs[DesktopPrefKeys.PureBlack] ?: false,
                dynamicTheme = prefs[DesktopPrefKeys.DynamicTheme] ?: true,
                selectedThemeColor = prefs[DesktopPrefKeys.SelectedThemeColor] ?: 0xFFED5564.toInt(),
                playerBackground = runCatching {
                    DesktopPlayerBackground.valueOf((prefs[DesktopPrefKeys.PlayerBackgroundStyle] ?: "GRADIENT").uppercase())
                }.getOrDefault(DesktopPlayerBackground.GRADIENT),
                sliderStyle = runCatching {
                    DesktopSliderStyle.valueOf((prefs[DesktopPrefKeys.SliderStyle] ?: "DEFAULT").uppercase())
                }.getOrDefault(DesktopSliderStyle.DEFAULT),
                gridSize = runCatching {
                    DesktopGridSize.valueOf((prefs[DesktopPrefKeys.GridItemsSize] ?: "BIG").uppercase())
                }.getOrDefault(DesktopGridSize.BIG),
                densityScale = (prefs[DesktopPrefKeys.DensityScale] ?: 1.0f).coerceIn(0.75f, 1.25f),
                defaultOpenTab = prefs[DesktopPrefKeys.DefaultOpenTab] ?: "HOME",
                showLikedPlaylist = prefs[DesktopPrefKeys.ShowLikedPlaylist] ?: true,
                showDownloadedPlaylist = prefs[DesktopPrefKeys.ShowDownloadedPlaylist] ?: true,
                showTopPlaylist = prefs[DesktopPrefKeys.ShowTopPlaylist] ?: true,
                showCachedPlaylist = prefs[DesktopPrefKeys.ShowCachedPlaylist] ?: true,
                showUploadedPlaylist = prefs[DesktopPrefKeys.ShowUploadedPlaylist] ?: true,
                showWrappedCard = prefs[DesktopPrefKeys.ShowWrappedCard] ?: true,

                lyricsTextSize = (prefs[DesktopPrefKeys.LyricsTextSize] ?: 18f).coerceIn(12f, 40f),
                lyricsLineSpacing = (prefs[DesktopPrefKeys.LyricsLineSpacing] ?: 1.3f).coerceIn(1.0f, 2.5f),
                lyricsPosition = runCatching {
                    DesktopLyricsPosition.valueOf((prefs[DesktopPrefKeys.LyricsTextPosition] ?: "CENTER").uppercase())
                }.getOrDefault(DesktopLyricsPosition.CENTER),
                lyricsAnimationStyle = prefs[DesktopPrefKeys.LyricsAnimationStyle] ?: "KARAOKE",
                respectAgentPositioning = prefs[DesktopPrefKeys.RespectAgentPositioning] ?: true,
                showIntervalIndicator = prefs[DesktopPrefKeys.ShowIntervalIndicator] ?: true,
                lyricsClick = prefs[DesktopPrefKeys.LyricsClick] ?: true,
                lyricsScroll = prefs[DesktopPrefKeys.LyricsScroll] ?: true,

                contentLanguage = prefs[DesktopPrefKeys.ContentLanguage] ?: "SYSTEM_DEFAULT",
                contentCountry = prefs[DesktopPrefKeys.ContentCountry] ?: "SYSTEM_DEFAULT",
                hideExplicit = prefs[DesktopPrefKeys.HideExplicit] ?: false,
                hideVideoSongs = prefs[DesktopPrefKeys.HideVideoSongs] ?: false,
                hideYoutubeShorts = prefs[DesktopPrefKeys.HideYoutubeShorts] ?: false,
                addToPlaylistPosition = runCatching {
                    DesktopPlaylistAddPosition.valueOf((prefs[DesktopPrefKeys.AddToPlaylistPosition] ?: "END").uppercase())
                }.getOrDefault(DesktopPlaylistAddPosition.END),
                showArtistDescription = prefs[DesktopPrefKeys.ShowArtistDescription] ?: true,
                showArtistSubscriberCount = prefs[DesktopPrefKeys.ShowArtistSubscriberCount] ?: true,
                showMonthlyListeners = prefs[DesktopPrefKeys.ShowMonthlyListeners] ?: true,
                randomizeHomeOrder = prefs[DesktopPrefKeys.RandomizeHomeOrder] ?: false,
                topSize = (prefs[DesktopPrefKeys.TopSize]?.toIntOrNull() ?: 50).coerceIn(5, 100),
                quickPicksMode = runCatching {
                    DesktopQuickPicksMode.valueOf((prefs[DesktopPrefKeys.QuickPicks] ?: "QUICK_PICKS").uppercase())
                }.getOrDefault(DesktopQuickPicksMode.QUICK_PICKS),
                proxyEnabled = prefs[DesktopPrefKeys.ProxyEnabled] ?: false,
                proxyType = runCatching {
                    DesktopProxyType.valueOf((prefs[DesktopPrefKeys.ProxyType] ?: "HTTP").uppercase())
                }.getOrDefault(DesktopProxyType.HTTP),
                proxyUrl = prefs[DesktopPrefKeys.ProxyUrl] ?: "",
                proxyUsername = prefs[DesktopPrefKeys.ProxyUsername] ?: "",
                proxyPassword = prefs[DesktopPrefKeys.ProxyPassword] ?: "",

                audioQuality = runCatching {
                    DesktopAudioQuality.valueOf(prefs[DesktopPrefKeys.AudioQuality] ?: "HIGH")
                }.getOrDefault(DesktopAudioQuality.HIGH),
                enableSongCache = prefs[DesktopPrefKeys.EnableSongCache] ?: true,
                maxSongCacheSizeMb = prefs[DesktopPrefKeys.MaxSongCacheSize] ?: 1024,
                maxImageCacheSizeMb = prefs[DesktopPrefKeys.MaxImageCacheSize] ?: 512,
                pauseListenHistory = prefs[DesktopPrefKeys.PauseListenHistory] ?: false,
                pauseSearchHistory = prefs[DesktopPrefKeys.PauseSearchHistory] ?: false,
                historyDurationSec = (prefs[DesktopPrefKeys.HistoryDuration] ?: 10f).coerceIn(1f, 100f),
                crossfadeEnabled = prefs[DesktopPrefKeys.CrossfadeEnabled] ?: false,
                crossfadeDurationSec = (prefs[DesktopPrefKeys.CrossfadeDuration] ?: 5f).coerceIn(1f, 15f),
                crossfadeGapless = prefs[DesktopPrefKeys.CrossfadeGapless] ?: true,
                skipSilence = prefs[DesktopPrefKeys.SkipSilence] ?: false,
                audioNormalization = prefs[DesktopPrefKeys.AudioNormalization] ?: true,
                loudnessLevel = runCatching {
                    DesktopLoudnessLevel.valueOf((prefs[DesktopPrefKeys.LoudnessLevel] ?: "BALANCED").uppercase())
                }.getOrDefault(DesktopLoudnessLevel.BALANCED),
                varispeed = prefs[DesktopPrefKeys.Varispeed] ?: false,
                playbackPitch = (prefs[DesktopPrefKeys.PlaybackPitch] ?: 1.0f).coerceIn(0.5f, 2.0f),
                seekExtraSeconds = prefs[DesktopPrefKeys.SeekExtraSeconds] ?: false,
                persistentQueue = prefs[DesktopPrefKeys.PersistentQueue] ?: true,
                autoplay = prefs[DesktopPrefKeys.Autoplay] ?: true,
                autoLoadMore = prefs[DesktopPrefKeys.AutoLoadMore] ?: true,
                disableLoadMoreWhenRepeatAll = prefs[DesktopPrefKeys.DisableLoadMoreWhenRepeatAll] ?: false,
                autoDownloadOnLike = prefs[DesktopPrefKeys.AutoDownloadOnLike] ?: false,
                similarContent = prefs[DesktopPrefKeys.SimilarContent] ?: true,
                rememberShuffleAndRepeat = prefs[DesktopPrefKeys.RememberShuffleAndRepeat] ?: true,
                persistentShuffleAcrossQueues = prefs[DesktopPrefKeys.PersistentShuffleAcrossQueues] ?: false,
                shufflePlaylistFirst = prefs[DesktopPrefKeys.ShufflePlaylistFirst] ?: false,
                preventDuplicateTracksInQueue = prefs[DesktopPrefKeys.PreventDuplicateTracksInQueue] ?: true,
                autoSkipNextOnError = prefs[DesktopPrefKeys.AutoSkipNextOnError] ?: true,
                pauseOnMute = prefs[DesktopPrefKeys.PauseOnMute] ?: false,
                playbackSpeed = (prefs[DesktopPrefKeys.PlaybackSpeed] ?: 1.0f).coerceIn(0.5f, 2.0f),
                eqEnabled = prefs[DesktopPrefKeys.EqEnabled] ?: false,
                eqPreset = prefs[DesktopPrefKeys.EqPreset] ?: "Flat",
                eqPreamp = (prefs[DesktopPrefKeys.EqPreamp] ?: 0f).coerceIn(-15f, 15f),
                eqBands = eqBands,

                sleepTimerEnabled = prefs[DesktopPrefKeys.SleepTimerEnabled] ?: false,
                sleepTimerRepeat = prefs[DesktopPrefKeys.SleepTimerRepeat] ?: "DAILY",
                sleepTimerStartTime = prefs[DesktopPrefKeys.SleepTimerStartTime] ?: "23:00",
                sleepTimerEndTime = prefs[DesktopPrefKeys.SleepTimerEndTime] ?: "06:00",
                sleepTimerCustomDays = customDays,
                sleepTimerStopAfterCurrentSong = prefs[DesktopPrefKeys.SleepTimerStopAfterCurrentSong] ?: false,
                sleepTimerFadeOut = prefs[DesktopPrefKeys.SleepTimerFadeOut] ?: true,
                sleepTimerDefaultMinutes = (prefs[DesktopPrefKeys.SleepTimerDefault] ?: 30).coerceIn(1, 240),

                enableLrcLib = prefs[DesktopPrefKeys.EnableLrcLib] ?: true,
                enableKugou = prefs[DesktopPrefKeys.EnableKugou] ?: true,
                enableBetterLyrics = prefs[DesktopPrefKeys.EnableBetterLyrics] ?: true,
                enablePaxsenix = prefs[DesktopPrefKeys.EnablePaxsenix] ?: true,
                enableLyricsPlus = prefs[DesktopPrefKeys.EnableLyricsPlus] ?: true,
                enableZemer = prefs[DesktopPrefKeys.EnableZemer] ?: true,
                lyricsProviderOrder = providerOrder,
                lyricsRomanizeAsMain = prefs[DesktopPrefKeys.LyricsRomanizeAsMain] ?: false,
                lyricsRomanizeCyrillicByLine = prefs[DesktopPrefKeys.LyricsRomanizeCyrillicByLine] ?: false,
                lyricsRomanizeLanguages = romanizeSet,

                aiProvider = prefs[DesktopPrefKeys.AiProvider] ?: "OpenRouter",
                openRouterBaseUrl = prefs[DesktopPrefKeys.OpenRouterBaseUrl]
                    ?: "https://openrouter.ai/api/v1/chat/completions",
                openRouterApiKey = prefs[DesktopPrefKeys.OpenRouterApiKey] ?: "",
                openRouterModel = prefs[DesktopPrefKeys.OpenRouterModel] ?: "google/gemini-2.5-flash",
                deeplApiKey = prefs[DesktopPrefKeys.DeeplApiKey] ?: "",
                deeplFormality = prefs[DesktopPrefKeys.DeeplFormality] ?: "default",
                translateMode = prefs[DesktopPrefKeys.TranslateMode] ?: "Literal",
                translateLanguage = prefs[DesktopPrefKeys.TranslateLanguage] ?: "es",
                aiSystemPrompt = prefs[DesktopPrefKeys.AiSystemPrompt] ?: "",

                cookie = prefs[DesktopPrefKeys.InnerTubeCookie] ?: "",
                visitorData = prefs[DesktopPrefKeys.VisitorData] ?: "",
                dataSyncId = prefs[DesktopPrefKeys.DataSyncId] ?: "",
                authUser = prefs[DesktopPrefKeys.InnerTubeAuthUser] ?: "0",
                accountName = prefs[DesktopPrefKeys.AccountName] ?: "",
                accountEmail = prefs[DesktopPrefKeys.AccountEmail] ?: "",
                accountChannelHandle = prefs[DesktopPrefKeys.AccountChannelHandle] ?: "",
                ytmSync = prefs[DesktopPrefKeys.YtmSync] ?: true,
                useLoginForBrowse = prefs[DesktopPrefKeys.UseLoginForBrowse] ?: true,

                enableDiscordRpc = prefs[DesktopPrefKeys.EnableDiscordRPC] ?: false,
                discordToken = prefs[DesktopPrefKeys.DiscordToken] ?: "",
                discordUsername = prefs[DesktopPrefKeys.DiscordUsername] ?: "",
                discordStateTemplate = prefs[DesktopPrefKeys.DiscordStateTemplate] ?: "{artist.name}",
                discordDetailsTemplate = prefs[DesktopPrefKeys.DiscordDetailsTemplate] ?: "{song.name}",
                discordActivityType = prefs[DesktopPrefKeys.DiscordActivityType] ?: "LISTENING",
                discordActivityName = prefs[DesktopPrefKeys.DiscordActivityName] ?: "miliMusic",
                discordButton1Enabled = prefs[DesktopPrefKeys.DiscordButton1Enabled] ?: true,
                discordButton1Text = prefs[DesktopPrefKeys.DiscordButton1Text] ?: "Escuchar en YouTube Music",
                discordButton1Url = prefs[DesktopPrefKeys.DiscordButton1Url]
                    ?: "https://music.youtube.com/watch?v={song.id}",
                discordButton2Enabled = prefs[DesktopPrefKeys.DiscordButton2Enabled] ?: false,
                discordButton2Text = prefs[DesktopPrefKeys.DiscordButton2Text] ?: "miliMusic",
                discordButton2Url = prefs[DesktopPrefKeys.DiscordButton2Url]
                    ?: "https://github.com/angelvalentin-dev/miliMusic",
                discordUserStatus = prefs[DesktopPrefKeys.DiscordUserStatus] ?: "online",

                enableLastFmScrobbling = prefs[DesktopPrefKeys.EnableLastFMScrobbling] ?: false,
                lastFmSession = prefs[DesktopPrefKeys.LastFMSession] ?: "",
                lastFmUsername = prefs[DesktopPrefKeys.LastFMUsername] ?: "",
                lastFmUseNowPlaying = prefs[DesktopPrefKeys.LastFMUseNowPlaying] ?: true,
                lastFmUseSendLikes = prefs[DesktopPrefKeys.LastFMUseSendLikes] ?: false,
                lastFmApiKey = prefs[DesktopPrefKeys.LastFMApiKey] ?: "",
                lastFmSecret = prefs[DesktopPrefKeys.LastFMSecret] ?: "",
                scrobbleMinSongDurationSec = (prefs[DesktopPrefKeys.ScrobbleMinSongDuration] ?: 30).coerceIn(10, 120),
                scrobbleDelayPercent = (prefs[DesktopPrefKeys.ScrobbleDelayPercent] ?: 0.5f).coerceIn(0.3f, 0.95f),
                scrobbleDelaySeconds = (prefs[DesktopPrefKeys.ScrobbleDelaySeconds] ?: 180).coerceIn(30, 360),

                listenTogetherServerUrl = prefs[DesktopPrefKeys.ListenTogetherServerUrl]
                    ?: "wss://metroserverx.meowery.eu/ws",
                listenTogetherUsername = prefs[DesktopPrefKeys.ListenTogetherUsername] ?: "",
                listenTogetherAutoApproval = prefs[DesktopPrefKeys.ListenTogetherAutoApproval] ?: false,
                listenTogetherAutoApproveSuggestions = prefs[DesktopPrefKeys.ListenTogetherAutoApproveSuggestions] ?: false,
                listenTogetherSyncVolume = prefs[DesktopPrefKeys.ListenTogetherSyncVolume] ?: false,
                listenTogetherBlockedUsers = blockedUsers,

                alarmEntriesJson = prefs[DesktopPrefKeys.AlarmEntries] ?: "",

                checkForUpdates = prefs[DesktopPrefKeys.CheckForUpdates] ?: true,
                volume = prefs[DesktopPrefKeys.Volume] ?: 0.85f,
                downloadsDir = prefs[DesktopPrefKeys.DownloadsDir] ?: AppPaths.defaultDownloadsDir.absolutePath,
                minimizeToTray = prefs[DesktopPrefKeys.MinimizeToTray] ?: false,
                enableSmtc = prefs[DesktopPrefKeys.EnableSmtc] ?: true,
                windowWidth = (prefs[DesktopPrefKeys.WindowWidth] ?: 1280).coerceIn(960, 3840),
                windowHeight = (prefs[DesktopPrefKeys.WindowHeight] ?: 820).coerceIn(640, 2160),
            )
        }

    suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        store.edit { it[key] = value }
    }

    suspend fun updateSession(
        cookie: String,
        visitorData: String,
        dataSyncId: String,
        authUser: String,
        accountName: String,
        accountEmail: String,
        accountChannelHandle: String,
    ) {
        store.edit { prefs ->
            prefs[DesktopPrefKeys.InnerTubeCookie] = cookie
            prefs[DesktopPrefKeys.VisitorData] = visitorData
            prefs[DesktopPrefKeys.DataSyncId] = dataSyncId
            prefs[DesktopPrefKeys.InnerTubeAuthUser] = authUser
            prefs[DesktopPrefKeys.AccountName] = accountName
            prefs[DesktopPrefKeys.AccountEmail] = accountEmail
            prefs[DesktopPrefKeys.AccountChannelHandle] = accountChannelHandle
        }
    }

    suspend fun clearSession() {
        store.edit { prefs ->
            prefs.remove(DesktopPrefKeys.InnerTubeCookie)
            prefs.remove(DesktopPrefKeys.VisitorData)
            prefs.remove(DesktopPrefKeys.DataSyncId)
            prefs.remove(DesktopPrefKeys.InnerTubeAuthUser)
            prefs.remove(DesktopPrefKeys.AccountName)
            prefs.remove(DesktopPrefKeys.AccountEmail)
            prefs.remove(DesktopPrefKeys.AccountChannelHandle)
        }
    }

    suspend fun importFromBytes(bytes: ByteArray) {
        val imported = PreferencesSerializer.readFrom(ByteArrayInputStream(bytes).source().buffer())
        store.updateData { imported }
    }
}
