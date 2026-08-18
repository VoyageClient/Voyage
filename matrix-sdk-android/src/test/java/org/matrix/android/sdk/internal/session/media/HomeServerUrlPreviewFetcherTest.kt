/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.media

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotContain
import org.junit.Test
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.session.content.ContentUrlResolver
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.internal.network.token.AccessTokenProvider

private const val URL = "https://matrix.org"
private const val THUMBNAIL_MXC = "mxc://example.org/thumbnail"
private const val THUMBNAIL_HTTP = "https://example.org/_matrix/media/thumbnail"
private val IMAGE_BYTES = ByteArray(64) { it.toByte() }

private val SERVER_PREVIEW: JsonDict = mapOf(
        "og:url" to "https://matrix.org/",
        "og:title" to "Matrix.org",
        "og:image" to THUMBNAIL_MXC,
        "og:image:width" to 800.0,
        "matrix:image:size" to 1234.0,
        "some:other:field" to "value"
)

class HomeServerUrlPreviewFetcherTest {

    private val getRawPreviewUrlTask = mockk<GetRawPreviewUrlTask>()
    private val contentUrlResolver = mockk<ContentUrlResolver>()
    private val okHttpClient = mockk<OkHttpClient>()
    private val accessTokenProvider = mockk<AccessTokenProvider> { every { getToken() } returns "token" }
    private val sentRequest = slot<Request>()
    private var imageBytes: ByteArray? = IMAGE_BYTES
    private var thumbnailContentType = "image/jpeg"

    private val fetcher = HomeServerUrlPreviewFetcher(
            getRawPreviewUrlTask = getRawPreviewUrlTask,
            contentUrlResolver = contentUrlResolver,
            okHttpClient = okHttpClient,
            accessTokenProvider = accessTokenProvider,
            coroutineDispatchers = MatrixCoroutineDispatchers(
                    io = Dispatchers.Unconfined,
                    computation = Dispatchers.Unconfined,
                    main = Dispatchers.Unconfined,
                    crypto = Dispatchers.Unconfined,
                    dmVerif = Dispatchers.Unconfined
            )
    )

    init {
        coEvery { getRawPreviewUrlTask.execute(any()) } returns SERVER_PREVIEW
        every { contentUrlResolver.resolveForDownload(THUMBNAIL_MXC, null) } returns ContentUrlResolver.ResolvedMethod.GET(THUMBNAIL_HTTP)
        every { contentUrlResolver.requiresAuthentication(any()) } returns true
        every { okHttpClient.newCall(capture(sentRequest)) } answers {
            mockk<Call> {
                every { execute() } answers {
                    Response.Builder()
                            .request(sentRequest.captured)
                            .protocol(Protocol.HTTP_1_1)
                            .code(if (imageBytes == null) 404 else 200)
                            .message("message")
                            .header("Content-Type", thumbnailContentType)
                            .body(ResponseBody.create(MediaType.parse(thumbnailContentType), imageBytes ?: ByteArray(0)))
                            .build()
                }
            }
        }
    }

    @Test
    fun `the fields the homeserver returns are kept`() = runTest {
        val preview = fetcher.fetch(URL)!!

        preview.fields["og:title"] shouldBeEqualTo "Matrix.org"
        preview.fields["og:image:width"] shouldBeEqualTo 800.0
    }

    @Test
    fun `fields which are not part of a preview are dropped`() = runTest {
        val fields = fetcher.fetch(URL)!!.fields

        fields.keys shouldNotContain "some:other:field"
        // The size and the image are recomputed from the bytes we reupload.
        fields.keys shouldNotContain "matrix:image:size"
        fields.keys shouldNotContain "og:image"
        fields.keys shouldContain "og:url"
    }

    @Test
    fun `the thumbnail is fetched back so it can be reuploaded`() = runTest {
        val preview = fetcher.fetch(URL)!!

        preview.image!!.bytes shouldBeEqualTo IMAGE_BYTES
        sentRequest.captured.url().toString() shouldBeEqualTo THUMBNAIL_HTTP
    }

    @Test
    fun `the thumbnail is fetched with our credentials when the homeserver requires them`() = runTest {
        fetcher.fetch(URL)

        sentRequest.captured.header("Authorization") shouldBeEqualTo "Bearer token"
    }

    @Test
    fun `an unauthenticated homeserver is not sent our token`() = runTest {
        every { contentUrlResolver.requiresAuthentication(any()) } returns false

        fetcher.fetch(URL)

        sentRequest.captured.header("Authorization").shouldBeNull()
    }

    @Test
    fun `a preview survives a thumbnail which fails to download`() = runTest {
        imageBytes = null

        val preview = fetcher.fetch(URL)!!

        preview.image.shouldBeNull()
        preview.fields["og:title"] shouldBeEqualTo "Matrix.org"
    }

    @Test
    fun `an oversized thumbnail is refused`() = runTest {
        imageBytes = ByteArray(3 * 1024 * 1024)

        fetcher.fetch(URL)!!.image.shouldBeNull()
    }

    @Test
    fun `media which is not an image is refused`() = runTest {
        thumbnailContentType = "application/octet-stream"

        fetcher.fetch(URL)!!.image.shouldBeNull()
    }

    @Test
    fun `a remote thumbnail is not fetched, since only our own media is reuploadable`() = runTest {
        coEvery { getRawPreviewUrlTask.execute(any()) } returns SERVER_PREVIEW + ("og:image" to "https://matrix.org/logo.png")

        fetcher.fetch(URL)!!.image.shouldBeNull()
    }

    @Test
    fun `a homeserver with nothing to say gives no preview`() = runTest {
        coEvery { getRawPreviewUrlTask.execute(any()) } returns emptyMap()

        fetcher.fetch(URL).shouldBeNull()
    }
}
