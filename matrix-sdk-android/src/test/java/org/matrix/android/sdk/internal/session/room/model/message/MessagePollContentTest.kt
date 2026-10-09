/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.model.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.android.sdk.api.session.room.model.message.MessageEndPollContent
import org.matrix.android.sdk.api.session.room.model.message.MessagePollContent
import org.matrix.android.sdk.api.session.room.model.message.MessagePollResponseContent
import org.matrix.android.sdk.internal.di.MoshiProvider

class MessagePollContentTest {

    private val moshi = MoshiProvider.providesMoshi()

    @Test
    fun `reads stable poll schema`() {
        val content = moshi.adapter(MessagePollContent::class.java).fromJson("""
            {
              "m.text": [{"body": "Lunch?"}],
              "m.poll": {
                "kind": "m.disclosed",
                "question": {"m.text": [{"body": "Lunch?"}]},
                "answers": [{"m.id": "pizza", "m.text": [{"body": "Pizza"}]}]
              }
            }
        """.trimIndent())!!
        val poll = content.getBestPollCreationInfo()!!
        assertEquals("Lunch?", poll.question?.getBestQuestion())
        assertEquals("pizza", poll.answers?.single()?.getBestId())
        assertEquals("Pizza", poll.answers?.single()?.getBestAnswer())
        assertTrue(!poll.isUndisclosed())
    }

    @Test
    fun `reads unstable poll schema`() {
        val content = moshi.adapter(MessagePollContent::class.java).fromJson("""
            {
              "org.matrix.msc3381.poll.start": {
                "kind": "org.matrix.msc3381.poll.disclosed",
                "question": {"org.matrix.msc1767.text": "Lunch?"},
                "answers": [{"id": "pizza", "org.matrix.msc1767.text": "Pizza"}]
              }
            }
        """.trimIndent())!!
        val poll = content.getBestPollCreationInfo()!!
        assertEquals("Lunch?", poll.question?.getBestQuestion())
        assertEquals("pizza", poll.answers?.single()?.getBestId())
        assertEquals("Pizza", poll.answers?.single()?.getBestAnswer())
        assertTrue(!poll.isUndisclosed())
    }

    @Test
    fun `unknown or missing kind is undisclosed`() {
        for (kind in listOf("", "\"kind\": \"custom.kind\",")) {
            val content = moshi.adapter(MessagePollContent::class.java).fromJson("""{"m.poll": {$kind "max_selections": 1}}""")!!
            assertTrue(content.getBestPollCreationInfo()!!.isUndisclosed())
        }
    }

    @Test
    fun `reads stable and unstable votes including an empty selection`() {
        val adapter = moshi.adapter(MessagePollResponseContent::class.java)
        val stable = adapter.fromJson("""{"m.selections": ["pizza"]}""")!!
        val unstable = adapter.fromJson("""{"org.matrix.msc3381.poll.response": {"answers": ["pizza"]}}""")!!
        assertEquals(listOf("pizza"), stable.getBestResponse()?.answers)
        assertEquals(stable.getBestResponse(), unstable.getBestResponse())
        assertEquals(emptyList<String>(), adapter.fromJson("""{"m.selections": []}""")?.getBestResponse()?.answers)
    }

    @Test
    fun `reads stable and unstable poll end fallback`() {
        val adapter = moshi.adapter(MessageEndPollContent::class.java)
        assertEquals("Ended", adapter.fromJson("""{"m.text": [{"body": "Ended"}]}""")?.getBestText())
        assertEquals("Ended", adapter.fromJson("""{"org.matrix.msc1767.text": "Ended"}""")?.getBestText())
    }
}
