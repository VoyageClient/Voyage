/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.media

import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test

private const val URL = "https://matrix.org"

private val ENCRYPTED_FILE = mapOf(
        "url" to "mxc://example.org/encrypted",
        "key" to mapOf(
                "k" to "GRAgOUnbbkcd-UWoX5kTiIXJII81qwpSCnxLd5X6pxU",
                "alg" to "A256CTR",
                "ext" to true,
                "kty" to "oct",
                "key_ops" to listOf("encrypt", "decrypt")
        ),
        "iv" to "kZeoJfx4ehoAAAAAAAAAAA",
        "hashes" to mapOf("sha256" to "WDOJYFegjAHNlaJmOhEPpE/3reYeD1pRvPVcta4Tgbg"),
        "v" to "v2"
)

private val FULL_PREVIEW = mapOf(
        "matrix:matched_url" to URL,
        "og:url" to "https://matrix.org/",
        "og:site_name" to "Matrix.org",
        "og:title" to "Matrix.org",
        "og:description" to "The open protocol",
        "og:image" to "mxc://example.org/thumbnail",
        "og:image:width" to 800.0,
        "og:image:height" to 400.0,
        "og:image:type" to "image/jpeg",
        "matrix:image:size" to 16588.0
)

class BundledUrlPreviewsTest {

    private fun content(vararg pairs: Pair<String, Any>) = mapOf<String, Any>("body" to URL, *pairs)

    @Test
    fun `a content without the field bundles nothing`() {
        BundledUrlPreviews.parse(content()).shouldBeNull()
        BundledUrlPreviews.parse(null).shouldBeNull()
    }

    @Test
    fun `a field which is not an array bundles nothing`() {
        BundledUrlPreviews.parse(content("m.url_previews" to "not an array")).shouldBeNull()
    }

    @Test
    fun `an empty array asks for no preview at all`() {
        BundledUrlPreviews.parse(content("m.url_previews" to emptyList<Any>()))!!.shouldBeEmpty()
    }

    @Test
    fun `the stable field is read`() {
        val previews = BundledUrlPreviews.parse(content("m.url_previews" to listOf(FULL_PREVIEW)))!!

        previews.size shouldBeEqualTo 1
        previews[0].matchedUrl shouldBeEqualTo URL
        previews[0].previewUrlData!!.title shouldBeEqualTo "Matrix.org"
    }

    @Test
    fun `the unstable field is read`() {
        val previews = BundledUrlPreviews.parse(content("com.beeper.linkpreviews" to listOf(FULL_PREVIEW)))!!

        previews.size shouldBeEqualTo 1
        previews[0].previewUrlData!!.title shouldBeEqualTo "Matrix.org"
    }

    @Test
    fun `the stable field wins over the unstable one`() {
        val unstable = FULL_PREVIEW + ("og:title" to "Stale")
        val previews = BundledUrlPreviews.parse(
                content("m.url_previews" to listOf(FULL_PREVIEW), "com.beeper.linkpreviews" to listOf(unstable))
        )!!

        previews[0].previewUrlData!!.title shouldBeEqualTo "Matrix.org"
    }

    @Test
    fun `the unstable matched url is read`() {
        val preview = FULL_PREVIEW - "matrix:matched_url" + ("matched_url" to URL)

        BundledUrlPreviews.parse(content("m.url_previews" to listOf(preview)))!![0].matchedUrl shouldBeEqualTo URL
    }

    @Test
    fun `all the fields are mapped`() {
        val data = BundledUrlPreviews.parse(content("m.url_previews" to listOf(FULL_PREVIEW)))!![0].previewUrlData!!

        data.url shouldBeEqualTo "https://matrix.org/"
        data.siteName shouldBeEqualTo "Matrix.org"
        data.title shouldBeEqualTo "Matrix.org"
        data.description shouldBeEqualTo "The open protocol"
        data.mxcUrl shouldBeEqualTo "mxc://example.org/thumbnail"
        data.imageWidth shouldBeEqualTo 800
        data.imageHeight shouldBeEqualTo 400
        data.imageMimeType shouldBeEqualTo "image/jpeg"
        data.encryptedImage.shouldBeNull()
    }

    @Test
    fun `dimensions are read whichever number type they were parsed as`() {
        val preview = FULL_PREVIEW + mapOf("og:image:width" to 800, "og:image:height" to 400L)
        val data = BundledUrlPreviews.parse(content("m.url_previews" to listOf(preview)))!![0].previewUrlData!!

        data.imageWidth shouldBeEqualTo 800
        data.imageHeight shouldBeEqualTo 400
    }

