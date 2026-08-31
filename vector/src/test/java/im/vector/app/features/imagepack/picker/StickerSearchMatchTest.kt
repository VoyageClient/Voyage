/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.picker

import im.vector.app.features.imagepack.ResolvedImage
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Test

class StickerSearchMatchTest {

    private fun image(shortcode: String, body: String? = null, packName: String? = null) = ResolvedImage(
            shortcode = shortcode,
            mxcUrl = "mxc://example.org/$shortcode",
            body = body,
            info = null,
            usages = setOf("m.sticker"),
            packDisplayName = packName,
    )

    @Test
    fun `matches shortcode case insensitively`() {
        image("BlobCat").matchesQuery("blob").shouldBeTrue()
    }

    @Test
    fun `matches body`() {
        image("bc", body = "happy cat").matchesQuery("Cat").shouldBeTrue()
    }

    @Test
    fun `matches pack name`() {
        image("bc", packName = "Blobs").matchesQuery("blobs").shouldBeTrue()
    }

    @Test
    fun `does not match unrelated query`() {
        image("bc", body = "happy cat", packName = "Blobs").matchesQuery("dog").shouldBeFalse()
    }
}
