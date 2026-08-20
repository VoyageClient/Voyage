/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.useragent.data

import com.squareup.moshi.JsonClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

/** ETag / Last-Modified validators kept per URL so a re-fetch can ask "only if changed". */
@JsonClass(generateAdapter = true)
data class UaValidators(val etag: String?, val lastModified: String?)

/** Persists per-URL validators so conditional requests survive a process restart. */
interface UaValidatorStore {
    fun load(url: String): UaValidators?
    fun save(url: String, validators: UaValidators)
    fun invalidate(url: String)
}

/** Thrown when a conditional request comes back 304: the caller should keep its existing cache. */
class UaNotModified(url: String) : RuntimeException("Not modified: $url")

/**
 * Bare HTTP client for fetching spoofing option data (GitHub, StatCounter, …). Deliberately separate
 * from the SDK OkHttp stack so these requests don't carry the spoofed UA or hit the VPN gate. Sends a
 * neutral UA of its own, which GitHub requires or it 403s. When a [validators] store is present it sends
 * If-None-Match / If-Modified-Since and throws [UaNotModified] on 304, so unchanged sources (the ~5 MB
 * device CSV, the ~1 MB electron list, GitHub releases) aren't re-downloaded.
 */
class UaHttp(
        // StatCounter can be slow to generate the larger CSVs, so allow a generous read timeout.
        private val client: OkHttpClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build(),
        private val validators: UaValidatorStore? = null,
) {

    // Shorter-timeout twin for flaky sources (source.android.com stalls for tens of seconds): fail fast
    // to the cache instead of hanging the whole download on one bad server.
    private val impatientClient: OkHttpClient = client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()

    suspend fun get(url: String, impatient: Boolean = false, conditional: Boolean = true): String = withContext(Dispatchers.IO) {
        execute(url, impatient, conditional).use { response ->
            val body = response.body()?.string() ?: throw IOException("Empty body for $url")
            // Save validators only after the body is fully read, so a mid-stream drop never leaves a
            // stored ETag pointing at data we didn't actually cache.
            if (conditional) validators?.save(url, UaValidators(response.header("ETag"), response.header("Last-Modified")))
            body
        }
    }

    /** Raw bytes, for sources that aren't UTF-8 (e.g. Google's UTF-16 device CSV). */
    suspend fun getBytes(url: String, conditional: Boolean = true): ByteArray = withContext(Dispatchers.IO) {
        execute(url, impatient = false, conditional).use { response ->
            val bytes = response.body()?.bytes() ?: throw IOException("Empty body for $url")
            if (conditional) validators?.save(url, UaValidators(response.header("ETag"), response.header("Last-Modified")))
            bytes
        }
    }

    // One retry: these endpoints (and mobile networks) drop connections often enough to matter.
    private fun execute(url: String, impatient: Boolean, conditional: Boolean): okhttp3.Response {
        val httpClient = if (impatient) impatientClient else client
        val builder = Request.Builder()
                .url(url)
                .header("User-Agent", FETCH_USER_AGENT)
                .header("Accept", "application/json, text/csv, text/plain, */*")
        if (conditional) validators?.load(url)?.let {
            it.etag?.let { tag -> builder.header("If-None-Match", tag) }
            it.lastModified?.let { lm -> builder.header("If-Modified-Since", lm) }
        }
        val request = builder.build()
        var lastError: IOException? = null
        repeat(2) { attempt ->
            try {
                val response = httpClient.newCall(request).execute()
                if (response.code() == 304) {
                    response.close()
                    throw UaNotModified(url)
                }
                if (!response.isSuccessful) {
                    response.close()
                    throw IOException("HTTP ${response.code()} for $url")
                }
                return response
            } catch (e: IOException) {
                Timber.w(e, "UA fetch attempt %d failed: %s", attempt + 1, url)
                lastError = e
            }
        }
        throw lastError ?: IOException("UA fetch failed: $url")
    }

    companion object {
        private const val FETCH_USER_AGENT = "element-android-ua-spoof"
    }
}
