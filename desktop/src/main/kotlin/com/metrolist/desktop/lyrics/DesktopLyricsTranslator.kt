package com.metrolist.desktop.lyrics

import com.metrolist.desktop.data.DesktopSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class AiProviderPreset(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val models: List<String>,
)

object DesktopAiProviders {
    val all: List<AiProviderPreset> = listOf(
        AiProviderPreset("OpenRouter", "OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "google/gemini-2.5-flash", listOf("google/gemini-2.5-flash", "openai/gpt-4o-mini", "anthropic/claude-3.5-haiku", "meta-llama/llama-3.3-70b-instruct")),
        AiProviderPreset("OpenAI", "OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini", listOf("gpt-4o-mini", "gpt-4.1-mini", "gpt-4o")),
        AiProviderPreset("Gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "gemini-2.5-flash", listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-2.5-pro")),
        AiProviderPreset("Claude", "Anthropic Claude", "https://api.anthropic.com/v1/messages", "claude-3-5-haiku-latest", listOf("claude-3-5-haiku-latest", "claude-sonnet-4-20250514")),
        AiProviderPreset("Perplexity", "Perplexity", "https://api.perplexity.ai/chat/completions", "sonar", listOf("sonar", "sonar-pro")),
        AiProviderPreset("XAi", "xAI (Grok)", "https://api.x.ai/v1/chat/completions", "grok-3-mini", listOf("grok-3-mini", "grok-3")),
        AiProviderPreset("Mistral", "Mistral AI", "https://api.mistral.ai/v1/chat/completions", "mistral-small-latest", listOf("mistral-small-latest", "mistral-medium-latest")),
        AiProviderPreset("Inception", "Inception", "https://api.inceptionlabs.ai/v1/chat/completions", "mercury", listOf("mercury")),
        AiProviderPreset("DeepL", "DeepL", "https://api-free.deepl.com/v2/translate", "", emptyList()),
        AiProviderPreset("Custom", "Personalizado (OpenAI compatible)", "", "", emptyList()),
    )

    fun presetFor(id: String): AiProviderPreset = all.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: all.first()
}

object DesktopLyricsTranslator {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun translateLines(lines: List<String>, settings: DesktopSettings): Result<List<String>> {
        if (lines.isEmpty()) return Result.success(emptyList())
        val text = lines.joinToString("\n")
        return if (settings.aiProvider.equals("DeepL", ignoreCase = true)) {
            translateWithDeepL(lines, settings.translateLanguage, settings.deeplApiKey, settings.deeplFormality)
        } else {
            val preset = DesktopAiProviders.presetFor(settings.aiProvider)
            val baseUrl = if (settings.aiProvider.equals("Custom", ignoreCase = true)) {
                settings.openRouterBaseUrl.trim()
            } else {
                preset.baseUrl
            }
            val model = settings.openRouterModel.ifBlank { preset.defaultModel }
            if (settings.aiProvider.equals("Claude", ignoreCase = true)) {
                translateWithClaude(text, lines.size, settings.translateLanguage, settings.openRouterApiKey, model, settings.translateMode, settings.aiSystemPrompt)
            } else {
                translateOpenAiCompatible(text, lines.size, settings.translateLanguage, settings.openRouterApiKey, baseUrl, model, settings.translateMode, settings.aiSystemPrompt)
            }
        }
    }

