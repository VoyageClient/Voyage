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
    fun `given no formatted body and a legacy emote fallback, when extracting, then the fallback is stripped`() {
        val body = "> * <@alice:example.org> waves\n\nhi"

        ContentUtils.extractUsefulTextFromReply(body, null) shouldBeEqualTo "hi"
    }

    @Test
    fun `given no formatted body and a body opening with the sender's own quote, when extracting, then the body is left untouched`() {
        val body = "> Мнение буду с него выражать типа.\n\nя про вот это"

        ContentUtils.extractUsefulTextFromReply(body, null) shouldBeEqualTo body
        ContentUtils.extractUsefulTextFromReply(body) shouldBeEqualTo body
    }

    @Test
    fun `given a sender quote that only resembles a fallback, when extracting, then the body is left untouched`() {
        listOf(
                "> <b> is bold\n\nright?",
                "> <@nocolon> hi\n\nright?",
                "> <@user:server hi\n\nright?",
        ).forEach { ContentUtils.extractUsefulTextFromReply(it) shouldBeEqualTo it }
    }

    @Test
    fun `given a fallback from a server with a port, when extracting, then the fallback is stripped`() {
        val body = "> <@alice:example.org:8448> original\n\nmy answer"

        ContentUtils.extractUsefulTextFromReply(body) shouldBeEqualTo "my answer"
    }

    @Test
    fun `given a fallback with no reply text, when extracting, then the body is left untouched`() {
        val body = "> <@alice:example.org> original"

        ContentUtils.extractUsefulTextFromReply(body) shouldBeEqualTo body
    }
}
