/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.translation

import android.text.Spannable
import android.text.SpannableStringBuilder
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.room.send.MatrixEmoteSpan
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OutgoingMessageTranslatorTest {

    private class Emote(override val shortcode: String, override val mxcUrl: String) : MatrixEmoteSpan {
        override val body: String? = null
    }

    private var translatedInput: String? = null

    private val client = mockk<TranslationClient> {
        coEvery { translate(any(), any(), any()) } answers {
            translatedInput = firstArg()
            TranslationResult.Success(firstArg<String>().replace("Hallo, wie geht es dir?", "Hello, how are you?"), "de")
        }
    }

    private val translator = OutgoingMessageTranslator(client)

    private fun withEmote(text: String, shortcode: String): CharSequence {
        val start = text.indexOf(":$shortcode:")
        return SpannableStringBuilder(text).apply {
            setSpan(Emote(shortcode, "mxc://example.org/$shortcode"), start, start + shortcode.length + 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    @Test
    fun `emote spans survive translation as emoticon images`() {
        val outcome = runBlocking { translator.translate(withEmote("Hallo, wie geht es dir? :wave:", "wave"), "en") }
        translatedInput shouldBeEqualTo "Hallo, wie geht es dir? {{0}}"
        outcome shouldBeEqualTo OutgoingMessageTranslator.Outcome.Translated(
                "Hello, how are you? :wave:",
                "Hello, how are you? <img data-mx-emoticon src=\"mxc://example.org/wave\" alt=\":wave:\" title=\":wave:\" height=\"32\" />"
        )
    }

    @Test
    fun `an emote-only message is sent formatted without translating`() {
        translatedInput = null
        val outcome = runBlocking { translator.translate(withEmote(":wave:", "wave"), "en") }
        translatedInput shouldBeEqualTo null
        outcome shouldBeEqualTo OutgoingMessageTranslator.Outcome.Translated(
                ":wave:",
                "<img data-mx-emoticon src=\"mxc://example.org/wave\" alt=\":wave:\" title=\":wave:\" height=\"32\" />"
        )
    }

    @Test
    fun `plain text has no formatted body`() {
        val outcome = runBlocking { translator.translate("Hallo, wie geht es dir?", "en") }
        outcome shouldBeEqualTo OutgoingMessageTranslator.Outcome.Translated("Hello, how are you?", null)
    }
}
