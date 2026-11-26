package com.metrolist.desktop.recognition

import com.metrolist.desktop.db.DesktopDatabase
import com.metrolist.desktop.db.DesktopRecognitionHistoryItem
import com.metrolist.desktop.db.DesktopSong
import com.metrolist.desktop.db.toDesktopSong
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

sealed interface RecognitionState {
    data object Idle : RecognitionState
    data class Listening(val elapsedSec: Float, val amplitude: Float) : RecognitionState
    data object Processing : RecognitionState
    data class Success(val item: DesktopRecognitionHistoryItem, val matchedSong: DesktopSong?) : RecognitionState
    data class NoMatch(val message: String = "No se encontró ninguna coincidencia") : RecognitionState
    data class Error(val message: String) : RecognitionState
}

/** Pure-JVM port of the Shazam acoustic fingerprinting algorithm (Vibra / SongRec). */
object DesktopShazamSignatureGenerator {
    const val REQUIRED_SAMPLE_RATE = 16_000
    private const val FFT_SIZE = 2048
    private const val FFT_OUTPUT_SIZE = FFT_SIZE / 2 + 1
    private const val MAX_PEAKS = 255
    private const val MAX_TIME_SECONDS = 12.0
    private const val RING_BUF_SIZE = 256

    private val HANNING = DoubleArray(FFT_SIZE) { i ->
        0.5 * (1.0 - cos(2.0 * PI * (i + 1).toDouble() / 2049.0))
    }

    fun resampleTo16kMono(pcm16Le: ByteArray, sourceRate: Int): ByteArray {
        if (sourceRate == REQUIRED_SAMPLE_RATE) return pcm16Le
        val srcSamples = ShortArray(pcm16Le.size / 2)
        ByteBuffer.wrap(pcm16Le).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(srcSamples)
        val ratio = sourceRate.toDouble() / REQUIRED_SAMPLE_RATE.toDouble()
        val dstCount = (srcSamples.size / ratio).toInt().coerceAtLeast(1)
        val out = ByteBuffer.allocate(dstCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until dstCount) {
            val pos = i * ratio
            val idx = pos.toInt().coerceIn(0, srcSamples.lastIndex)
            val nextIdx = (idx + 1).coerceAtMost(srcSamples.lastIndex)
            val frac = pos - idx
            val sample = (srcSamples[idx] * (1.0 - frac) + srcSamples[nextIdx] * frac).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            out.putShort(sample)
        }
        return out.array()
    }

    fun fromI16(samples: ByteArray): String {
        require(samples.size >= 2 && samples.size % 2 == 0) {
            "PCM samples must be non-empty 16-bit audio"
        }
        val pcm = ShortArray(samples.size / 2)
        ByteBuffer.wrap(samples).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
        return State().process(pcm)
    }

    private data class Peak(val fftPassNumber: Int, val peakMagnitude: Int, val correctedBin: Int)

    private class State {
        private val samplesRing = IntArray(FFT_SIZE)
        private var samplesPos = 0
        private val fftOutputs = Array(RING_BUF_SIZE) { DoubleArray(FFT_OUTPUT_SIZE) }
        private var fftPos = 0
        private val spreadFfts = Array(RING_BUF_SIZE) { DoubleArray(FFT_OUTPUT_SIZE) }
        private var spreadPos = 0
        private var spreadNumWritten = 0
        private var numSamples = 0
        private val bandPeaks = Array(4) { mutableListOf<Peak>() }
        private var totalPeaks = 0

        fun process(pcm: ShortArray): String {
            var offset = 0
            while (offset + 128 <= pcm.size) {
                val elapsedSec = numSamples.toDouble() / REQUIRED_SAMPLE_RATE
                if (elapsedSec >= MAX_TIME_SECONDS && totalPeaks >= MAX_PEAKS) break
                numSamples += 128
                for (k in offset until offset + 128) {
                    samplesRing[samplesPos] = pcm[k].toInt()
                    samplesPos = (samplesPos + 1) % FFT_SIZE
                }
                doFft()
                doPeakSpreading()
                if (spreadNumWritten >= 47) doPeakRecognition()
                offset += 128
            }
            return encodeSignature()
        }

        private fun doFft() {
            val windowed = DoubleArray(FFT_SIZE) { i ->
                samplesRing[(samplesPos + i) % FFT_SIZE].toDouble() * HANNING[i]
            }
            computeRfft(windowed).copyInto(fftOutputs[fftPos])
            fftPos = (fftPos + 1) % RING_BUF_SIZE
        }

