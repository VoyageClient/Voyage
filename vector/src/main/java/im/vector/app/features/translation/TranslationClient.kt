/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.translation

import im.vector.app.core.resources.StringProvider
import im.vector.app.core.vpn.VpnGateInterceptor
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed class TranslationResult {
    data class Success(val text: String, val detectedSource: String?) : TranslationResult()
    data class Failure(val message: String) : TranslationResult()
}

/**
 * Talks to the configured translation engine (with the backup engine as a fallback), mirroring the
 * BetterDiscord Translator plugin's engine table. Message text leaves the device here, so the
 * client sits behind the VPN gate like every other network path.
 */
@Singleton
class TranslationClient @Inject constructor(
        private val settings: TranslationSettings,
        private val stringProvider: StringProvider,
        private val nllbTranslator: im.vector.app.features.translation.ondevice.NllbTranslator,
        vpnGateInterceptor: VpnGateInterceptor,
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(vpnGateInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()

    private class EngineError(val status: Int?, message: String) : IOException(message)

    /**
     * [source] / [target] are Google language ids ([TranslationLanguages.AUTO] / [TranslationLanguages.APP]
     * allowed). Whatever the engine answers is returned as-is — no echo or same-language filtering.
     */
    suspend fun translate(text: String, source: String, target: String): TranslationResult = withContext(Dispatchers.IO) {
        val resolvedTarget = TranslationLanguages.resolve(target)
        val resolvedSource = if (source == TranslationLanguages.AUTO) TranslationLanguages.AUTO else TranslationLanguages.resolve(source)
        val configured = listOfNotNull(settings.engine, settings.backupEngine)
        if (configured.isEmpty()) {
            return@withContext TranslationResult.Failure(stringProvider.getString(CommonStrings.translation_error_no_engine))
        }
        val candidates = configured
                .filter { it.supports(resolvedTarget) && (resolvedSource == TranslationLanguages.AUTO || it.supports(resolvedSource)) }
        if (candidates.isEmpty()) {
            return@withContext TranslationResult.Failure(stringProvider.getString(CommonStrings.translation_error_unsupported_language))
        }
        var lastError: String? = null
        for (engine in candidates) {
            try {
                var (translated, detected) = call(engine, text, resolvedSource, resolvedTarget)
                // LOCAL does its own detection and never plays the detected-equals-target echo game,
                // and each retry there would be a full model run.
                if (engine != TranslationEngine.LOCAL && resolvedSource == TranslationLanguages.AUTO && translated.trim() == text.trim()) {
                    // Echo: the engine judged the whole text to already be in the target language. For a
                    // mixed-language message that verdict comes from the dominant language and the foreign
                    // words stay untranslated. Re-ask in word-halves so each chunk is detected on its own;
                    // chunks genuinely in the target language echo again and are kept as-is.
                    val chunked = ChunkState()
                    val retried = translateHalves(engine, text, resolvedTarget, chunked)
                    if (retried.trim() != text.trim()) {
                        translated = retried
                        detected = chunked.detected
                    }
                }
                val normalizedDetected = detected?.let { TranslationLanguages.normalize(it) }?.takeIf { TranslationLanguages.isKnown(it) }
                return@withContext TranslationResult.Success(translated, normalizedDetected)
            } catch (e: Exception) {
                Timber.w(e, "Translation via ${engine.displayName} failed")
                lastError = describe(engine, e)
            }
        }
        TranslationResult.Failure(lastError.orEmpty())
    }

    private class ChunkState(var budget: Int = 6, var detected: String? = null)

    // Depth is capped so a genuinely-native half doesn't eat the whole request budget before the
    // foreign half is ever tried.
    private suspend fun translateHalves(engine: TranslationEngine, text: String, target: String, state: ChunkState, depth: Int = 0): String {
        val words = text.split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.size < 2 || state.budget <= 0 || depth > 1) return text
        val mid = words.size / 2
        val halves = listOf(words.subList(0, mid).joinToString(" "), words.subList(mid, words.size).joinToString(" "))
        val out = ArrayList<String>(2)
        for (half in halves) {
            if (state.budget <= 0) {
                out.add(half)
                continue
            }
            state.budget--
            val (translated, detected) = call(engine, half, TranslationLanguages.AUTO, target)
            if (translated.isNotEmpty() && translated.trim() != half.trim()) {
                if (state.detected == null) state.detected = detected
                out.add(translated.trim())
            } else {
                out.add(translateHalves(engine, half, target, state, depth + 1))
            }
        }
        return out.joinToString(" ")
    }

    private fun describe(engine: TranslationEngine, e: Exception): String {
        val reason = when ((e as? EngineError)?.status) {
            401, 403 -> stringProvider.getString(CommonStrings.translation_error_key)
            429, 456 -> stringProvider.getString(CommonStrings.translation_error_limit)
            else -> e.message?.takeIf { it.isNotBlank() } ?: stringProvider.getString(CommonStrings.translation_error_server)
        }
        return stringProvider.getString(CommonStrings.translation_failed, engine.displayName, reason)
    }

    private suspend fun call(engine: TranslationEngine, text: String, source: String, target: String): Pair<String, String?> {
        // Microsoft and DeepL fall back to the communal keys the BetterDiscord plugin ships, so they
        // work out of the box (until those keys are throttled); the LLM engines have no such keys.
        val needsOwnKey = engine == TranslationEngine.DEEPSEEK || engine == TranslationEngine.OPENAI_COMPATIBLE
        if (needsOwnKey && settings.apiKey(engine).isEmpty()) {
            throw EngineError(null, stringProvider.getString(CommonStrings.translation_error_no_key))
        }
        return when (engine) {
            TranslationEngine.LOCAL -> {
                if (!nllbTranslator.isAvailable) {
                    throw EngineError(null, stringProvider.getString(CommonStrings.translation_error_local_missing))
                }
                nllbTranslator.translate(text, target)
            }
            TranslationEngine.GOOGLE -> google(text, source, target)
            TranslationEngine.MICROSOFT -> microsoft(text, source, target)
            TranslationEngine.DEEPL -> deepl(text, source, target)
            TranslationEngine.DEEPSEEK -> chatCompletion(
                    TranslationEngine.DEEPSEEK_ENDPOINT, TranslationEngine.DEEPSEEK_MODEL, settings.apiKey(engine), text, source, target
            )
            TranslationEngine.OPENAI_COMPATIBLE -> chatCompletion(settings.oaiEndpoint, settings.oaiModel, settings.apiKey(engine), text, source, target)
        }
    }

    private fun google(text: String, source: String, target: String): Pair<String, String?> {
        val url = HttpUrl.parse("https://translate.googleapis.com/translate_a/single")!!.newBuilder()
                .addQueryParameter("client", "gtx")
                .addQueryParameter("dt", "t")
                .addQueryParameter("dj", "1")
                .addQueryParameter("source", "input")
                .addQueryParameter("sl", TranslationEngine.GOOGLE.wireCode(source))
                .addQueryParameter("tl", TranslationEngine.GOOGLE.wireCode(target))
                .build()
        val request = Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_UA)
                .post(FormBody.Builder().add("q", text).build())
                .build()
        val body = JSONObject(execute(request))
        val sentences = body.optJSONArray("sentences") ?: JSONArray()
        val translated = buildString {
            for (i in 0 until sentences.length()) {
                append(sentences.optJSONObject(i)?.optString("trans").orEmpty())
            }
        }
        return translated to body.optString("src").takeIf { it.isNotEmpty() }
    }

    private fun microsoft(text: String, source: String, target: String): Pair<String, String?> {
        val urlBuilder = HttpUrl.parse("https://api.cognitive.microsofttranslator.com/translate")!!.newBuilder()
                .addQueryParameter("api-version", "3.0")
                .addQueryParameter("to", TranslationEngine.MICROSOFT.wireCode(target))
        if (source != TranslationLanguages.AUTO) urlBuilder.addQueryParameter("from", TranslationEngine.MICROSOFT.wireCode(source))
        val payload = JSONArray().put(JSONObject().put("Text", text)).toString()
        val request = Request.Builder()
                .url(urlBuilder.build())
                .header("Ocp-Apim-Subscription-Key", settings.apiKey(TranslationEngine.MICROSOFT).ifEmpty { COMMUNAL_MICROSOFT_KEY })
                .header("Ocp-Apim-Subscription-Region", settings.microsoftRegion)
                .post(RequestBody.create(JSON, payload))
                .build()
        val first = JSONArray(execute(request)).optJSONObject(0) ?: throw IOException("Empty response")
        val translations = first.optJSONArray("translations") ?: JSONArray()
        val translated = buildString {
            for (i in 0 until translations.length()) append(translations.optJSONObject(i)?.optString("text").orEmpty())
        }
        return translated to first.optJSONObject("detectedLanguage")?.optString("language")?.takeIf { it.isNotEmpty() }
    }

    private fun deepl(text: String, source: String, target: String): Pair<String, String?> {
        val key = settings.apiKey(TranslationEngine.DEEPL).ifEmpty { COMMUNAL_DEEPL_KEY }
        val endpoint = if (key.endsWith(":fx")) "https://api-free.deepl.com/v2/translate" else "https://api.deepl.com/v2/translate"
        val payload = JSONObject()
                .put("text", JSONArray().put(text))
                .put("target_lang", TranslationEngine.DEEPL.wireCode(target))
        if (source != TranslationLanguages.AUTO) payload.put("source_lang", TranslationEngine.DEEPL.wireCode(source).substringBefore('-'))
        val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "DeepL-Auth-Key $key")
                .post(RequestBody.create(JSON, payload.toString()))
                .build()
        val translations = JSONObject(execute(request)).optJSONArray("translations") ?: JSONArray()
        val translated = buildString {
            for (i in 0 until translations.length()) append(translations.optJSONObject(i)?.optString("text").orEmpty())
        }
        return translated to translations.optJSONObject(0)?.optString("detected_source_language")?.takeIf { it.isNotEmpty() }
    }

    private fun chatCompletion(endpoint: String, model: String, key: String, text: String, source: String, target: String): Pair<String, String?> {
        val sourceName = TranslationLanguages.nameOf(source)?.let { "$it " }.orEmpty()
        val targetName = TranslationLanguages.nameOf(target) ?: target
        val flattened = text.replace("\n", " [NEWLINE] ").replace(Regex("""\s+"""), " ")
        val prompt = """
            You are a professional localization expert. Translate the following ${sourceName}content to $targetName following these rules:
            1. Return ONLY the translation without any explanations
            2. Use natural, fluent language
            3. Maintain consistent terminology for technical terms
            4. Preserve the original tone and style
            5. Use concise sentence structures
            6. Handle numbers/units/proper nouns correctly
            7. Keep any {{n}} placeholders exactly as they are
            8. Convert [NEWLINE] markers to actual line breaks (don't show them literally)

            Text to translate:
            $flattened
        """.trimIndent()
        val payload = JSONObject()
                .put("model", model)
                .put(
                        "messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", "You are a senior bilingual localization specialist"))
                        .put(JSONObject().put("role", "user").put("content", prompt))
                )
                .put("temperature", 0.2)
                .put("top_p", 0.8)
        val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $key")
                .post(RequestBody.create(JSON, payload.toString()))
                .build()
        val content = JSONObject(execute(request))
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")
                ?: throw IOException("Empty response")
        return content.replace("[NEWLINE]", "\n").trim() to null
    }

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { response ->
            val body = response.body()?.string().orEmpty()
            if (!response.isSuccessful) {
                Timber.w("Translation request ${request.url().host()} -> HTTP ${response.code()}: ${body.take(200)}")
                throw EngineError(response.code(), "HTTP ${response.code()}")
            }
            if (body.isEmpty()) throw IOException("Empty response")
            return body
        }
    }

    companion object {
        private val WHITESPACE = Regex("""\s+""")
        private val JSON = MediaType.parse("application/json; charset=utf-8")
        private const val COMMUNAL_MICROSOFT_KEY = "1ea861033a56423f860fd6f5ff33e308"
        private const val COMMUNAL_DEEPL_KEY = "75cc2f40-fdae-14cd-7242-6a384e2abb9c:fx"
        private const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
