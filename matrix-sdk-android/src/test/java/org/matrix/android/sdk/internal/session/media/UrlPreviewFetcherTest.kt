/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.media

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers

private const val PAGE_URL = "https://example.org/page"
private const val IMAGE_URL = "https://example.org/image.png"
private val IMAGE_BYTES = ByteArray(128) { it.toByte() }

private val PAGE = """
    <html><head>
    <meta property="og:title" content="The title" />
    <meta property="og:description" content="The description" />
    <meta property="og:image" content="/image.png" />
    <meta property="og:image:width" content="800" />
    <meta property="og:image:height" content="400" />
    </head><body></body></html>
""".trimIndent()

class UrlPreviewFetcherTest {

    private val okHttpClient = mockk<OkHttpClient>()
    private val responses = mutableMapOf<String, Response.Builder.() -> Unit>()

    private val fetcher = UrlPreviewFetcher(
            okHttpClient = okHttpClient,
            coroutineDispatchers = MatrixCoroutineDispatchers(
                    io = Dispatchers.Unconfined,
                    computation = Dispatchers.Unconfined,
                    main = Dispatchers.Unconfined,
                    crypto = Dispatchers.Unconfined,
                    dmVerif = Dispatchers.Unconfined
            )
    )

    init {
        every { okHttpClient.newCall(any()) } answers {
            val request = firstArg<Request>()
            mockk<Call> {
                every { execute() } answers {
                    val builder = Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(ResponseBody.create(null, ByteArray(0)))
                    responses[request.url().toString()]?.invoke(builder) ?: builder.code(404).message("Not Found")
                    builder.build()
                }
            }
        }
        givenPage(PAGE_URL, PAGE)
        givenImage(IMAGE_URL, IMAGE_BYTES)
    }

    private fun givenPage(url: String, html: String, contentType: String = "text/html; charset=utf-8") {
        givenPage(url, html.toByteArray(), contentType)
    }

    private fun givenPage(url: String, bytes: ByteArray, contentType: String) {
        responses[url] = {
            header("Content-Type", contentType)
            body(ResponseBody.create(MediaType.parse(contentType), bytes))
        }
    }

    private fun givenImage(url: String, bytes: ByteArray, contentType: String = "image/png") {
        givenPage(url, bytes, contentType)
    }

    @Test
    fun `a page is read into preview fields`() = runTest {
        val preview = fetcher.fetch(PAGE_URL)!!

        preview.fields["og:title"] shouldBeEqualTo "The title"
        preview.fields["og:description"] shouldBeEqualTo "The description"
        preview.fields["og:image"] shouldBeEqualTo IMAGE_URL
    }

    @Test
    fun `the image of a page is fetched with it`() = runTest {
        val preview = fetcher.fetch(PAGE_URL)!!

        val image = preview.image!!
        image.bytes shouldBeEqualTo IMAGE_BYTES
        image.mimeType shouldBeEqualTo "image/png"
    }

    @Test
    fun `image sizes are numbers, as they are everywhere else in a preview`() = runTest {
        val preview = fetcher.fetch(PAGE_URL)!!

        preview.fields["og:image:width"] shouldBeEqualTo 800
        preview.fields["og:image:height"] shouldBeEqualTo 400
    }

    @Test
    fun `an image we cannot fetch is not advertised`() = runTest {
        responses.remove(IMAGE_URL)

        val preview = fetcher.fetch(PAGE_URL)!!

        preview.image.shouldBeNull()
        preview.fields["og:image"].shouldBeNull()
        preview.fields["og:title"] shouldBeEqualTo "The title"
    }

    @Test
    fun `a link straight to an image previews as that image`() = runTest {
        val preview = fetcher.fetch(IMAGE_URL)!!

        preview.image!!.bytes shouldBeEqualTo IMAGE_BYTES
        preview.fields["og:description"] shouldBeEqualTo "image.png"
        preview.fields["og:url"] shouldBeEqualTo IMAGE_URL
    }

    @Test
    fun `an image url which does not serve an image is refused`() = runTest {
        givenPage(IMAGE_URL, "<html><body>Not an image</body></html>")

        val preview = fetcher.fetch(PAGE_URL)!!

        preview.image.shouldBeNull()
        preview.fields["og:image"].shouldBeNull()
    }

    @Test
    fun `an oversized image is refused`() = runTest {
        givenImage(IMAGE_URL, ByteArray(3 * 1024 * 1024))

        fetcher.fetch(PAGE_URL)!!.image.shouldBeNull()
    }

    @Test
    fun `a page which is neither html nor an image is not previewed`() = runTest {
        givenPage(PAGE_URL, "{}", contentType = "application/json")

        fetcher.fetch(PAGE_URL).shouldBeNull()
    }

    @Test
    fun `a page which does not load is not previewed`() = runTest {
        responses.remove(PAGE_URL)

        fetcher.fetch(PAGE_URL).shouldBeNull()
    }

    @Test
    fun `a page with no tags worth reading is not previewed`() = runTest {
        givenPage(PAGE_URL, "<html><head></head><body></body></html>")

        fetcher.fetch(PAGE_URL).shouldBeNull()
    }

    @Test
    fun `the charset of the header is used`() = runTest {
        val html = "<html><head><meta property=\"og:title\" content=\"Café\" /></head><body></body></html>"
        givenPage(PAGE_URL, html.toByteArray(Charsets.ISO_8859_1), "text/html; charset=iso-8859-1")

        fetcher.fetch(PAGE_URL)!!.fields["og:title"] shouldBeEqualTo "Café"
    }

    @Test
    fun `a charset declared by the page itself wins over the header`() = runTest {
        val html = "<html><head><meta charset=\"iso-8859-1\">" +
                "<meta property=\"og:title\" content=\"Café\" /></head><body></body></html>"
        givenPage(PAGE_URL, html.toByteArray(Charsets.ISO_8859_1), "text/html; charset=utf-8")

        fetcher.fetch(PAGE_URL)!!.fields["og:title"] shouldBeEqualTo "Café"
    }

    @Test
    fun `an unknown charset falls back to utf-8`() = runTest {
        val html = "<html><head><meta property=\"og:title\" content=\"Café\" /></head><body></body></html>"
        givenPage(PAGE_URL, html.toByteArray(), "text/html; charset=definitely-not-a-charset")

        fetcher.fetch(PAGE_URL)!!.fields["og:title"] shouldBeEqualTo "Café"
    }

    @Test
    fun `only web urls are fetched`() = runTest {
        fetcher.fetch("ftp://example.org/file").shouldBeNull()
        fetcher.fetch("mxc://example.org/media").shouldBeNull()
        fetcher.fetch("not a url").shouldBeNull()
    }

    @Test
    fun `an enormous page is read up to its head`() = runTest {
        val padding = "<!-- ${"x".repeat(600 * 1024)} -->"
        givenPage(PAGE_URL, PAGE + padding)

        fetcher.fetch(PAGE_URL).shouldNotBeNull()
    }
}
