/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync.sliding

import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldContain
import org.junit.Test
import org.matrix.android.sdk.api.session.sync.model.LazyRoomSyncEphemeral
import org.matrix.android.sdk.internal.di.MoshiProvider

/**
 * MSC4186 / MSC4525 room results have to come out the other side shaped like sync v2, since that is
 * what the whole handler stack consumes.
 */
class SlidingSyncTranslatorTest {

    private val adapter = MoshiProvider.providesMoshi().adapter(SlidingSyncResponse::class.java)
    private val translator = SlidingSyncTranslator(userId = "@me:example.org")

    private fun translate(json: String) = translator.toSyncResponse(adapter.fromJson(json.trimIndent())!!)

    @Test
    fun `each transport's request omits the other's fields`() {
        val requestAdapter = MoshiProvider.providesMoshi().adapter(SlidingSyncRequest::class.java)

        val paginated = requestAdapter.toJson(
                SlidingSyncRequest(
                        requiredState = listOf(listOf("m.room.name", "")),
                        pageSize = 20,
                        limit = 20,
                        history = 20,
                )
        )
        paginated shouldBeEqualTo
                """{"required_state":[["m.room.name",""]],"page_size":20,"limit":20,"history":20}"""

        val simplified = requestAdapter.toJson(
                SlidingSyncRequest(
                        lists = mapOf(
                                "all" to SlidingSyncListRequest(
                                        ranges = listOf(listOf(0, 19)),
                                        requiredState = listOf(listOf("m.room.member", "\$LAZY")),
                                        timelineLimit = 20,
                                )
                        )
                )
        )
        simplified shouldBeEqualTo
                """{"lists":{"all":{"ranges":[[0,19]],"required_state":[["m.room.member","${'$'}LAZY"]],"timeline_limit":20}}}"""
    }

    @Test
    fun `unread counts are read whether flattened or nested`() {
        val flattened = translate(
                """{ "pos": "s1", "rooms": { "!r:example.org": { "notification_count": 3, "highlight_count": 1 } } }"""
        )
        flattened.rooms?.join?.get("!r:example.org")?.unreadNotifications?.notificationCount shouldBeEqualTo 3

        val nested = translate(
                """{ "pos": "s1", "rooms": { "!r:example.org": { "unread_notifications": { "notification_count": 3 } } } }"""
        )
        nested.rooms?.join?.get("!r:example.org")?.unreadNotifications?.notificationCount shouldBeEqualTo 3
    }

    @Test
    fun `joined room keeps its timeline and lands required_state in state_after`() {
        val response = translate(
                """
                {
                  "pos": "s1",
                  "rooms": {
                    "!room:example.org": {
                      "required_state": [
                        {
                          "type": "m.room.encryption", "state_key": "", "event_id": "${'$'}s1",
                          "sender": "@alice:example.org", "content": { "algorithm": "m.megolm.v1.aes-sha2" }
                        }
                      ],
                      "timeline": [
                        {
                          "type": "m.room.message", "event_id": "${'$'}e1",
                          "sender": "@alice:example.org", "content": { "body": "hi" }
                        }
                      ],
                      "prev_batch": "p1",
                      "limited": true,
                      "joined_count": 4,
                      "notification_count": 2,
                      "heroes": [ { "user_id": "@bob:example.org" } ]
                    }
                  }
                }
                """
        )

        response.nextBatch shouldBeEqualTo "s1"
        val room = response.rooms?.join?.get("!room:example.org")!!
        // required_state is the state at the end of the timeline, which is what state_after means.
        room.state.shouldBeNull()
        room.stateAfter?.events?.first()?.type shouldBeEqualTo "m.room.encryption"
        room.timeline?.events?.size shouldBeEqualTo 1
        room.timeline?.limited shouldBe true
        room.timeline?.prevToken shouldBeEqualTo "p1"
        room.summary?.joinedMembersCount shouldBeEqualTo 4
        room.summary?.heroes?.shouldContain("@bob:example.org")
        room.unreadNotifications?.notificationCount shouldBeEqualTo 2
    }