        private fun doPeakSpreading() {
            val lastFftIdx = (fftPos - 1 + RING_BUF_SIZE) % RING_BUF_SIZE
            val spread = fftOutputs[lastFftIdx].copyOf()
            for (pos in 0 until FFT_OUTPUT_SIZE - 2) {
                spread[pos] = maxOf(spread[pos], spread[pos + 1], spread[pos + 2])
            }
            for (pos in 0 until FFT_OUTPUT_SIZE) {
                var maxVal = spread[pos]
                for (offset in intArrayOf(-1, -3, -6)) {
                    val idx = ((spreadPos + offset) % RING_BUF_SIZE + RING_BUF_SIZE) % RING_BUF_SIZE
                    val oldVal = spreadFfts[idx][pos]
                    if (oldVal > maxVal) maxVal = oldVal
                    spreadFfts[idx][pos] = maxVal
                }
            }
            spread.copyInto(spreadFfts[spreadPos])
            spreadPos = (spreadPos + 1) % RING_BUF_SIZE
            spreadNumWritten++
        }

        private fun doPeakRecognition() {
            val fftMinus46 = fftOutputs[(fftPos - 46 + RING_BUF_SIZE * 2) % RING_BUF_SIZE]
            val spreadMinus49 = spreadFfts[(spreadPos - 49 + RING_BUF_SIZE * 2) % RING_BUF_SIZE]
            val otherOffsets = intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)

            for (binPos in 10 until FFT_OUTPUT_SIZE - 8) {
                val fftVal = fftMinus46[binPos]
                if (fftVal < 1.0 / 64.0 || fftVal < spreadMinus49[binPos]) continue

                var maxNeighbor = 0.0
                for (nOff in intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)) {
                    val v = spreadMinus49[binPos + nOff]
                    if (v > maxNeighbor) maxNeighbor = v
                }
                if (fftVal <= maxNeighbor) continue

                var maxOther = maxNeighbor
                for (oOff in otherOffsets) {
                    val sIdx = ((spreadPos + oOff) % RING_BUF_SIZE + RING_BUF_SIZE) % RING_BUF_SIZE
                    val v = spreadFfts[sIdx][binPos - 1]
                    if (v > maxOther) maxOther = v
                }
                if (fftVal <= maxOther) continue

                val fftNumber = spreadNumWritten - 46
                val peakMag = ln(max(1.0 / 64.0, fftVal)) * 1477.3 + 6144
                val peakBefore = ln(max(1.0 / 64.0, fftMinus46[binPos - 1])) * 1477.3 + 6144
                val peakAfter = ln(max(1.0 / 64.0, fftMinus46[binPos + 1])) * 1477.3 + 6144
                val v1 = peakMag * 2 - peakBefore - peakAfter
                val v2 = (peakAfter - peakBefore) * 32 / v1
                val correctedBin = binPos * 64.0 + v2
                val freqHz = correctedBin * (16000.0 / 2.0 / 1024.0 / 64.0)

