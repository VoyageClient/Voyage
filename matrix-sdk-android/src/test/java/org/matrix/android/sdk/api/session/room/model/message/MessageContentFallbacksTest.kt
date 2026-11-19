/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.model.message

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldBeNull
import org.junit.Test

class MessageContentFallbacksTest {

    @Test
    fun `a known msgtype parses to its own model`() {
        val content = mapOf("msgtype" to "m.text", "body" to "A sample message").toMessageContentWithFallbacks()

        content.shouldBeInstanceOf<MessageTextContent>()
    }

    @Test
    fun `an unknown msgtype carrying a url reads as a file`() {
        val content = mapOf(
                "msgtype" to "org.example.custom",
                "body" to "sample.bin",
                "url" to "mxc://example.org/sample",
        ).toMessageContentWithFallbacks()

        content.shouldBeInstanceOf<MessageFileContent>()
        content as MessageFileContent
        content.msgType shouldBeEqualTo MessageType.MSGTYPE_FILE
        content.url shouldBeEqualTo "mxc://example.org/sample"
        content.body shouldBeEqualTo "sample.bin"
    }

    @Test
    fun `an unknown msgtype without a file keeps its body`() {
        val content = mapOf("msgtype" to "org.example.custom", "body" to "A sample message").toMessageContentWithFallbacks()

        content shouldBeEqualTo MessageDefaultContent(msgType = "org.example.custom", body = "A sample message")
    }

    @Test
    fun `content without a msgtype shows its body or its file`() {
        mapOf("body" to "A sample message").toMessageContentWithFallbacks() shouldBeEqualTo
                MessageDefaultContent(msgType = "", body = "A sample message")
        mapOf("body" to "sample.bin", "url" to "mxc://example.org/sample").toMessageContentWithFallbacks()
                .shouldBeInstanceOf<MessageFileContent>()
        mapOf("membership" to "join").toMessageContentWithFallbacks().shouldBeNull()
    }
}
