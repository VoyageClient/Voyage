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
import org.matrix.android.sdk.api.crypto.MXCRYPTO_ALGORITHM_MEGOLM
import org.matrix.android.sdk.api.session.events.model.content.EncryptionEventContent
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CryptoRoomSqlStoreTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var store: CryptoRoomSqlStore

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = CryptoSqlDatabase.Schema)
        store = CryptoRoomSqlStore(CryptoSqlDatabase(driver))
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `store room algorithm marks it encrypted once`() {
        store.getRoomAlgorithm("!r:hs") shouldBe null

        store.storeRoomAlgorithm("!r:hs", MXCRYPTO_ALGORITHM_MEGOLM)

        store.getRoomAlgorithm("!r:hs") shouldBeEqualTo MXCRYPTO_ALGORITHM_MEGOLM
        store.roomWasOnceEncrypted("!r:hs") shouldBe true
    }

    @Test
    fun `setAlgorithmInfo stores rotation and crypto info`() {
        store.setAlgorithmInfo("!r2:hs", EncryptionEventContent(algorithm = MXCRYPTO_ALGORITHM_MEGOLM, rotationPeriodMs = 100L, rotationPeriodMsgs = 50L))

        val info = store.getRoomCryptoInfo("!r2:hs")!!
        info.algorithm shouldBeEqualTo MXCRYPTO_ALGORITHM_MEGOLM
        info.rotationPeriodMs shouldBeEqualTo 100L
        info.rotationPeriodMsgs shouldBeEqualTo 50L
        info.wasEncryptedOnce shouldBe true
    }

    @Test
    fun `room flags round-trip`() {
        store.setShouldEncryptForInvitedMembers("!r:hs", true)
        store.setShouldShareHistory("!r:hs", true)
        store.blockUnverifiedDevicesInRoom("!r:hs", true)

        store.shouldEncryptForInvitedMembers("!r:hs") shouldBe true
        store.getRoomShouldShareHistory("!r:hs") shouldBe true
        store.getBlockUnverifiedDevices("!r:hs") shouldBe true
        store.getRoomsListBlacklistUnverifiedDevices() shouldBeEqualTo listOf("!r:hs")
    }

    @Test
    fun `withheld session add, get and update`() {
        store.addWithHeld("!r:hs", "sess1", "sk1", "m.unauthorised", "reason")
        store.getWithHeld("!r:hs", "sess1")!!.let {
            it.code_string shouldBeEqualTo "m.unauthorised"
            it.sender_key shouldBeEqualTo "sk1"
        }

        store.addWithHeld("!r:hs", "sess1", "sk2", "m.unavailable", "reason2")
        store.getWithHeld("!r:hs", "sess1")!!.code_string shouldBeEqualTo "m.unavailable"
    }

    @Test
    fun `shared session mark and lookup`() {
        store.markedSessionAsShared("!r:hs", "sess1", "@b:hs", "DEV", "ik1", 5)

        store.getSharedSession("!r:hs", "sess1", "@b:hs", "DEV", "ik1")!!.chain_index shouldBeEqualTo 5L
        store.getSharedSession("!r:hs", "sess1", "@b:hs", "DEV", "other") shouldBe null
        store.getSharedSessions("!r:hs", "sess1").size shouldBeEqualTo 1
    }

    @Test
    fun `outbound session store, get and clear`() {
        store.storeRoomAlgorithm("!r:hs", MXCRYPTO_ALGORITHM_MEGOLM)
        store.getOutboundInfo("!r:hs") shouldBe null

        store.storeOutbound("!r:hs", "outbound-blob", 999L, true)
        store.getOutboundInfo("!r:hs")!!.let {
            it.serialized shouldBeEqualTo "outbound-blob"
            it.creationTime shouldBeEqualTo 999L
            it.shouldShareHistory shouldBe true
        }

        store.clearOutbound("!r:hs")
        store.getOutboundInfo("!r:hs") shouldBe null
    }
}
