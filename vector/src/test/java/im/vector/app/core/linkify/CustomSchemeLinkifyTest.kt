/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.linkify

import android.text.SpannableStringBuilder
import android.text.style.URLSpan
import im.vector.app.core.utils.isTappableLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CustomSchemeLinkifyTest {
    @Test
    fun `links custom schemes with their full uri`() {
        val text = SpannableStringBuilder("(foo+bar.1://host/path(a)), then other://example.org/item.")

        VectorLinkify.addLinks(text)

        val links = text.getSpans(0, text.length, URLSpan::class.java).map {
            text.substring(text.getSpanStart(it), text.getSpanEnd(it)) to it.url
        }
        assertEquals(
                listOf(
                        "foo+bar.1://host/path(a)" to "foo+bar.1://host/path(a)",
                        "other://example.org/item" to "other://example.org/item",
                ),
                links,
        )
        assertTrue("foo+bar.1://host/path(a)".isTappableLink())
    }
}
