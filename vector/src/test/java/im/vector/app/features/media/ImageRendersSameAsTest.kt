/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImageRendersSameAsTest {

    private val imageContentRenderer = ImageContentRenderer(
            context = RuntimeEnvironment.getApplication(),
            localFilesHelper = mockk(relaxed = true),
            activeSessionHolder = mockk(relaxed = true),
            dimensionConverter = mockk(relaxed = true),
            vectorPreferences = mockk(relaxed = true),
            failedMediaTracker = mockk(relaxed = true),
    )

    private val sticker = ImageContentRenderer.Data(
            eventId = "\$local.echo",
            filename = "sticker.webp",
            mimeType = "image/webp",
            url = "mxc://hs/sticker",
            elementToDecrypt = null,
            height = 128,
            maxHeight = 256,
            width = 128,
            maxWidth = 256,
            stableId = "stable",
    )

    private fun same(a: ImageContentRenderer.Data, b: ImageContentRenderer.Data) = with(imageContentRenderer) { a.rendersSameAs(b) }

    /** The send flow rebinds with the remote id and flips allowNonMxcUrls, both invisible on screen. */
    @Test
    fun `a sticker keeps its render across the local echo swap`() {
        same(sticker, sticker.copy(eventId = "\$remote", allowNonMxcUrls = true)) shouldBeEqualTo true
    }

    @Test
    fun `a different url is different content`() {
        same(sticker, sticker.copy(url = "mxc://hs/other")) shouldBeEqualTo false
    }

    @Test
    fun `a local url must agree on allowNonMxcUrls, which drives the scale type`() {
        val local = sticker.copy(url = "content://media/1", allowNonMxcUrls = true)

        same(local, local.copy(allowNonMxcUrls = false)) shouldBeEqualTo false
    }

    @Test
    fun `new dimensions are new pixels`() {
        same(sticker, sticker.copy(width = 64)) shouldBeEqualTo false
        same(sticker, sticker.copy(maxHeight = 512)) shouldBeEqualTo false
    }

    @Test
    fun `the blurhash, the preserved copy and the gallery index all count`() {
        same(sticker, sticker.copy(blurHash = "LEHV6nWB")) shouldBeEqualTo false
        same(sticker, sticker.copy(preservedFile = File("/tmp/x"))) shouldBeEqualTo false
        same(sticker, sticker.copy(galleryIndex = 2)) shouldBeEqualTo false
    }

    @Test
    fun `who sent it and when do not change the picture`() {
        same(sticker, sticker.copy(senderName = "alice", timestampMs = 42L)) shouldBeEqualTo true
    }
}