    @Test
    fun `absent unread counts stay absent rather than becoming zero`() {
        val response = translate("""{ "pos": "s1", "rooms": { "!room:example.org": { "timeline": [] } } }""")

        response.rooms?.join?.get("!room:example.org")?.unreadNotifications.shouldBeNull()
    }

    @Test
    fun `a room's first delivery is flagged so it does not raise notifications`() {
        val response = translate(
                """
                {
                  "pos": "s1",
                  "rooms": {
                    "!fresh:example.org": { "initial": true, "timeline": [] },
                    "!known:example.org": { "timeline": [] }
                  }
                }
                """
        )

        response.rooms?.join?.get("!fresh:example.org")?.isInitialDelivery shouldBe true
        response.rooms?.join?.get("!known:example.org")?.isInitialDelivery shouldBe false
    }

    @Test
    fun `state stubs survive translation so removed state can be applied`() {
        val response = translate(
                """
                {
                  "pos": "s1",
                  "rooms": {
                    "!room:example.org": {
                      "required_state": [ { "type": "m.room.topic", "state_key": "" } ]
                    }
                  }
                }
                """
        )

        val stub = response.rooms?.join?.get("!room:example.org")?.stateAfter?.events?.first()!!
        stub.type shouldBeEqualTo "m.room.topic"
        stub.eventId.shouldBeNull()
    }

    @Test
    fun `invited room is routed by membership and carries stripped state`() {
        val response = translate(
                """
                {
                  "pos": "s1",
                  "rooms": {
                    "!room:example.org": {
                      "membership": "invite",
                      "invite_state": [
                        {
                          "type": "m.room.name", "state_key": "",
                          "sender": "@alice:example.org", "content": { "name": "Invite" }
                        }
                      ]
                    }
                  }
                }
                """
        )

        response.rooms?.join?.size shouldBeEqualTo 0
        response.rooms?.invite?.get("!room:example.org")?.inviteState?.events?.size shouldBeEqualTo 1
    }

    @Test
    fun `own member event decides membership when the server sends no membership field`() {
        // Synapse omits the MSC's optional membership field, so a kicked room would otherwise read as
        // joined and be marked as participating again.
        val response = translate(
                """
                {
                  "pos": "s1",
                  "rooms": {
                    "!kicked:example.org": {
                      "required_state": [
                        {
                          "type": "m.room.member", "state_key": "@me:example.org", "event_id": "${'$'}m1",
                          "sender": "@mod:example.org", "content": { "membership": "leave" }
                        }
                      ]
                    },
                    "!banned:example.org": {
                      "required_state": [
                        {
                          "type": "m.room.member", "state_key": "@me:example.org", "event_id": "${'$'}m2",
                          "sender": "@mod:example.org", "content": { "membership": "ban" }
                        }
                      ]
                    },
                    "!joined:example.org": {
                      "required_state": [
                        {
                          "type": "m.room.member", "state_key": "@me:example.org", "event_id": "${'$'}m3",
                          "sender": "@me:example.org", "content": { "membership": "join" }
                        }
                      ]
                    }
                  }
                }
                """
        )

        response.rooms?.leave?.keys?.shouldContain("!kicked:example.org")
        response.rooms?.leave?.keys?.shouldContain("!banned:example.org")
        response.rooms?.join?.keys?.shouldContain("!joined:example.org")
        response.rooms?.join?.size shouldBeEqualTo 1
    }

    @Test
    fun `another user's member event does not decide our membership`() {
        val response = translate(
                """
                {
                  "pos": "s1",
                  "rooms": {
                    "!room:example.org": {
                      "required_state": [
                        {
                          "type": "m.room.member", "state_key": "@someone:example.org", "event_id": "${'$'}m1",
                          "sender": "@someone:example.org", "content": { "membership": "leave" }
                        }
                      ]
                    }
                  }
                }
                """
        )

        response.rooms?.join?.keys?.shouldContain("!room:example.org")
    }

