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
import org.matrix.android.sdk.api.session.crypto.model.CryptoDeviceInfo
import org.matrix.android.sdk.api.session.crypto.model.DeviceInfo
import org.matrix.android.sdk.internal.crypto.store.db.mapper.MyDeviceLastSeenInfoEntityMapper
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DeviceSqlStoreTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var store: DeviceSqlStore

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = CryptoSqlDatabase.Schema)
        store = DeviceSqlStore(CryptoSqlDatabase(driver), MyDeviceLastSeenInfoEntityMapper())
    }

    @After
    fun tearDown() {
        driver.close()
    }

    private fun device(userId: String, deviceId: String, identityKey: String = "ik_$deviceId") = CryptoDeviceInfo(
            deviceId = deviceId,
            userId = userId,
            algorithms = listOf("m.olm.v1", "m.megolm.v1"),
            keys = mapOf("curve25519:$deviceId" to identityKey, "ed25519:$deviceId" to "ed_$deviceId"),
            signatures = mapOf(userId to mapOf("ed25519:$deviceId" to "signature")),
    )

    @Test
    fun `store and get user devices round-trips`() {
        store.storeUserDevices("@a:hs", mapOf("D1" to device("@a:hs", "D1"), "D2" to device("@a:hs", "D2")), nowMs = 1000)

        store.getUserDevices("@a:hs")!!.keys shouldBeEqualTo setOf("D1", "D2")
        val d1 = store.getUserDevice("@a:hs", "D1")!!
        d1.deviceId shouldBeEqualTo "D1"
        d1.algorithms shouldBeEqualTo listOf("m.olm.v1", "m.megolm.v1")
        d1.keys!!["curve25519:D1"] shouldBeEqualTo "ik_D1"
        d1.firstTimeSeenLocalTs shouldBeEqualTo 1000L
    }

    @Test
    fun `unknown user returns null`() {
        store.getUserDevices("@unknown:hs") shouldBe null
        store.getUserDeviceList("@unknown:hs") shouldBe null
    }

    @Test
    fun `re-storing a device set removes the deleted devices and preserves timestamps`() {
        store.storeUserDevices("@a:hs", mapOf("D1" to device("@a:hs", "D1"), "D2" to device("@a:hs", "D2")), nowMs = 1000)
        store.storeUserDevices("@a:hs", mapOf("D1" to device("@a:hs", "D1")), nowMs = 2000)

        store.getUserDevices("@a:hs")!!.keys shouldBeEqualTo setOf("D1")
        store.getUserDevice("@a:hs", "D1")!!.firstTimeSeenLocalTs shouldBeEqualTo 1000L
    }

    @Test
    fun `storing null devices removes the user`() {
        store.storeUserDevices("@a:hs", mapOf("D1" to device("@a:hs", "D1")), nowMs = 1000)
        store.storeUserDevices("@a:hs", null, nowMs = 0)

        store.getUserDevices("@a:hs") shouldBe null
    }

    @Test
    fun `device lookup by identity key`() {
        store.storeUserDevices("@a:hs", mapOf("D1" to device("@a:hs", "D1", identityKey = "IK1")), nowMs = 1000)

        store.deviceWithIdentityKey("IK1")!!.deviceId shouldBeEqualTo "D1"
        store.deviceWithIdentityKey("@a:hs", "IK1")!!.deviceId shouldBeEqualTo "D1"
        store.deviceWithIdentityKey("does-not-exist") shouldBe null
    }

    @Test
    fun `setDeviceTrust updates the trust level`() {
        store.storeUserDevices("@a:hs", mapOf("D1" to device("@a:hs", "D1")), nowMs = 1000)
        store.getUserDevice("@a:hs", "D1")!!.trustLevel shouldBe null

        store.setDeviceTrust("@a:hs", "D1", crossSignedVerified = true, locallyVerified = true)

        val trust = store.getUserDevice("@a:hs", "D1")!!.trustLevel!!
        trust.crossSigningVerified shouldBe true
        trust.locallyVerified shouldBe true
    }

    @Test
    fun `device tracking statuses round-trip`() {
        store.saveDeviceTrackingStatuses(mapOf("@a:hs" to 1, "@b:hs" to 2))

        store.getDeviceTrackingStatuses() shouldBeEqualTo mapOf("@a:hs" to 1, "@b:hs" to 2)
        store.getDeviceTrackingStatus("@a:hs", -1) shouldBeEqualTo 1
        store.getDeviceTrackingStatus("@unknown:hs", -1) shouldBeEqualTo -1
    }

    @Test
    fun `my devices info round-trips`() {
        store.saveMyDevicesInfo(listOf(DeviceInfo(deviceId = "D1", displayName = "Phone", lastSeenTs = 100, lastSeenIp = "1.2.3.4")))

        val loaded = store.getMyDevicesInfo()
        loaded.size shouldBeEqualTo 1
        loaded[0].deviceId shouldBeEqualTo "D1"
        loaded[0].displayName shouldBeEqualTo "Phone"

        store.saveMyDevicesInfo(emptyList())
        store.getMyDevicesInfo().size shouldBeEqualTo 0
    }
}
