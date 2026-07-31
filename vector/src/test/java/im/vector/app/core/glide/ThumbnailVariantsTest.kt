/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.graphics.drawable.Drawable
import com.bumptech.glide.load.DataSource
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test

class ThumbnailVariantsTest {

    private val variants = ThumbnailVariants()

    @Test
    fun `a media nothing has served yet has no preference`() {
        variants.servedBy("mxc://example.org/media").shouldBeNull()
    }

    @Test
    fun `the last variant to serve a media wins`() {
        variants.remember("mxc://example.org/media", "https://example.org/still")
        variants.remember("mxc://example.org/media", "https://example.org/animated")

        variants.servedBy("mxc://example.org/media") shouldBeEqualTo "https://example.org/animated"
    }

    @Test
    fun `each media is remembered separately`() {
        variants.remember("mxc://example.org/one", "https://example.org/one-still")
        variants.remember("mxc://example.org/two", "https://example.org/two-animated")

        variants.servedBy("mxc://example.org/one") shouldBeEqualTo "https://example.org/one-still"
        variants.servedBy("mxc://example.org/two") shouldBeEqualTo "https://example.org/two-animated"
    }

    @Test
    fun `the memory is bounded, dropping what has gone longest unused`() {
        repeat(600) { variants.remember("mxc://example.org/$it", "https://example.org/$it") }

        variants.servedBy("mxc://example.org/0").shouldBeNull()
        variants.servedBy("mxc://example.org/599") shouldBeEqualTo "https://example.org/599"
    }

    @Test
    fun `a completed load records the variant that answered it`() {
        val listener = RememberServedVariant(variants, "mxc://example.org/media")

        val handled = listener.onResourceReady(mockk<Drawable>(), "https://example.org/animated", null, DataSource.MEMORY_CACHE, true)

        variants.servedBy("mxc://example.org/media") shouldBeEqualTo "https://example.org/animated"
        handled shouldBeEqualTo false
    }
}