                val band = when {
                    freqHz < 250.0 -> continue
                    freqHz < 520.0 -> 0
                    freqHz < 1450.0 -> 1
                    freqHz < 3500.0 -> 2
                    freqHz <= 5500.0 -> 3
                    else -> continue
                }
                bandPeaks[band] += Peak(fftNumber, peakMag.toInt(), correctedBin.toInt())
                totalPeaks++
            }
        }

        private fun encodeSignature(): String {
            val contentsStream = ByteArrayOutputStream()
            for (bandId in 0..3) {
                val peaks = bandPeaks[bandId]
                if (peaks.isEmpty()) continue
                val peakBuf = ByteArrayOutputStream()
                var prev = 0
                for (peak in peaks) {
                    val diff = peak.fftPassNumber - prev
                    if (diff >= 255) {
                        peakBuf.write(0xFF)
                        writeLe32(peakBuf, peak.fftPassNumber)
                        prev = peak.fftPassNumber
                    }
                    peakBuf.write(peak.fftPassNumber - prev)
                    writeLe16(peakBuf, peak.peakMagnitude)
                    writeLe16(peakBuf, peak.correctedBin)
                    prev = peak.fftPassNumber
                }
                val peakBytes = peakBuf.toByteArray()
                writeLe32(contentsStream, 0x60030040 + bandId)
                writeLe32(contentsStream, peakBytes.size)
                contentsStream.write(peakBytes)
                repeat((4 - peakBytes.size % 4) % 4) { contentsStream.write(0) }
            }

            val contents = contentsStream.toByteArray()
            val sizeMinusHeader = contents.size + 8
            val samplesAndOffset = (numSamples + REQUIRED_SAMPLE_RATE * 0.24).toInt()
            val header = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0xcafe2580.toInt())
                putInt(0)
                putInt(sizeMinusHeader)
                putInt(0x94119c00.toInt())
                putInt(0); putInt(0); putInt(0)
                putInt(3 shl 27)
                putInt(0); putInt(0)
                putInt(samplesAndOffset)
                putInt((15 shl 19) + 0x40000)
            }.array()

            val fullBuf = ByteArrayOutputStream(56 + contents.size)
            fullBuf.write(header)
            writeLe32(fullBuf, 0x40000000)
            writeLe32(fullBuf, contents.size + 8)
            fullBuf.write(contents)
            val fullBytes = fullBuf.toByteArray()

            val crc = CRC32().apply { update(fullBytes, 8, fullBytes.size - 8) }.value.toInt()
            fullBytes[4] = (crc and 0xFF).toByte()
            fullBytes[5] = ((crc shr 8) and 0xFF).toByte()
            fullBytes[6] = ((crc shr 16) and 0xFF).toByte()
            fullBytes[7] = ((crc shr 24) and 0xFF).toByte()

            val b64 = Base64.getEncoder().encodeToString(fullBytes)
            return "data:audio/vnd.shazam.sig;base64,$b64"
        }
    }

    private fun writeLe32(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    private fun writeLe16(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private fun computeRfft(windowed: DoubleArray): DoubleArray {
        val n = windowed.size
        val re = windowed.copyOf()
        val im = DoubleArray(n)
        var j = 0
        for (i in 1 until n) {
            var bit = n ushr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit ushr 1
            }
            j = j xor bit
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }
        var len = 2
        while (len <= n) {
            val halfLen = len ushr 1
            val ang = -PI / halfLen
            val wBaseRe = cos(ang)
            val wBaseIm = sin(ang)
            var i = 0
            while (i < n) {
                var wRe = 1.0
                var wIm = 0.0
                for (k in 0 until halfLen) {
                    val u = i + k
                    val v = u + halfLen
                    val evenRe = re[u]
                    val evenIm = im[u]
                    val oddRe = re[v] * wRe - im[v] * wIm
                    val oddIm = re[v] * wIm + im[v] * wRe
                    re[u] = evenRe + oddRe
                    im[u] = evenIm + oddIm
                    re[v] = evenRe - oddRe
                    im[v] = evenIm - oddIm
                    val newWRe = wRe * wBaseRe - wIm * wBaseIm
                    wIm = wRe * wBaseIm + wIm * wBaseRe
                    wRe = newWRe
                }
                i += len
            }
            len = len shl 1
        }
        val scale = 1.0 / (1 shl 17)
        return DoubleArray(FFT_OUTPUT_SIZE) { idx ->
            val mag = (re[idx] * re[idx] + im[idx] * im[idx]) * scale
            if (mag < 1e-10) 1e-10 else mag
        }
    }
}

