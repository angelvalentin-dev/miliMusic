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
    // Keys shared with Android PreferenceKeys.kt for .backup compatibility
    val DarkMode = stringPreferencesKey("darkMode")
    val PureBlack = booleanPreferencesKey("pureBlack")
    val DynamicTheme = booleanPreferencesKey("dynamicTheme")
    val SelectedThemeColor = intPreferencesKey("selectedThemeColor")

    val ContentLanguage = stringPreferencesKey("contentLanguage")
    val ContentCountry = stringPreferencesKey("contentCountry")
    val AudioQuality = stringPreferencesKey("audioQuality")
    val EnableSongCache = booleanPreferencesKey("enableSongCache")
    val MaxSongCacheSize = intPreferencesKey("maxSongCacheSize")
    val PauseListenHistory = booleanPreferencesKey("pauseListenHistory")
    val PauseSearchHistory = booleanPreferencesKey("pauseSearchHistory")

    val EnableLrcLib = booleanPreferencesKey("enableLrclib")
    val EnableKugou = booleanPreferencesKey("enableKugou")
    val EnableBetterLyrics = booleanPreferencesKey("enableBetterLyrics")

    val InnerTubeCookie = stringPreferencesKey("innerTubeCookie")
    val VisitorData = stringPreferencesKey("visitorData")
    val DataSyncId = stringPreferencesKey("dataSyncId")
    val InnerTubeAuthUser = stringPreferencesKey("innerTubeAuthUser")
    val AccountName = stringPreferencesKey("accountName")
    val AccountEmail = stringPreferencesKey("accountEmail")
    val AccountChannelHandle = stringPreferencesKey("accountChannelHandle")
    val YtmSync = booleanPreferencesKey("ytmSync")

    // Desktop-specific settings
    val Volume = floatPreferencesKey("desktopVolume")
    val DownloadsDir = stringPreferencesKey("desktopDownloadsDir")
    val MinimizeToTray = booleanPreferencesKey("desktopMinimizeToTray")
    val EnableSmtc = booleanPreferencesKey("desktopEnableSmtc")
}

enum class DesktopDarkMode { SYSTEM, LIGHT, DARK }
enum class DesktopAudioQuality { AUTO, LOW, HIGH }

data class DesktopSettings(
    val darkMode: DesktopDarkMode = DesktopDarkMode.DARK,
    val pureBlack: Boolean = false,
    val dynamicTheme: Boolean = true,
    val selectedThemeColor: Int = 0xFFED5564.toInt(),
    val contentLanguage: String = "SYSTEM_DEFAULT",
    val contentCountry: String = "SYSTEM_DEFAULT",
    val audioQuality: DesktopAudioQuality = DesktopAudioQuality.HIGH,
    val enableSongCache: Boolean = true,
    val maxSongCacheSizeMb: Int = 1024,
    val pauseListenHistory: Boolean = false,
    val pauseSearchHistory: Boolean = false,
    val enableLrcLib: Boolean = true,
    val enableKugou: Boolean = true,
    val enableBetterLyrics: Boolean = true,
    val cookie: String = "",
    val visitorData: String = "",
    val dataSyncId: String = "",
    val authUser: String = "0",
    val accountName: String = "",
    val accountEmail: String = "",
    val accountChannelHandle: String = "",
    val ytmSync: Boolean = true,
    val volume: Float = 0.85f,
    val downloadsDir: String = AppPaths.defaultDownloadsDir.absolutePath,
    val minimizeToTray: Boolean = true,
    val enableSmtc: Boolean = true,
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
            DesktopSettings(
                darkMode = runCatching {
                    DesktopDarkMode.valueOf(prefs[DesktopPrefKeys.DarkMode] ?: "DARK")
                }.getOrDefault(DesktopDarkMode.DARK),
                pureBlack = prefs[DesktopPrefKeys.PureBlack] ?: false,
                dynamicTheme = prefs[DesktopPrefKeys.DynamicTheme] ?: true,
                selectedThemeColor = prefs[DesktopPrefKeys.SelectedThemeColor] ?: 0xFFED5564.toInt(),
                contentLanguage = prefs[DesktopPrefKeys.ContentLanguage] ?: "SYSTEM_DEFAULT",
                contentCountry = prefs[DesktopPrefKeys.ContentCountry] ?: "SYSTEM_DEFAULT",
                audioQuality = runCatching {
                    DesktopAudioQuality.valueOf(prefs[DesktopPrefKeys.AudioQuality] ?: "HIGH")
                }.getOrDefault(DesktopAudioQuality.HIGH),
                enableSongCache = prefs[DesktopPrefKeys.EnableSongCache] ?: true,
                maxSongCacheSizeMb = prefs[DesktopPrefKeys.MaxSongCacheSize] ?: 1024,
                pauseListenHistory = prefs[DesktopPrefKeys.PauseListenHistory] ?: false,
                pauseSearchHistory = prefs[DesktopPrefKeys.PauseSearchHistory] ?: false,
                enableLrcLib = prefs[DesktopPrefKeys.EnableLrcLib] ?: true,
                enableKugou = prefs[DesktopPrefKeys.EnableKugou] ?: true,
                enableBetterLyrics = prefs[DesktopPrefKeys.EnableBetterLyrics] ?: true,
                cookie = prefs[DesktopPrefKeys.InnerTubeCookie] ?: "",
                visitorData = prefs[DesktopPrefKeys.VisitorData] ?: "",
                dataSyncId = prefs[DesktopPrefKeys.DataSyncId] ?: "",
                authUser = prefs[DesktopPrefKeys.InnerTubeAuthUser] ?: "0",
                accountName = prefs[DesktopPrefKeys.AccountName] ?: "",
                accountEmail = prefs[DesktopPrefKeys.AccountEmail] ?: "",
                accountChannelHandle = prefs[DesktopPrefKeys.AccountChannelHandle] ?: "",
                ytmSync = prefs[DesktopPrefKeys.YtmSync] ?: true,
                volume = prefs[DesktopPrefKeys.Volume] ?: 0.85f,
                downloadsDir = prefs[DesktopPrefKeys.DownloadsDir] ?: AppPaths.defaultDownloadsDir.absolutePath,
                minimizeToTray = prefs[DesktopPrefKeys.MinimizeToTray] ?: true,
                enableSmtc = prefs[DesktopPrefKeys.EnableSmtc] ?: true,
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
