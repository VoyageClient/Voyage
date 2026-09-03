/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto

import org.matrix.olm.OlmInboundGroupSession

internal enum class MegolmSessionOrdering {
    /** Same ratchet, ours starts earlier. */
    BETTER,

    /** Same ratchet, same first known index. */
    EQUAL,

    /** Same ratchet, the candidate starts earlier. */
    WORSE,

    /** Same session id, but a different ratchet: one of the two is not what it claims to be. */
    UNCONNECTED,
}

internal sealed interface MegolmSessionResolution {
    object Reject : MegolmSessionResolution

    data class KeepExisting(val upgradeTrust: Boolean) : MegolmSessionResolution

    data class UseCandidate(val trusted: Boolean) : MegolmSessionResolution
}

/**
 * Exporting both ratchets at a common index yields identical bytes only if they came from the same
 * outbound session; the export also covers the ed25519 signing key. Null if the comparison failed.
 */
internal fun OlmInboundGroupSession.compareWith(other: OlmInboundGroupSession): MegolmSessionOrdering? {
    return try {
        val ourIndex = firstKnownIndex
        val theirIndex = other.firstKnownIndex
        val lowestCommonIndex = ourIndex.coerceAtLeast(theirIndex)
        if (export(lowestCommonIndex) != other.export(lowestCommonIndex)) {
            MegolmSessionOrdering.UNCONNECTED
        } else {
            when {
                ourIndex < theirIndex -> MegolmSessionOrdering.BETTER
                ourIndex == theirIndex -> MegolmSessionOrdering.EQUAL
                else -> MegolmSessionOrdering.WORSE
            }
        }
    } catch (failure: Throwable) {
        null
    }
}

internal fun resolveMegolmSession(
        ordering: MegolmSessionOrdering,
        existingTrusted: Boolean,
        candidateTrusted: Boolean
): MegolmSessionResolution {
    return when (ordering) {
        MegolmSessionOrdering.UNCONNECTED ->
            if (candidateTrusted) {
                MegolmSessionResolution.UseCandidate(trusted = true)
            } else {
                MegolmSessionResolution.Reject
            }
        MegolmSessionOrdering.BETTER,
        MegolmSessionOrdering.EQUAL ->
            MegolmSessionResolution.KeepExisting(upgradeTrust = candidateTrusted && !existingTrusted)
        MegolmSessionOrdering.WORSE ->
            // Same ratchet from further back, so the trust we already established carries over to it.
            MegolmSessionResolution.UseCandidate(trusted = candidateTrusted || existingTrusted)
    }
}