    @Test
    fun `left and knocked rooms are routed away from the join bucket`() {
        val response = translate(
                """
                {
                  "pos": "s1",
                  "rooms": {
                    "!left:example.org": { "membership": "leave", "timeline": [] },
                    "!knock:example.org": { "membership": "knock", "invite_state": [] }
                  }
                }
                """
        )

        response.rooms?.join?.size shouldBeEqualTo 0
        response.rooms?.leave?.keys?.shouldContain("!left:example.org")
        response.rooms?.knock?.keys?.shouldContain("!knock:example.org")
    }

    @Test
    fun `extensions are hoisted to the places sync v2 keeps them`() {
        val response = translate(
                """
                {
                  "pos": "s1",
                  "rooms": { "!room:example.org": { "timeline": [] } },
                  "extensions": {
                    "to_device": {
                      "next_batch": "td1",
                      "events": [ { "type": "m.room_key", "sender": "@alice:example.org", "content": {} } ]
                    },
                    "e2ee": {
                      "device_lists": { "changed": [ "@bob:example.org" ], "left": [] },
                      "device_one_time_keys_count": { "signed_curve25519": 42 },
                      "device_unused_fallback_key_types": [ "signed_curve25519" ]
                    },
                    "account_data": {
                      "global": [ { "type": "m.direct", "content": {} } ],
                      "rooms": {
                        "!room:example.org": [ { "type": "m.tag", "sender": "@alice:example.org", "content": {} } ]
                      }
                    },
                    "receipts": {
                      "rooms": {
                        "!room:example.org": { "type": "m.receipt", "sender": "@alice:example.org", "content": {} }
                      }
                    },
                    "typing": {
                      "rooms": {
                        "!room:example.org": { "type": "m.typing", "sender": "@alice:example.org", "content": {} }
                      }
                    }
                  }
                }
                """
        )

        response.toDevice?.events?.size shouldBeEqualTo 1
        response.deviceLists?.changed?.shouldContain("@bob:example.org")
        response.deviceOneTimeKeysCount?.signedCurve25519 shouldBeEqualTo 42
        response.deviceUnusedFallbackKeyTypes?.shouldContain("signed_curve25519")
        response.accountData?.list?.size shouldBeEqualTo 1

        val room = response.rooms?.join?.get("!room:example.org")!!
        room.accountData?.events?.size shouldBeEqualTo 1
        // Receipts and typing both arrive as one EDU per room and share the v2 ephemeral slot.
        val ephemeral = room.ephemeral as LazyRoomSyncEphemeral.Parsed
        ephemeral.roomSyncEphemeral.events?.map { it.type } shouldBeEqualTo listOf("m.receipt", "m.typing")
    }

    @Test
    fun `MSC4262 profile updates become MSC4429 profile updates`() {
        val response = translate(
                """
                {
                  "pos": "1",
                  "extensions": {
                    "org.matrix.msc4262.profiles": {
                      "users": {
                        "@alice:example.org": {
                          "updated": { "m.status": { "text": "swimming" } },
                          "removed": ["m.tz"]
                        },
                        "@bob:example.org": null
                      }
                    }
                  }
                }
                """
        )

        val alice = response.profileUpdates?.get("@alice:example.org")?.profileUpdates!!
        alice["m.status"] shouldBeEqualTo mapOf("text" to "swimming")
        // A removal has to arrive as an explicit null, which is how MSC4429 spells "field cleared".
        alice.containsKey("m.tz") shouldBe true
        alice["m.tz"].shouldBeNull()

        // A null user means stop tracking them, which survives as a null profileUpdates map.
        val bob = response.profileUpdates?.get("@bob:example.org")!!
        bob.profileUpdates.shouldBeNull()
    }

    @Test
    fun `the stable profiles extension wins over the unstable one`() {
        val response = translate(
                """
                {
                  "pos": "1",
                  "extensions": {
                    "org.matrix.msc4262.profiles": { "users": { "@unstable:example.org": { "updated": {} } } },
                    "profiles": { "users": { "@stable:example.org": { "updated": {} } } }
                  }
                }
                """
        )

        response.profileUpdates?.keys shouldBeEqualTo setOf("@stable:example.org")
    }
}
