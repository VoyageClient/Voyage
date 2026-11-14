/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.reply

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.room.model.message.ForwardedInfo

class QuotesForwardAsOriginalTest {

    private fun message(body: String) = mapOf("msgtype" to "m.text", "body" to body)

    private fun forward(roomId: String?, key: String = ForwardedInfo.STABLE_KEY): Content =
            message("hello") + (key to listOfNotNull(
                    "event_id" to "\$original",
                    roomId?.let { "room_id" to it },
                    "sender" to "@alice:example.org",
            ).toMap())

    @Test
    fun `a forward replying to a forward from the same room shows it as the original`() {
        quotesForwardAsOriginal(forward("!source:example.org"), forward("!source:example.org")) shouldBeEqualTo true
    }

    @Test
    fun `prefix variants of the same room match`() {
        quotesForwardAsOriginal(
                forward("!source:example.org"),
                forward("!source:example.org", key = ForwardedInfo.UNSTABLE_KEY),
        ) shouldBeEqualTo true
    }

    @Test
    fun `an ordinary reply to a forward keeps the forward`() {
        quotesForwardAsOriginal(message("reply"), forward("!source:example.org")) shouldBeEqualTo false
    }

    @Test
    fun `a forward replying to a forward from another room keeps the forward`() {
        quotesForwardAsOriginal(forward("!source:example.org"), forward("!elsewhere:example.org")) shouldBeEqualTo false
    }

    @Test
    fun `a forward replying to an ordinary message is not affected`() {
        quotesForwardAsOriginal(forward("!source:example.org"), message("hi")) shouldBeEqualTo false
    }

    @Test
    fun `forwards without an origin room never match`() {
        quotesForwardAsOriginal(forward(null), forward(null)) shouldBeEqualTo false
    }
}
