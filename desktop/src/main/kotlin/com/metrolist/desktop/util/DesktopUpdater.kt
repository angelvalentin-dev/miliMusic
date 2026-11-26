package com.metrolist.desktop.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class DesktopReleaseAsset(
    val name: String = "",
    val browser_download_url: String = "",
    val size: Long = 0L,
)

@Serializable
data class DesktopReleaseInfo(
    val tag_name: String = "",
    val name: String = "",
    val body: String = "",
    val published_at: String = "",
    val html_url: String = "",
    val assets: List<DesktopReleaseAsset> = emptyList(),
) {
    val displayVersion: String get() = tag_name.removePrefix("v").ifBlank { name.removePrefix("v") }
}

object DesktopUpdater {
    const val CURRENT_VERSION = "13.7.0"
    private const val REPO_RELEASES_API = "https://api.github.com/repos/angelvalentin-dev/miliMusic/releases"
    private const val UPSTREAM_RELEASES_API = "https://api.github.com/repos/MetrolistGroup/Metrolist/releases"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedReleases: List<DesktopReleaseInfo> = emptyList()

    fun compareVersions(v1: String, v2: String): Int {
        val p1 = v1.removePrefix("v").substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
        val p2 = v2.removePrefix("v").substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(p1.size, p2.size)
        for (i in 0 until maxLen) {
            val a = p1.getOrNull(i) ?: 0
            val b = p2.getOrNull(i) ?: 0
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    fun isUpdateAvailable(currentVersion: String = CURRENT_VERSION, latestVersion: String): Boolean =
        compareVersions(latestVersion, currentVersion) > 0

    suspend fun fetchReleases(forceRefresh: Boolean = false): Result<List<DesktopReleaseInfo>> = withContext(Dispatchers.IO) {
        if (cachedReleases.isNotEmpty() && !forceRefresh) return@withContext Result.success(cachedReleases)
        runCatching {
            val primary = fetchFromUrl("$REPO_RELEASES_API?per_page=15")
            val list = if (primary.isNotEmpty()) primary else fetchFromUrl("$UPSTREAM_RELEASES_API?per_page=15")
            cachedReleases = list
            list
        }
    }

    suspend fun checkForUpdate(forceRefresh: Boolean = false): Result<Pair<DesktopReleaseInfo?, Boolean>> =
        fetchReleases(forceRefresh).map { releases ->
            val latest = releases.firstOrNull()
            val hasUpdate = latest != null && isUpdateAvailable(CURRENT_VERSION, latest.displayVersion)
            latest to hasUpdate
        }

    private fun fetchFromUrl(url: String): List<DesktopReleaseInfo> {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "miliMusic-Desktop/$CURRENT_VERSION")
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                json.decodeFromString<List<DesktopReleaseInfo>>(resp.body.string())
            }
        }.getOrDefault(emptyList())
    }

    fun close() {
        runCatching { client.dispatcher.cancelAll() }
        runCatching { client.dispatcher.executorService.shutdownNow() }
        runCatching { client.connectionPool.evictAll() }
    }
}