class DesktopMusicRecognizer(
    private val database: DesktopDatabase,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _state = MutableStateFlow<RecognitionState>(RecognitionState.Idle)
    val state: StateFlow<RecognitionState> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private var activeJob: Job? = null

    fun startListening(durationSeconds: Int = 10) {
        cancel()
        activeJob = scope.launch {
            try {
                val (pcmBytes, sampleRate) = captureMicrophone(durationSeconds)
                if (!isActive) return@launch
                _state.value = RecognitionState.Processing
                val pcm16k = DesktopShazamSignatureGenerator.resampleTo16kMono(pcmBytes, sampleRate)
                val sig = DesktopShazamSignatureGenerator.fromI16(pcm16k)
                val sampleMs = (pcm16k.size / 2L) * 1000L / DesktopShazamSignatureGenerator.REQUIRED_SAMPLE_RATE
                val recognized = queryShazam(sig, sampleMs)
                if (recognized == null) {
                    _state.value = RecognitionState.NoMatch()
                    return@launch
                }
                val matchedSong = findPlayableMatch(recognized)
                val enriched = recognized.copy(youtubeVideoId = matchedSong?.id ?: recognized.youtubeVideoId)
                database.addRecognitionHistory(enriched)
                _state.value = RecognitionState.Success(enriched, matchedSong)
            } catch (ce: CancellationException) {
                _state.value = RecognitionState.Idle
                throw ce
            } catch (t: Throwable) {
                _state.value = RecognitionState.Error(t.message ?: "Error al reconocer música")
            }
        }
    }

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        _state.value = RecognitionState.Idle
    }

    fun reset() {
        _state.value = RecognitionState.Idle
    }

    private fun captureMicrophone(durationSeconds: Int): Pair<ByteArray, Int> {
        val candidateRates = listOf(16_000, 44_100, 48_000)
        var line: TargetDataLine? = null
        var chosenRate = 16_000

        for (rate in candidateRates) {
            val fmt = AudioFormat(rate.toFloat(), 16, 1, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, fmt)
            if (AudioSystem.isLineSupported(info)) {
                val opened = runCatching {
                    (AudioSystem.getLine(info) as TargetDataLine).apply { open(fmt) }
                }.getOrNull()
                if (opened != null) {
                    line = opened
                    chosenRate = rate
                    break
                }
            }
        }

        val mic = line ?: error("No se detectó un micrófono disponible en el sistema")
        val output = ByteArrayOutputStream()
        val buf = ByteArray(chosenRate / 5 * 2) // ~200ms chunks
        val totalTargetBytes = chosenRate * 2 * durationSeconds

        mic.start()
        try {
            val startedMs = System.currentTimeMillis()
            while (output.size() < totalTargetBytes && activeJob?.isActive != false) {
                val read = mic.read(buf, 0, buf.size)
                if (read > 0) {
                    output.write(buf, 0, read)
                    val amp = computeAmplitude(buf, read)
                    val elapsed = (System.currentTimeMillis() - startedMs) / 1000f
                    _state.value = RecognitionState.Listening(elapsedSec = elapsed, amplitude = amp)
                }
            }
        } finally {
            runCatching { mic.stop() }
            runCatching { mic.close() }
        }
        return output.toByteArray() to chosenRate
    }

    private fun computeAmplitude(buf: ByteArray, length: Int): Float {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val lo = buf[i].toInt() and 0xFF
            val hi = buf[i + 1].toInt() shl 8
            val sample = abs((hi or lo).toShort().toInt())
            if (sample > peak) peak = sample
            i += 2
        }
        return (peak / 32768f).coerceIn(0f, 1f)
    }

    @Serializable
    private data class ShazamReq(
        val geolocation: Geo,
        val signature: Sig,
        val timestamp: Long,
        val timezone: String,
    ) {
        @Serializable
        data class Geo(val altitude: Double, val latitude: Double, val longitude: Double)
        @Serializable
        data class Sig(val samplems: Long, val timestamp: Long, val uri: String)
    }

    @Serializable
    private data class ShazamResp(
        val tagid: String? = null,
        val track: Track? = null,
    ) {
        @Serializable
        data class Track(
            val key: String? = null,
            val title: String? = null,
            val subtitle: String? = null,
            val url: String? = null,
            val isrc: String? = null,
            val images: Images? = null,
            val genres: Genres? = null,
            val sections: List<Section>? = null,
        )
        @Serializable
        data class Images(val coverart: String? = null, val coverarthq: String? = null)
        @Serializable
        data class Genres(val primary: String? = null)
        @Serializable
        data class Section(val type: String? = null, val metadata: List<Meta>? = null)
        @Serializable
        data class Meta(val title: String? = null, val text: String? = null)
    }

    private fun queryShazam(signature: String, sampleDurationMs: Long): DesktopRecognitionHistoryItem? {
        val ts = System.currentTimeMillis() / 1000L
        val uuid1 = UUID.randomUUID().toString().uppercase()
        val uuid2 = UUID.randomUUID().toString()
        val body = json.encodeToString(
            ShazamReq(
                geolocation = ShazamReq.Geo(
                    altitude = Random.nextDouble() * 400 + 100,
                    latitude = Random.nextDouble() * 180 - 90,
                    longitude = Random.nextDouble() * 360 - 180,
                ),
                signature = ShazamReq.Sig(sampleDurationMs, ts, signature),
                timestamp = ts,
                timezone = "America/New_York",
            ),
        )
        val url = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/$uuid1/$uuid2" +
            "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 6.0.1; SM-G920F Build/MMB29K)")
            .header("Content-Language", "en_US")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Shazam devolvió HTTP ${resp.code}")
            val parsed = json.decodeFromString<ShazamResp>(resp.body.string())
            val track = parsed.track ?: return null
            val meta = track.sections?.firstOrNull { it.type == "SONG" }?.metadata.orEmpty()
            DesktopRecognitionHistoryItem(
                trackId = track.key ?: parsed.tagid.orEmpty(),
                title = track.title.orEmpty(),
                artist = track.subtitle.orEmpty(),
                album = meta.firstOrNull { it.title == "Album" }?.text,
                coverArtUrl = track.images?.coverart,
                coverArtHqUrl = track.images?.coverarthq,
                genre = track.genres?.primary,
                releaseDate = meta.firstOrNull { it.title == "Released" }?.text,
                label = meta.firstOrNull { it.title == "Label" }?.text,
                shazamUrl = track.url,
                isrc = track.isrc,
                recognizedAt = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun findPlayableMatch(item: DesktopRecognitionHistoryItem): DesktopSong? {
        val query = "${item.title} ${item.artist}".trim()
        if (query.isBlank()) return null
        return runCatching {
            YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                ?.items?.filterIsInstance<SongItem>()?.firstOrNull()?.toDesktopSong()
        }.getOrNull()
    }

    fun close() {
        cancel()
        runCatching { httpClient.dispatcher.cancelAll() }
        runCatching { httpClient.dispatcher.executorService.shutdownNow() }
        runCatching { httpClient.connectionPool.evictAll() }
    }
}