    @Test
    fun `html entities of the text fields are unescaped`() {
        val preview = FULL_PREVIEW + mapOf("og:title" to "Ben &#38; Jerry", "og:description" to "Caf&#233;")
        val data = BundledUrlPreviews.parse(content("m.url_previews" to listOf(preview)))!![0].previewUrlData!!

        data.title shouldBeEqualTo "Ben & Jerry"
        data.description shouldBeEqualTo "Café"
    }

    @Test
    fun `an url which is missing from the body is ignored`() {
        val preview = FULL_PREVIEW + ("matrix:matched_url" to "https://not-in-the-body.org")

        BundledUrlPreviews.parse(content("m.url_previews" to listOf(preview)))!!.shouldBeEmpty()
    }

    @Test
    fun `an entry without a matched url is ignored`() {
        val preview = FULL_PREVIEW - "matrix:matched_url"

        BundledUrlPreviews.parse(content("m.url_previews" to listOf(preview)))!!.shouldBeEmpty()
    }

    @Test
    fun `entries which are not objects are ignored`() {
        val previews = BundledUrlPreviews.parse(content("m.url_previews" to listOf("nonsense", FULL_PREVIEW)))!!

        previews.size shouldBeEqualTo 1
    }

    @Test
    fun `an entry which only whitelists an url carries no data`() {
        val previews = BundledUrlPreviews.parse(content("m.url_previews" to listOf(mapOf("matrix:matched_url" to URL))))!!

        previews.size shouldBeEqualTo 1
        previews[0].matchedUrl shouldBeEqualTo URL
        previews[0].previewUrlData.shouldBeNull()
    }

    @Test
    fun `a preview of a remote image is not displayed`() {
        val preview = mapOf("matrix:matched_url" to URL, "og:image" to "https://matrix.org/logo.png")
        val previews = BundledUrlPreviews.parse(content("m.url_previews" to listOf(preview)))!!

        previews[0].previewUrlData.shouldBeNull()
    }

    @Test
    fun `an encrypted image is read from the stable field`() {
        val preview = FULL_PREVIEW - "og:image" + ("matrix:image:encrypted" to ENCRYPTED_FILE)
        val data = BundledUrlPreviews.parse(content("m.url_previews" to listOf(preview)))!![0].previewUrlData!!

        data.encryptedImage!!.url shouldBeEqualTo "mxc://example.org/encrypted"
        data.mxcUrl.shouldBeNull()
    }

    @Test
    fun `an encrypted image is read from the unstable field`() {
        val preview = FULL_PREVIEW - "og:image" + ("beeper:image:encryption" to ENCRYPTED_FILE)
        val data = BundledUrlPreviews.parse(content("m.url_previews" to listOf(preview)))!![0].previewUrlData!!

        data.encryptedImage!!.url shouldBeEqualTo "mxc://example.org/encrypted"
    }

    @Test
    fun `an encrypted image hides a clear one, which the sender should not have sent`() {
        val preview = FULL_PREVIEW + ("matrix:image:encrypted" to ENCRYPTED_FILE)
        val data = BundledUrlPreviews.parse(content("m.url_previews" to listOf(preview)))!![0].previewUrlData!!

        data.mxcUrl.shouldBeNull()
        data.encryptedImage!!.url shouldBeEqualTo "mxc://example.org/encrypted"
    }

    @Test
    fun `an incomplete encrypted image is ignored`() {
        val preview = mapOf(
                "matrix:matched_url" to URL,
                "matrix:image:encrypted" to (ENCRYPTED_FILE - "key")
        )
        val previews = BundledUrlPreviews.parse(content("m.url_previews" to listOf(preview)))!!

        previews[0].previewUrlData.shouldBeNull()
    }

    @Test
    fun `several previews are all kept, in order`() {
        val otherUrl = "https://element.io"
        val other = mapOf("matrix:matched_url" to otherUrl, "og:title" to "Element")
        val previews = BundledUrlPreviews.parse(
                mapOf("body" to "$URL and $otherUrl", "m.url_previews" to listOf(FULL_PREVIEW, other))
        )!!

        previews.map { it.matchedUrl } shouldBeEqualTo listOf(URL, otherUrl)
    }

    @Test
    fun `a content without a body displays no preview`() {
        BundledUrlPreviews.parse(mapOf("m.url_previews" to listOf(FULL_PREVIEW)))!!.shouldBeEmpty()
    }
}
