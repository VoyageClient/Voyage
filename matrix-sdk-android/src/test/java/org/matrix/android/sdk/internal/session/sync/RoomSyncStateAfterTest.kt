/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync

import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.matrix.android.sdk.api.session.sync.model.RoomSync
import org.matrix.android.sdk.internal.di.MoshiProvider

/**
 * MSC4222: a server answering `state_after` sends it *instead of* `state`.
 */
class RoomSyncStateAfterTest {

    private val adapter = MoshiProvider.providesMoshi().adapter(RoomSync::class.java)

    private fun roomSyncOf(stateKeyName: String) = adapter.fromJson(
            """
            {
                "$stateKeyName": {
                    "events": [
                        {
                            "type": "m.room.encryption",
                            "state_key": "",
                            "event_id": "${'$'}state1",
                            "sender": "@alice:example.org",
                            "content": { "algorithm": "m.megolm.v1.aes-sha2" }
                        }
                    ]
                },
                "timeline": { "events": [], "limited": false }
            }
            """.trimIndent()
    )!!

    @Test
    fun `stable state_after is picked up`() {
        val roomSync = roomSyncOf("state_after")

        roomSync.state.shouldBeNull()
        roomSync.stateAfter?.events?.size shouldBeEqualTo 1
        roomSync.stateAfter?.events?.first()?.type shouldBeEqualTo "m.room.encryption"
    }

    @Test
    fun `unstable state_after is picked up`() {
        val roomSync = roomSyncOf("org.matrix.msc4222.state_after")

        roomSync.state.shouldBeNull()
        roomSync.stateAfter?.events?.size shouldBeEqualTo 1
    }

    @Test
    fun `stable name wins when a server sends both spellings`() {
        val roomSync = adapter.fromJson(
                """
                {
                    "state_after": { "events": [] },
                    "org.matrix.msc4222.state_after": {
                        "events": [
                            { "type": "m.room.topic", "state_key": "", "event_id": "${'$'}s", "sender": "@a:b", "content": {} }
                        ]
                    }
                }
                """.trimIndent()
        )!!

        roomSync.stateAfter?.events?.size shouldBeEqualTo 0
    }

    @Test
    fun `a legacy state-only response leaves state_after unset`() {
        val roomSync = roomSyncOf("state")

        roomSync.stateAfter.shouldBeNull()
        roomSync.state?.events?.size shouldBeEqualTo 1
    }

    @Test
    fun `state and state_after are never both meaningful`() {
        val roomSync = roomSyncOf("state_after")

        (roomSync.state != null && roomSync.stateAfter != null) shouldBe false
    }
}
