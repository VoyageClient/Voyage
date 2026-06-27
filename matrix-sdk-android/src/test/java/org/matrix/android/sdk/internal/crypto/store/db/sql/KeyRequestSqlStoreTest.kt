/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.store.db.sql

import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.crypto.OutgoingRoomKeyRequestState
import org.matrix.android.sdk.api.session.crypto.model.RoomKeyRequestBody
import org.matrix.android.sdk.api.session.events.model.content.WithHeldCode
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.matrix.android.sdk.internal.util.time.Clock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class KeyRequestSqlStoreTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var store: KeyRequestSqlStore

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = CryptoSqlDatabase.Schema)
        store = KeyRequestSqlStore(CryptoSqlDatabase(driver), object : Clock {
            override fun epochMillis() = 1234L
        })
    }

    @After
    fun tearDown() {
        driver.close()
    }

    private fun body(roomId: String = "!r:hs", sessionId: String = "sess1", senderKey: String = "sk1", algorithm: String = "m.megolm.v1.aes-sha2") =
            RoomKeyRequestBody(algorithm = algorithm, roomId = roomId, senderKey = senderKey, sessionId = sessionId)

    @Test
    fun `getOrAdd creates a request and dedups on the same body`() {
        val r1 = store.getOrAddOutgoingRoomKeyRequest(body(), mapOf("@b:hs" to listOf("DEV")), 0)
        r1.requestBody!!.sessionId shouldBeEqualTo "sess1"
        r1.state shouldBeEqualTo OutgoingRoomKeyRequestState.UNSENT

        val r2 = store.getOrAddOutgoingRoomKeyRequest(body(), mapOf("@b:hs" to listOf("DEV")), 0)
        r2.requestId shouldBeEqualTo r1.requestId
        store.getOutgoingRoomKeyRequests().size shouldBeEqualTo 1
    }

    @Test
    fun `lookups by id, body and room or session`() {
        val r1 = store.getOrAddOutgoingRoomKeyRequest(body(), emptyMap(), 0)

        store.getOutgoingRoomKeyRequest(r1.requestId)!!.requestId shouldBeEqualTo r1.requestId
        store.getOutgoingRoomKeyRequest(body())!!.requestId shouldBeEqualTo r1.requestId
        store.getOutgoingRoomKeyRequest("!r:hs", "sess1", "m.megolm.v1.aes-sha2", "sk1").size shouldBeEqualTo 1
    }

    @Test
    fun `update state and required index`() {
        val r1 = store.getOrAddOutgoingRoomKeyRequest(body(), emptyMap(), 0)

        store.updateOutgoingRoomKeyRequestState(r1.requestId, OutgoingRoomKeyRequestState.SENT)
        store.getOutgoingRoomKeyRequest(r1.requestId)!!.state shouldBeEqualTo OutgoingRoomKeyRequestState.SENT
        store.getOutgoingRoomKeyRequests(setOf(OutgoingRoomKeyRequestState.SENT)).size shouldBeEqualTo 1
        store.getOutgoingRoomKeyRequests(setOf(OutgoingRoomKeyRequestState.UNSENT)).size shouldBeEqualTo 0

        store.updateOutgoingRoomKeyRequiredIndex(r1.requestId, 7)
        store.getOutgoingRoomKeyRequest(r1.requestId)!!.fromIndex shouldBeEqualTo 7
    }

    @Test
    fun `delete and delete-in-state`() {
        val r1 = store.getOrAddOutgoingRoomKeyRequest(body(), emptyMap(), 0)
        store.deleteOutgoingRoomKeyRequest(r1.requestId)
        store.getOutgoingRoomKeyRequest(r1.requestId) shouldBe null

        store.getOrAddOutgoingRoomKeyRequest(body(sessionId = "sess2"), emptyMap(), 0)
        store.deleteOutgoingRoomKeyRequestInState(OutgoingRoomKeyRequestState.UNSENT)
        store.getOutgoingRoomKeyRequests().size shouldBeEqualTo 0
    }

    @Test
    fun `audit trail save and read`() {
        store.saveIncomingKeyRequestAuditTrail("req1", "!r:hs", "sess1", "sk1", "alg", "@b:hs", "DEV")
        store.saveWithheldAuditTrail("!r:hs", "sess1", "sk1", "alg", WithHeldCode.UNAUTHORISED, "@b:hs", "DEV")
        store.saveForwardKeyAuditTrail("!r:hs", "sess1", "sk1", "alg", "@b:hs", "DEV", 5, incoming = false)

        store.getGossipingEvents().size shouldBeEqualTo 3
    }
}
