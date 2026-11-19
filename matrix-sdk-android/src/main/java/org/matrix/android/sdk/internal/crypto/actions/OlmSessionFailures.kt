/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.actions

import org.matrix.android.sdk.api.session.crypto.model.CryptoDeviceInfo
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.util.time.Clock
import javax.inject.Inject

/**
 * Devices we could not open an Olm session with (no one-time key, bad signature). Room keys are retried
 * for them on later messages, so the claims are spaced out — 15s doubling up to 15 minutes, as the
 * Rust SDK does — and the m.no_olm notice goes out once per failure streak rather than per message.
 */
@SessionScope
internal class OlmSessionFailures @Inject constructor(private val clock: Clock) {

    private class Entry(val count: Int, val retryAt: Long)

    private val failures = HashMap<String, Entry>()
    private val noOlmNoticeSent = HashSet<String>()

    @Synchronized
    fun isBackingOff(device: CryptoDeviceInfo): Boolean {
        val entry = failures[key(device)] ?: return false
        return clock.epochMillis() < entry.retryAt
    }

    @Synchronized
    fun retryInMillis(device: CryptoDeviceInfo): Long = failures[key(device)]?.let { maxOf(0, it.retryAt - clock.epochMillis()) } ?: 0

    @Synchronized
    fun onFailure(device: CryptoDeviceInfo) {
        val key = key(device)
        val count = (failures[key]?.count ?: 0) + 1
        failures[key] = Entry(count, clock.epochMillis() + delayMillis(count))
    }

    @Synchronized
    fun onSuccess(device: CryptoDeviceInfo) {
        val key = key(device)
        failures.remove(key)
        noOlmNoticeSent.remove(key)
    }

    /** True the first time it is asked for a device since its last success. */
    @Synchronized
    fun takeNoOlmNotice(device: CryptoDeviceInfo): Boolean = noOlmNoticeSent.add(key(device))

    private fun key(device: CryptoDeviceInfo) = "${device.userId}|${device.deviceId}|${device.identityKey()}"

    companion object {
        private const val BASE_DELAY_MS = 15_000L
        private const val MAX_DELAY_MS = 15 * 60_000L

        internal fun delayMillis(failureCount: Int): Long =
                (BASE_DELAY_MS shl (failureCount - 1).coerceIn(0, 10)).coerceAtMost(MAX_DELAY_MS)
    }
}
