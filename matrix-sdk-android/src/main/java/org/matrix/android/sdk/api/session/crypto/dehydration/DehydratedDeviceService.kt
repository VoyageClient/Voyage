/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.crypto.dehydration

/**
 * MSC3814: a spare Olm identity, stored encrypted on the server, that keeps receiving room keys
 * while this account has no device online. On the next start we rehydrate it and pick up the
 * messages that would otherwise be undecryptable.
 */
interface DehydratedDeviceService {

    /**
     * Decrypt whatever the stored dehydrated device received, importing any room keys it was sent.
     * Does nothing if there is no dehydrated device, or if the dehydration key can't be read.
     */
    suspend fun rehydrateDevice(): RehydrationResult

    /**
     * Replace the stored dehydrated device with a fresh one. The previous device, and everything
     * still queued for it, is dropped by the server, so rehydrate first.
     */
    suspend fun createDehydratedDevice(displayName: String? = null): String

    /**
     * Bring dehydration up to date: rehydrate the stored device, and leave a usable one behind.
     * Returns what the rehydration found.
     */
    suspend fun startDehydration(displayName: String? = null): RehydrationResult

    sealed interface RehydrationResult {
        /** The homeserver doesn't implement MSC3814. */
        object Unsupported : RehydrationResult

        /** No dehydrated device was stored, or it used an algorithm we don't implement. */
        object NothingToRehydrate : RehydrationResult

        /** The dehydration key isn't reachable, so the user has to unlock secret storage first. */
        object KeyUnavailable : RehydrationResult

        data class Rehydrated(val deviceId: String, val eventCount: Int) : RehydrationResult
    }
}
