package com.metrolist.desktop.audio

import com.metrolist.desktop.AppPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class EqFilterType { PK, LSC, HSC, LPQ, HPQ }

@Serializable
data class ParametricEqBand(
    val filterType: EqFilterType,
    val frequency: Double,
    val gain: Double,
    val q: Double,
)

@Serializable
data class ParametricEqProfile(
    val preamp: Double = 0.0,
    val bands: List<ParametricEqBand> = emptyList(),
)

@Serializable
data class SavedEqProfile(
    val id: String,
    val name: String,
    val source: String = "Custom",
    val preamp: Double = 0.0,
    val bands: List<ParametricEqBand> = emptyList(),
    val projectedVlcBands: List<Float> = List(10) { 0f },
)

object DesktopParametricEq {
    // True libVLC 3.0 10-band graphic equalizer center frequencies (Hz)
    val vlcBandFrequenciesHz = listOf(60.0, 170.0, 310.0, 600.0, 1000.0, 3000.0, 6000.0, 12000.0, 14000.0, 16000.0)
    val vlcBandLabels = listOf("60Hz", "170Hz", "310Hz", "600Hz", "1kHz", "3kHz", "6kHz", "12kHz", "14kHz", "16kHz")

    fun parseApoText(content: String): ParametricEqProfile {
        var preamp = 0.0
        val bands = mutableListOf<ParametricEqBand>()
        val preampRegex = Regex("""Preamp:\s*([-+]?\d+(?:\.\d+)?)\s*dB""", RegexOption.IGNORE_CASE)
        val fcRegex = Regex("""Fc\s+([-+]?\d+(?:\.\d+)?)\s*Hz""", RegexOption.IGNORE_CASE)
        val gainRegex = Regex("""Gain\s+([-+]?\d+(?:\.\d+)?)\s*dB""", RegexOption.IGNORE_CASE)
        val qRegex = Regex("""Q\s+([-+]?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

        for (rawLine in content.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("Preamp:", ignoreCase = true)) {
                preamp = preampRegex.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            } else if (line.startsWith("Filter", ignoreCase = true) && line.contains("ON", ignoreCase = true)) {
                val type = when {
                    line.contains("LSC", ignoreCase = true) -> EqFilterType.LSC
                    line.contains("HSC", ignoreCase = true) -> EqFilterType.HSC
                    line.contains("PK", ignoreCase = true) -> EqFilterType.PK
                    line.contains("LPQ", ignoreCase = true) -> EqFilterType.LPQ
                    line.contains("HPQ", ignoreCase = true) -> EqFilterType.HPQ
                    else -> null
                } ?: continue
                val fc = fcRegex.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
                val gain = gainRegex.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
                val q = qRegex.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.707
                if (fc in 1.0..48000.0 && q > 0.0) {
                    bands += ParametricEqBand(type, fc, gain.coerceIn(-25.0, 25.0), q.coerceIn(0.05, 20.0))
                }
            }
        }
        return ParametricEqProfile(preamp = preamp.coerceIn(-20.0, 20.0), bands = bands)
    }

    /** Computes exact digital biquad filter magnitude in dB at [freqHz] (Audio EQ Cookbook). */
    fun evaluateBandDb(band: ParametricEqBand, freqHz: Double, sampleRate: Double = 48_000.0): Double {
        if (band.frequency <= 0.0 || band.frequency >= sampleRate / 2.0) return 0.0
        val w0 = 2.0 * PI * band.frequency / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0 * band.q.coerceAtLeast(0.05))
        val a = 10.0.pow(band.gain / 40.0)

        var b0: Double
        var b1: Double
        var b2: Double
        var a0: Double
        var a1: Double
        var a2: Double

        when (band.filterType) {
            EqFilterType.PK -> {
                b0 = 1.0 + alpha * a
                b1 = -2.0 * cosW0
                b2 = 1.0 - alpha * a
                a0 = 1.0 + alpha / a
                a1 = -2.0 * cosW0
                a2 = 1.0 - alpha / a
            }
            EqFilterType.LSC -> {
                val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
                b0 = a * ((a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha)
                b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)
                b2 = a * ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha)
                a0 = (a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha
                a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW0)
                a2 = (a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha
            }
            EqFilterType.HSC -> {
                val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
                b0 = a * ((a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha)
                b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)
                b2 = a * ((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha)
                a0 = (a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha
                a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW0)
                a2 = (a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha
            }
            EqFilterType.LPQ -> {
                b0 = (1.0 - cosW0) / 2.0
                b1 = 1.0 - cosW0
                b2 = (1.0 - cosW0) / 2.0
                a0 = 1.0 + alpha
                a1 = -2.0 * cosW0
                a2 = 1.0 - alpha
            }
            EqFilterType.HPQ -> {
                b0 = (1.0 + cosW0) / 2.0
                b1 = -(1.0 + cosW0)
                b2 = (1.0 + cosW0) / 2.0
                a0 = 1.0 + alpha
                a1 = -2.0 * cosW0
                a2 = 1.0 - alpha
            }
        }

        val w = 2.0 * PI * freqHz / sampleRate
        val cosW = cos(w)
        val sinW = sin(w)
        val cos2W = cos(2.0 * w)
        val sin2W = sin(2.0 * w)

        val numRe = b0 + b1 * cosW + b2 * cos2W
        val numIm = -(b1 * sinW + b2 * sin2W)
        val denRe = a0 + a1 * cosW + a2 * cos2W
        val denIm = -(a1 * sinW + a2 * sin2W)

        val mag = hypot(numRe, numIm) / hypot(denRe, denIm).coerceAtLeast(1e-12)
        return (20.0 * log10(mag.coerceAtLeast(1e-6)))
    }

    fun evaluateProfileDb(profile: ParametricEqProfile, freqHz: Double): Double =
        profile.preamp + profile.bands.sumOf { evaluateBandDb(it, freqHz) }

    /** Projects an arbitrary parametric EQ profile onto libVLC's 10 graphic bands + preamp. */
    fun projectToVlc10Bands(profile: ParametricEqProfile): Pair<Float, List<Float>> {
        val gains = vlcBandFrequenciesHz.map { hz ->
            profile.bands.sumOf { evaluateBandDb(it, hz) }.toFloat().coerceIn(-15f, 15f)
        }
        val preamp = profile.preamp.toFloat().coerceIn(-15f, 15f)
        return preamp to gains
    }

    /** Evaluates frequency response curve of the active 10-band graphic EQ for Canvas visualization. */
    fun evaluateGraphicCurve(preamp: Float, bands: List<Float>, points: Int = 64): List<Pair<Double, Double>> {
        val syntheticBands = vlcBandFrequenciesHz.mapIndexed { idx, hz ->
            ParametricEqBand(EqFilterType.PK, hz, (bands.getOrNull(idx) ?: 0f).toDouble(), 1.4)
        }
        val profile = ParametricEqProfile(preamp = preamp.toDouble(), bands = syntheticBands)
        val minLog = log10(20.0)
        val maxLog = log10(20_000.0)
        return (0 until points).map { i ->
            val t = i.toDouble() / (points - 1).coerceAtLeast(1)
            val hz = 10.0.pow(minLog + (maxLog - minLog) * t)
            hz to evaluateProfileDb(profile, hz).coerceIn(-18.0, 18.0)
        }
    }
}

data class AutoEqEntry(
    val label: String,
    val form: String,
    val rig: String,
    val source: String,
    val formDirectory: String,
) {
    val repoPath: String get() = "results/$source/$formDirectory/$label/$label ParametricEQ.txt"
}

class DesktopAutoEqSearch(
    private val cacheDir: File = File(AppPaths.dataDir, "autoeq_cache"),
) {
    @Serializable
    private data class GitHubTreeNode(val path: String, val type: String)

    @Serializable
    private data class GitHubTreeResponse(val tree: List<GitHubTreeNode> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val treeFile = File(cacheDir, "tree.json")
    private val treeTsFile = File(cacheDir, "tree_timestamp")
    private var entries: List<AutoEqEntry> = emptyList()

    companion object {
        private const val TREE_API_URL = "https://api.github.com/repos/ndellagrotte/AutoEq/git/trees/master?recursive=1"
        private const val RAW_BASE_URL = "https://raw.githubusercontent.com/ndellagrotte/AutoEq/master"
        private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
    }

    suspend fun ensureIndexLoaded(forceRefresh: Boolean = false): Result<Int> = withContext(Dispatchers.IO) {
        if (entries.isNotEmpty() && !forceRefresh) return@withContext Result.success(entries.size)
        runCatching {
            cacheDir.mkdirs()
            val treeJson = fetchTreeJson(forceRefresh) ?: error("No se pudo descargar la base de datos AutoEQ")
            val parsed = json.decodeFromString<GitHubTreeResponse>(treeJson)
            entries = parsed.tree.mapNotNull { node ->
                if (node.type != "blob" || !node.path.startsWith("results/") || !node.path.endsWith(" ParametricEQ.txt")) {
                    return@mapNotNull null
                }
                val parts = node.path.split("/")
                if (parts.size < 5) return@mapNotNull null
                val source = parts[1]
                val formDir = parts[2]
                val label = parts[3]
                val form = listOf("in-ear", "over-ear", "earbud").firstOrNull { formDir.contains(it, true) } ?: "headphone"
                val rig = formDir.replace(form, "", ignoreCase = true).trim().ifBlank { source }
                AutoEqEntry(label = label, form = form, rig = rig, source = source, formDirectory = formDir)
            }
            entries.size
        }
    }

    fun search(query: String, maxResults: Int = 60): List<AutoEqEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return entries.take(maxResults)
        return entries
            .filter { it.label.lowercase().contains(q) || it.source.lowercase().contains(q) }
            .sortedWith(
                compareByDescending<AutoEqEntry> {
                    val low = it.label.lowercase()
                    when {
                        low == q -> 3
                        low.startsWith(q) -> 2
                        else -> 1
                    }
                }.thenBy { it.label },
            )
            .take(maxResults)
    }

    suspend fun loadProfile(entry: AutoEqEntry): Result<ParametricEqProfile> = withContext(Dispatchers.IO) {
        runCatching {
            val cacheFile = File(cacheDir, entry.repoPath.replace("/", File.separator))
            val content = if (cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < CACHE_TTL_MS) {
                cacheFile.readText()
            } else {
                val encodedPath = entry.repoPath.split("/").joinToString("/") {
                    URLEncoder.encode(it, "UTF-8").replace("+", "%20")
                }
                val req = Request.Builder().url("$RAW_BASE_URL/$encodedPath").build()
                client.newCall(req).execute().use { resp ->
                    check(resp.isSuccessful) { "HTTP ${resp.code} descargando perfil AutoEQ" }
                    resp.body.string().also { body ->
                        cacheFile.parentFile?.mkdirs()
                        cacheFile.writeText(body)
                    }
                }
            }
            DesktopParametricEq.parseApoText(content)
        }
    }

    private fun fetchTreeJson(forceRefresh: Boolean): String? {
        if (!forceRefresh && treeFile.exists() && treeTsFile.exists()) {
            val ts = treeTsFile.readText().trim().toLongOrNull() ?: 0L
            if (System.currentTimeMillis() - ts < CACHE_TTL_MS) return treeFile.readText()
        }
        return runCatching {
            val req = Request.Builder()
                .url(TREE_API_URL)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return if (treeFile.exists()) treeFile.readText() else null
                val body = resp.body.string()
                treeFile.writeText(body)
                treeTsFile.writeText(System.currentTimeMillis().toString())
                body
            }
        }.getOrElse { if (treeFile.exists()) treeFile.readText() else null }
    }

    fun close() {
        runCatching { client.dispatcher.cancelAll() }
        runCatching { client.dispatcher.executorService.shutdownNow() }
        runCatching { client.connectionPool.evictAll() }
    }
}

object DesktopEqProfileStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file: File by lazy { File(AppPaths.dataDir, "eq_profiles.json") }

    fun loadAll(): List<SavedEqProfile> =
        runCatching {
            if (file.exists()) json.decodeFromString<List<SavedEqProfile>>(file.readText()) else emptyList()
        }.getOrDefault(emptyList())

    fun save(profile: SavedEqProfile): List<SavedEqProfile> {
        val updated = (loadAll().filterNot { it.id == profile.id } + profile).sortedBy { it.name.lowercase() }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(updated))
        }
        return updated
    }

    fun delete(profileId: String): List<SavedEqProfile> {
        val updated = loadAll().filterNot { it.id == profileId }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(updated))
        }
        return updated
    }
}
