/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.richtext

import im.vector.lib.richtext.linkify.Linkifier
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomSchemeLinkifierTest {
    private val linkifier = Linkifier(TestMatrixPatterns)

    @Test
    fun `links custom schemes and excludes surrounding punctuation`() {
        val text = SpanBuffer("(foo+bar.1://host/path(a)), then other://example.org/item.")

        linkifier.linkify(text)

        val links = text.spansOf<RichStyle.Url>().map { span ->
            Triple(text.substring(span.start, span.end), (span.style as RichStyle.Url).url, span.start)
        }
        assertEquals(
                listOf(
                        Triple("foo+bar.1://host/path(a)", "foo+bar.1://host/path(a)", 1),
                        Triple("other://example.org/item", "other://example.org/item", 33),
                ),
                links,
        )
    }

    @Test
    fun `keeps the scheme when a domain also matches web url detection`() {
        val text = SpanBuffer("foo://example.com/path")

        linkifier.linkify(text)

        assertEquals("foo://example.com/path", (text.spansOf<RichStyle.Url>().single().style as RichStyle.Url).url)
    }
}
