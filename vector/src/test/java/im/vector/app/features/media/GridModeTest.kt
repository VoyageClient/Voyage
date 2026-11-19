/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import im.vector.app.features.media.ImageContentRenderer.Companion.gridMode
import im.vector.app.features.media.ImageContentRenderer.Mode
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.util.MimeTypes

class GridModeTest {

    @Test
    fun `alpha-capable formats a server can scale get a thumbnail in the grid`() {
        listOf(MimeTypes.Png, MimeTypes.Webp, MimeTypes.Gif, MimeTypes.Apng, MimeTypes.Jpeg, null).forEach {
            gridMode(it) shouldBeEqualTo Mode.THUMBNAIL
        }
    }

    @Test
    fun `formats a server cannot thumbnail are fetched whole`() {
        gridMode(MimeTypes.Svg) shouldBeEqualTo Mode.STICKER
        gridMode(MimeTypes.Jxl) shouldBeEqualTo Mode.STICKER
    }
}
