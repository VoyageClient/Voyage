/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto

import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

// Vectors: one session (first known index 3) pickled by vodozemac 0.11 and by libce in the libolm format, both keyed
// with "@alice:example.org" the way matrix-dart-sdk does.
private const val USER_ID = "@alice:example.org"
private const val SESSION_ID = "N413wCeBFwo+n/5tnZS0Djh56tJ2Il8qQSqGxxjcUFk"
private const val EXPORTED_KEY = "AQAAAAPawu1P9KVnZUgIo9Ar5k8SNc0BbYS2ha8wX8jGgz888c7ANPdusa/FNhgfyeuj3HFAOxxF1tQCPYHPZYIstOzFkO6w5sdvkSHYLInXmgKP9QJnTN" +
        "Bb3Sx0TVcV9sZ12kLGL847Xc5xV2q4HWBjPRcHAt5kldKBFcadywnb9JwMQTeNd8AngRcKPp/+bZ2UtA44eerSdiJfKkEqhscY3FBZ"
private const val VODOZEMAC_PICKLE = "D5Z9W0O/288B3Nb4dnmNGNC7rZZWyqzkKk1FB1h+qh7YSuH+FVqVMnraQb+k8tjbBT+hQG2tyDkJ+S1mHWO/RSfkeP86+bk6ownizWn6qDQ4VrK" +
        "OgDJvI77kT3hGpYtHCfelkW6iW8sCBfxRFJlgFMqKX4AmeLiStaUu5kMsj/BUcoToESRxpFKUlFQyPrT66EF/+kCKbVcOH8NC2JcuX68xbS1IhAIWgh7xXu8m79aEIx0U" +
        "rK2zCi9KEQ5D+abWe6WLyzKBFGa+YN2fO2y/HX3xyFL/aw36EuNdLoqbmMtRxqfOJMa6v+8SfxPdTgdl49QlO1hjW1KgCcQQ7FtokgbUf8oq20dDXoifyZZaEnTjK8u1kjc" +
        "q/OS0i1MvauzwB+YctcuorWCxyChyNvOjt72cV5Mu/jMd0DFJbZf8g+Pdvuv5m1Mvqxkjl2fIGGP/gm6kKdGYJrIkoNjEiFIS96Jn1cjY1cEahlnh8kF+XCg9IB11oONwlc" +
        "zIBkT8fD4Y7p9r5jQ9B+n6Xw1T2TJqucCqRN4z+sWls4B9dzAdyTjKQzMSJxmHdqGVBliqJoAKsh3Jh0eDbN82nHz5Jw359TiCE70ktbIONKkCsC01lpscsD1OMW/Z7K+e4" +
        "QU/NUjV6D7kKni2iRClcQzpdHkDERSB4d7DwDpqBfpoTTkeDspZX4p8Pey4Zm45WOZb4k/LjoZQ1uE5jEEoyFCFiYKTkkCbkf1e17wvG/cQ3+lEchXG7FzhYIxJAMjU8Pvl" +
        "qoIDrXVfjT+zeqVmG22rmtok7XtNiSVBk/U9s7UhR+CQ3CT1n9ZiHYeD/nTG3H88psivS9CAZ4gtvdjVm9QyGncQU209vNOL2a0bPrNUMuVKh/NaStWhjHjDVKawhwD/7Xh" +
        "d6exPytq8abxr/0Tsw6iSvHYCWm5NuzZl"
private const val LIBOLM_PICKLE = "rB8m0w1PPhcxSaacbm7OxFrC5P0E3xGlXI3mfAvpKG5fQSq1uxOwFohS4zgiFxzQOJkAoguC7r04veEH6EeDvUYAKxCQUtq28zj2OxozjFeOZKEG" +
        "5E0T0UCABDxtKxpJBbvjh/YgcKPipselQlEYFT6yAl/0XQYYD7ewMfEOAzIyNdwYsOcFkqG5+tGeXsPYcLDfFJihJpocSNM8RDLGa6RfRtj02gm0T8lHRw5pi/xSEm+uCEH" +
        "XpbNLiSUakmU61wtyR6mQe0rOafow2EBYgTgY5OwhybWpgHDvcOjsJw5MJvrM0ZvB00au1BsZQUgU014HrQ6um5keaT/vFSpK9+SeB0RW17BlDYuEz8VoPUP/FzCnQ9mliW" +
        "lGXSbh2GJMNp+wJt+Lk+YK6oiGfa8kp2Z6aVDWzPQq"

