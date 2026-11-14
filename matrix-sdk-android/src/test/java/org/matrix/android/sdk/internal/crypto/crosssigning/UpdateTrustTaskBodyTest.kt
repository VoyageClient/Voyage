/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.crosssigning

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.session.crypto.crosssigning.CrossSigningService
import org.matrix.android.sdk.api.session.crypto.crosssigning.CryptoCrossSigningKey
import org.matrix.android.sdk.api.session.crypto.crosssigning.DeviceTrustLevel
import org.matrix.android.sdk.api.session.crypto.crosssigning.DeviceTrustResult
import org.matrix.android.sdk.api.session.crypto.crosssigning.KeyUsage
import org.matrix.android.sdk.api.session.crypto.crosssigning.MXCrossSigningInfo
import org.matrix.android.sdk.api.session.crypto.crosssigning.UserTrustResult
import org.matrix.android.sdk.api.session.crypto.model.CryptoDeviceInfo
import org.matrix.android.sdk.api.session.crypto.model.trustRelevant
import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStore

class UpdateTrustTaskBodyTest {

    private val me = "@me:example.org"
    private val alice = "@alice:example.org"

    private fun crossSigningInfo(userId: String, trusted: Boolean) = MXCrossSigningInfo(
            userId,
            listOf(KeyUsage.MASTER, KeyUsage.SELF_SIGNING).map { usage ->
                CryptoCrossSigningKey(
                        userId = userId,
                        usages = listOf(usage.value),
                        keys = mapOf("ed25519:${usage.value}" to usage.value),
                        signatures = null,
                        trustLevel = DeviceTrustLevel(trusted, trusted),
                )
            },
            wasTrustedOnce = trusted,
    )

    @Test
    fun `devices are judged against the user trust written in the same run`() {
        val device = CryptoDeviceInfo("ALICEPHONE", alice, trustLevel = DeviceTrustLevel(false, false))
        var aliceTrusted = false
        val cryptoStore = mockk<IMXCryptoStore>(relaxed = true) {
            every { getMyCrossSigningInfo() } returns crossSigningInfo(me, true)
            every { getCrossSigningInfo(alice) } answers { crossSigningInfo(alice, aliceTrusted) }
            every { setUserKeysAsTrusted(alice, any()) } answers { aliceTrusted = secondArg() }
            every { getUserDeviceList(alice) } returns listOf(device)
        }
        val crossSigningService = mockk<CrossSigningService> {
            every { checkOtherMSKTrusted(any(), any()) } returns UserTrustResult.Success
            every { checkDeviceTrust(any<MXCrossSigningInfo>(), any<MXCrossSigningInfo>(), any<CryptoDeviceInfo>()) } answers {
                val otherKeys = secondArg<MXCrossSigningInfo?>()!!
                if (otherKeys.isTrusted()) DeviceTrustResult.Success(DeviceTrustLevel(true, null)) else DeviceTrustResult.KeysNotTrusted(otherKeys)
            }
        }
        val body = UpdateTrustTaskBody(
                crossSigningService = crossSigningService,
                cryptoStore = cryptoStore,
                database = mockk(),
                sessionDbDispatcher = mockk(),
                stores = mockk(),
                myUserId = me,
                updateTrustWorkerDataRepository = mockk(),
                cryptoSessionInfoProvider = mockk(),
        )

        body.updateTrust(listOf(alice))

        verify { cryptoStore.setDeviceTrust(alice, "ALICEPHONE", true, null) }
    }

    @Test
    fun `dehydrated devices do not count towards a shield`() {
        val phone = CryptoDeviceInfo("PHONE", alice)
        val offline = CryptoDeviceInfo("OFFLINE", alice, dehydrated = true)
        val legacy = CryptoDeviceInfo("LEGACY", alice, dehydrated = false)

        listOf(phone, offline, legacy).trustRelevant() shouldBeEqualTo listOf(phone, legacy)
    }
}
