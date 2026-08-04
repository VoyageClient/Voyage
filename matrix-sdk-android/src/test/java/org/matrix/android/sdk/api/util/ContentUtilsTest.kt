/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.util

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class ContentUtilsTest {

    @Test
    fun `given a legacy fallback in both bodies, when extracting, then only the reply text is kept`() {
        val body = "> <@alice:example.org> original\n\nmy answer"
        val formatted = "<mx-reply><blockquote>original</blockquote></mx-reply>my answer"

        ContentUtils.extractUsefulTextFromReply(body, formatted) shouldBeEqualTo "my answer"
    }

    @Test
    fun `given a modern reply that is a blockquote, when extracting, then the body is left untouched`() {
        val body = "> a quote of my own"
        val formatted = "<blockquote>a quote of my own</blockquote>"

        ContentUtils.extractUsefulTextFromReply(body, formatted) shouldBeEqualTo body
    }

    @Test
    fun `given a modern reply spanning blockquoted paragraphs, when extracting, then the body is left untouched`() {
        val body = "> first quoted line\n\n> second quoted line"
        val formatted = "<blockquote>first quoted line</blockquote><blockquote>second quoted line</blockquote>"

        ContentUtils.extractUsefulTextFromReply(body, formatted) shouldBeEqualTo body
    }

    @Test
    fun `given no formatted body, when extracting, then the legacy fallback is still stripped`() {
        val body = "> <@alice:example.org> original\n\nmy answer"

        ContentUtils.extractUsefulTextFromReply(body, null) shouldBeEqualTo "my answer"
    }

    @Test
    fun `given a fallback with no reply text, when extracting, then the body is left untouched`() {
        val body = "> <@alice:example.org> original"

        ContentUtils.extractUsefulTextFromReply(body) shouldBeEqualTo body
    }
}
