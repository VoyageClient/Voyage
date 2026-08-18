/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.media

import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.internal.di.LinkPreview
import java.nio.charset.Charset
import javax.inject.Inject

private const val MAX_HTML_SIZE = 512 * 1024

private val META_CHARSET = Regex("<\\s*meta[^>]*charset\\s*=\\s*\"?([a-zA-Z0-9_-]+)\"?", RegexOption.IGNORE_CASE)
private val XML_ENCODING = Regex("\\s*<\\s*\\?\\s*xml[^>]*encoding=\"([a-zA-Z0-9_-]+)\"", RegexOption.IGNORE_CASE)

/**
 * Reads a page from the site itself and turns it into preview fields, the way Synapse's `/preview_url`
 * would have. Nothing here goes through a homeserver — which is the point of generating MSC4095
 * previews on the device.
 */
internal class UrlPreviewFetcher @Inject constructor(
        @LinkPreview private val okHttpClient: OkHttpClient,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
) {

    suspend fun fetch(url: String): FetchedPreview? {
        val fetched = get(url) { response ->
            val contentType = response.header("Content-Type").orEmpty()
            when {
                contentType.isHtml() -> response.readHtml()?.let { Fetched.Html(it, response.request().url()) }
                contentType.isImage() -> response.readImage(url)?.let { Fetched.Media(it) }
                else -> null
            }
        }
        return when (fetched) {
            is Fetched.Html -> fetched.toPreview()
            is Fetched.Media -> fetched.preview
            null -> null
        }
    }

    private sealed class Fetched {
        class Html(val page: Page, val url: HttpUrl) : Fetched()
        class Media(val preview: FetchedPreview) : Fetched()
    }

    private class Page(val bytes: ByteArray, val charset: Charset)

    private fun Response.readHtml(): Page? {
        val body = body() ?: return null
        // Truncated rather than refused: the tags we want are in the head, at the very top.
        val bytes = body.byteStream().readAtMost(MAX_HTML_SIZE)
        return bytes.takeIf { it.isNotEmpty() }?.let { Page(it, it.charset(header("Content-Type"))) }
    }

    /**
     * A link straight to an image previews as that image, as Synapse does for a media content type.
     */
    private fun Response.readImage(url: String): FetchedPreview? {
        val image = readThumbnail() ?: return null
        val fields = mutableMapOf<String, Any>("og:url" to url)
        HttpUrl.parse(url)?.pathSegments()?.lastOrNull()?.takeIf { it.isNotEmpty() }
                ?.let { fields["og:description"] = it }
        return FetchedPreview(fields = fields, image = image)
    }

    private suspend fun Fetched.Html.toPreview(): FetchedPreview? {
        // Decoded here rather than by Jsoup's stream parser, which would sniff the charset a second time.
        val document = Jsoup.parse(String(page.bytes, page.charset), url.toString())
        val og = OpenGraphParser.parse(document)
        if (og.isEmpty()) return null
        val fields = mutableMapOf<String, Any>().apply { putAll(og) }
        // Sizes are numbers everywhere else in a preview, including in what /preview_url returns.
        listOf("og:image:width", "og:image:height").forEach { key ->
            val size = (og[key])?.toIntOrNull()
            if (size == null) fields.remove(key) else fields[key] = size
        }
        val image = (og["og:image"])?.let { imageUrl -> get(imageUrl) { it.readThumbnail() } }
        // An image we could not fetch must not be advertised: the url would not resolve for anyone else.
        if (image == null) fields.remove("og:image")
        return FetchedPreview(fields, image)
    }

    private suspend fun <T> get(url: String, block: (Response) -> T?): T? {
        val httpUrl = HttpUrl.parse(url)?.takeIf { it.scheme() == "https" || it.scheme() == "http" } ?: return null
        val request = Request.Builder()
                .url(httpUrl)
                .header("Accept", "text/html,application/xhtml+xml,image/*;q=0.8,*/*;q=0.5")
                .build()
        return withContext(coroutineDispatchers.io) {
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) block(response) else null
            }
        }
    }
}

private fun String.isHtml() = startsWith("text/html", ignoreCase = true) || startsWith("application/xhtml", ignoreCase = true)

private fun String.isImage() = startsWith("image/", ignoreCase = true)

/**
 * Synapse's precedence: the meta tag, then the XML declaration, then the header, then UTF-8.
 */
private fun ByteArray.charset(contentType: String?): Charset {
    val start = String(this, 0, size.coerceAtMost(1024), Charsets.ISO_8859_1)
    val declared = META_CHARSET.find(start)?.groupValues?.get(1)
            ?: XML_ENCODING.find(start)?.groupValues?.get(1)
            ?: contentType?.substringAfter("charset=", "")?.substringBefore(';')?.trim('"', '\'', ' ')?.takeIf { it.isNotEmpty() }
    return declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
}