    private suspend fun translateOpenAiCompatible(
        text: String,
        lineCount: Int,
        targetLanguage: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        mode: String,
        customSystemPrompt: String,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext Result.failure(IllegalStateException("Configura la URL base del proveedor IA"))
        if (apiKey.isBlank()) return@withContext Result.failure(IllegalStateException("Configura tu API Key de traducción IA en Ajustes"))

        val (sysPrompt, userPrompt) = buildPrompts(text, lineCount, targetLanguage, mode, customSystemPrompt)
        val payload = buildJsonObject {
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", sysPrompt) })
                add(buildJsonObject { put("role", "user"); put("content", userPrompt) })
            })
            if (model.isNotBlank()) put("model", model)
            put("temperature", 0.3)
        }

        repeat(3) { attempt ->
            try {
                val req = Request.Builder()
                    .url(baseUrl)
                    .header("Authorization", "Bearer ${apiKey.trim()}")
                    .header("Content-Type", "application/json")
                    .header("HTTP-Referer", "https://github.com/angelvalentin-dev/miliMusic")
                    .header("X-Title", "miliMusic Desktop")
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body.string()
                    if (!resp.isSuccessful) {
                        val err = extractError(body, resp.code, resp.message)
                        if (resp.code >= 500 && attempt < 2) {
                            delay(1000L * (attempt + 1))
                            return@use
                        }
                        return@withContext Result.failure(IllegalStateException("Error de traducción: $err"))
                    }
                    val content = json.parseToJsonElement(body).jsonObject["choices"]
                        ?.jsonArray?.getOrNull(0)?.jsonObject?.get("message")?.jsonObject?.get("content")
                        ?.jsonPrimitive?.contentOrNull.orEmpty()
                    return@withContext parseTranslationContent(content, lineCount)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (attempt == 2) return@withContext Result.failure(t)
                delay(1000L * (attempt + 1))
            }
        }
        Result.failure(IllegalStateException("No se pudo traducir las letras"))
    }

    private suspend fun translateWithClaude(
        text: String,
        lineCount: Int,
        targetLanguage: String,
        apiKey: String,
        model: String,
        mode: String,
        customSystemPrompt: String,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.failure(IllegalStateException("Configura tu API Key de Claude en Ajustes"))
        val (sysPrompt, userPrompt) = buildPrompts(text, lineCount, targetLanguage, mode, customSystemPrompt)
        val payload = buildJsonObject {
            put("model", model.ifBlank { "claude-3-5-haiku-latest" })
            put("max_tokens", (lineCount * 100).coerceIn(512, 8192))
            put("system", sysPrompt)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", userPrompt) })
            })
        }
        runCatching {
            val req = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", apiKey.trim())
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) error(extractError(body, resp.code, resp.message))
                val content = json.parseToJsonElement(body).jsonObject["content"]
                    ?.jsonArray?.getOrNull(0)?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                parseTranslationContent(content, lineCount).getOrThrow()
            }
        }
    }

    private suspend fun translateWithDeepL(
        lines: List<String>,
        targetLanguage: String,
        apiKey: String,
        formality: String,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.failure(IllegalStateException("Configura tu API Key de DeepL en Ajustes"))
        val langCode = when (targetLanguage.lowercase()) {
            "zh", "zh-cn", "zh-hans", "zh-tw", "zh-hant" -> "ZH"
            "en", "en-us" -> "EN-US"
            "en-gb" -> "EN-GB"
            "pt", "pt-pt" -> "PT-PT"
            "pt-br" -> "PT-BR"
            else -> targetLanguage.uppercase().take(2)
        }
        val url = if (apiKey.trim().endsWith(":fx")) "https://api-free.deepl.com/v2/translate" else "https://api.deepl.com/v2/translate"
        val body = buildJsonObject {
            put("text", buildJsonArray { lines.forEach { add(it) } })
            put("target_lang", langCode)
            if (formality != "default") put("formality", formality)
            put("preserve_formatting", true)
        }
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "DeepL-Auth-Key ${apiKey.trim()}")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body.string()
                if (!resp.isSuccessful) error(extractError(raw, resp.code, resp.message))
                val translations = json.parseToJsonElement(raw).jsonObject["translations"]?.jsonArray
                    ?: error("Respuesta vacía de DeepL")
                val out = translations.map { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
                alignLineCount(out, lines.size)
            }
        }
    }

    private fun buildPrompts(
        text: String,
        lineCount: Int,
        targetLanguage: String,
        mode: String,
        customSystemPrompt: String,
    ): Pair<String, String> {
        val sys = customSystemPrompt.takeIf { it.isNotBlank() }?.replace("{lineCount}", lineCount.toString())
            ?: """You are a precise lyrics translation assistant. Output ONLY a valid JSON object: {"lines": ["line1", "line2", ...]}.
Rules:
1. Output ONLY JSON, no markdown fences or commentary.
2. Each input line maps 1-to-1 to an entry in "lines".
3. Preserve empty lines as "".
4. The "lines" array MUST have EXACTLY $lineCount items."""

        val user = when (mode) {
            "Romanized" -> "Romanize these $lineCount lyric lines into plain ASCII Latin letters without translating meaning. Return JSON {\"lines\": [...]} with $lineCount items:\n$text"
            "Transcribed" -> "Transcribe the phonetic sound of these $lineCount lyric lines into $targetLanguage script without translating meaning. Return JSON {\"lines\": [...]} with $lineCount items:\n$text"
            else -> "Translate these $lineCount lyric lines naturally into $targetLanguage preserving poetic flow and exact line count. Return JSON {\"lines\": [...]} with $lineCount items:\n$text"
        }
        return sys to user
    }

    fun parseTranslationContent(content: String, expectedLineCount: Int): Result<List<String>> = runCatching {
        val cleaned = content.replace("```json", "").replace("```", "").trim()
        val bracketed = cleaned.substringAfter('[', "").substringBeforeLast(']', "")
            .takeIf { it.isNotEmpty() }?.let { "[$it]" }
        val extracted = sequenceOf(content.trim(), cleaned, bracketed)
            .filterNotNull()
            .mapNotNull { cand ->
                runCatching { extractLines(json.parseToJsonElement(cand)) }.getOrNull()
            }
            .firstOrNull()
            ?: cleaned.lines().filter { it.isNotBlank() }.map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
                .takeIf { it.isNotEmpty() }
            ?: error("No se pudo interpretar la respuesta de traducción")
        alignLineCount(extracted, expectedLineCount)
    }

    private fun alignLineCount(lines: List<String>, expected: Int): List<String> =
        lines.take(expected) + List((expected - lines.size).coerceAtLeast(0)) { "" }

    private fun extractLines(el: JsonElement): List<String>? = when (el) {
        is JsonObject -> (el["lines"] as? JsonArray)?.map { it.jsonPrimitive.content }
        is JsonArray -> el.map { it.jsonPrimitive.content }
        else -> null
    }

    private fun extractError(body: String, code: Int, message: String): String =
        runCatching {
            val obj = json.parseToJsonElement(body).jsonObject
            obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                ?: obj["message"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP $code: $message"

    fun close() {
        runCatching { client.dispatcher.cancelAll() }
        runCatching { client.dispatcher.executorService.shutdownNow() }
        runCatching { client.connectionPool.evictAll() }
    }
}
