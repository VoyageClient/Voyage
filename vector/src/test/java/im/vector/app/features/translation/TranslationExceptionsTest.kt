/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.translation

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotContain
import org.junit.Test

class TranslationExceptionsTest {

    private val permalink = "https://matrix.to/#/!room:example.org/\$event?via=example.org&amp;via=other.org"

    @Test
    fun `an escaped ampersand in a bare url stays attached to it`() {
        val html = "Schau mal $permalink bitte"
        val exceptions = TranslationExceptions.forReceivedHtml(html)
        exceptions.text shouldNotContain "via"
        exceptions.restore(exceptions.text.replace("Schau mal", "Look").replace("bitte", "please")) shouldBeEqualTo
                "Look $permalink please"
    }

    @Test
    fun `a glued url gets its whitespace back`() {
        val exceptions = TranslationExceptions.forReceivedHtml("Schau $permalink")
        exceptions.restore(exceptions.text.replace("Schau ", "Look:")) shouldBeEqualTo "Look: $permalink"
    }

    @Test
    fun `a url inside a link keeps its tags adjacent`() {
        val html = "Siehe <a href=\"https://example.org/?a=1&amp;b=2\">https://example.org/?a=1&amp;b=2</a>"
        val exceptions = TranslationExceptions.forReceivedHtml(html)
        exceptions.restore(exceptions.text.replace("Siehe", "See")) shouldBeEqualTo
                "See <a href=\"https://example.org/?a=1&amp;b=2\">https://example.org/?a=1&amp;b=2</a>"
    }

    @Test
    fun `a mention pill from another host or a matrix uri keeps its label`() {
        listOf("https://link.example.org/#/@alice:example.org", "matrix:u/alice:example.org").forEach { href ->
            val html = "Hallo <a href=\"$href\">Alice Liebe</a>"
            val exceptions = TranslationExceptions.forReceivedHtml(html)
            exceptions.text shouldNotContain "Liebe"
            exceptions.restore(exceptions.text.replace("Hallo", "Hello")) shouldBeEqualTo "Hello <a href=\"$href\">Alice Liebe</a>"
        }
    }

    @Test
    fun `a bare matrix uri keeps its query`() {
        val uri = "matrix:roomid/abc/e/def?via=example.org&amp;via=other.org"
        val exceptions = TranslationExceptions.forReceivedHtml("Schau $uri")
        exceptions.text shouldNotContain "via"
        exceptions.restore(exceptions.text.replace("Schau", "Look")) shouldBeEqualTo "Look $uri"
    }

    @Test
    fun `a plain body url keeps its query`() {
        val url = "https://matrix.to/#/!room:example.org/\$event?via=example.org&via=other.org"
        val exceptions = TranslationExceptions.forReceived("Schau $url")
        exceptions.restore(exceptions.text.replace("Schau", "Look")) shouldBeEqualTo "Look $url"
    }
}
