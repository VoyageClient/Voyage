/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.actions

import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.session.crypto.model.CryptoDeviceInfo
import org.matrix.android.sdk.internal.util.time.Clock

class OlmSessionFailuresTest {

    private var now = 1_000_000L
    private val failures = OlmSessionFailures(object : Clock {
        override fun epochMillis() = now
    })
    private val device = CryptoDeviceInfo(deviceId = "DEVICEA", userId = "@alice:example.org", keys = mapOf("curve25519:DEVICEA" to "identityA"))
    private val other = CryptoDeviceInfo(deviceId = "DEVICEB", userId = "@alice:example.org", keys = mapOf("curve25519:DEVICEB" to "identityB"))

    @Test
    fun `delay doubles from 15 seconds and caps at 15 minutes`() {
        OlmSessionFailures.delayMillis(1) shouldBeEqualTo 15_000L
        OlmSessionFailures.delayMillis(2) shouldBeEqualTo 30_000L
        OlmSessionFailures.delayMillis(6) shouldBeEqualTo 480_000L
        OlmSessionFailures.delayMillis(7) shouldBeEqualTo 900_000L
        OlmSessionFailures.delayMillis(50) shouldBeEqualTo 900_000L
    }

    @Test
    fun `a failed device backs off until its delay has passed`() {
        failures.isBackingOff(device) shouldBe false

        failures.onFailure(device)
        failures.isBackingOff(device) shouldBe true
        failures.isBackingOff(other) shouldBe false

        now += 15_000L
        failures.isBackingOff(device) shouldBe false

        failures.onFailure(device)
        now += 29_999L
        failures.isBackingOff(device) shouldBe true
        now += 1L
        failures.isBackingOff(device) shouldBe false
    }

    @Test
    fun `the no_olm notice is sent once per failure streak`() {
        failures.onFailure(device)
        failures.takeNoOlmNotice(device) shouldBe true
        failures.onFailure(device)
        failures.takeNoOlmNotice(device) shouldBe false

        failures.onSuccess(device)
        failures.isBackingOff(device) shouldBe false
        failures.takeNoOlmNotice(device) shouldBe true
    }
}