class FluffyChatBackupTest {

    @Test
    fun `a vodozemac pickle exports at its first known index`() {
        val session = FluffyChatBackup.unpickleInboundGroupSession(VODOZEMAC_PICKLE, USER_ID)
        session.sessionId shouldBeEqualTo SESSION_ID
        session.exportedKey shouldBeEqualTo EXPORTED_KEY
    }

    @Test
    fun `a legacy libolm pickle exports at its first known index`() {
        val session = FluffyChatBackup.unpickleInboundGroupSession(LIBOLM_PICKLE, USER_ID)
        session.sessionId shouldBeEqualTo SESSION_ID
        session.exportedKey shouldBeEqualTo EXPORTED_KEY
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a pickle under another user id is rejected`() {
        FluffyChatBackup.unpickleInboundGroupSession(VODOZEMAC_PICKLE, "@bob:example.org")
    }

    @Test
    fun `only megolm session dumps are recognized`() {
        FluffyChatBackup.isFluffyChatBackup(" \n{\"box_client\":{}}".toByteArray()) shouldBe true
        FluffyChatBackup.isFluffyChatBackup("-----BEGIN MEGOLM SESSION DATA-----\n".toByteArray()) shouldBe false
    }

    @Test
    fun `sessions are read from the dump with the pickle key of the dumped account`() {
        val sessions = FluffyChatBackup.readMegolmSessions(dump(VODOZEMAC_PICKLE).toByteArray(), fallbackUserId = "@other:example.org")

        sessions.size shouldBeEqualTo 1
        with(sessions.single()) {
            algorithm shouldBeEqualTo "m.megolm.v1.aes-sha2"
            sessionId shouldBeEqualTo SESSION_ID
            roomId shouldBeEqualTo "!room:example.org"
            senderKey shouldBeEqualTo "senderCurveKey"
            sessionKey shouldBeEqualTo EXPORTED_KEY
            senderClaimedKeys shouldBeEqualTo mapOf("ed25519" to "senderEdKey")
            senderClaimedEd25519Key shouldBeEqualTo "senderEdKey"
            forwardingCurve25519KeyChain shouldBeEqualTo listOf("forwarderKey")
            sharedHistory shouldBe true
        }
    }

    @Test
    fun `sessions whose pickle does not match their id are skipped`() {
        FluffyChatBackup.readMegolmSessions(dump(LIBOLM_PICKLE, sessionId = "someOtherSession").toByteArray(), USER_ID).shouldBeEmpty()
    }

    private fun dump(pickle: String, sessionId: String = SESSION_ID): String {
        val content = """{"algorithm":"m.megolm.v1.aes-sha2","room_id":"!room:example.org","session_id":"$sessionId",""" +
                """"forwarding_curve25519_key_chain":["forwarderKey"],"org.matrix.msc3061.shared_history":true}"""
        // Client.exportDump() shape, with box_client last so box order is exercised.
        return """
            {
              "box_events": {"!room:example.org|${'$'}event": {"type": "m.room.message", "content": {"body": [1, 2]}}},
              "box_inbound_group_session": {
                "$sessionId": {
                  "room_id": "!room:example.org",
                  "session_id": "$sessionId",
                  "pickle": "$pickle",
                  "content": ${quote(content)},
                  "indexes": "{}",
                  "allowed_at_index": "{}",
                  "sender_key": "senderCurveKey",
                  "sender_claimed_keys": ${quote("""{"ed25519":"senderEdKey"}""")}
                }
              },
              "box_client": {"user_id": "$USER_ID", "token": "secret", "device_id": "DEVICE"}
            }
        """.trimIndent()
    }

    private fun quote(json: String) = "\"" + json.replace("\"", "\\\"") + "\""
}
