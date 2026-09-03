/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class MegolmSessionMergeTest {

    @Test
    fun `an untrusted key claiming a known session id with another ratchet is dropped`() {
        resolveMegolmSession(MegolmSessionOrdering.UNCONNECTED, existingTrusted = false, candidateTrusted = false) shouldBeEqualTo
                MegolmSessionResolution.Reject
        resolveMegolmSession(MegolmSessionOrdering.UNCONNECTED, existingTrusted = true, candidateTrusted = false) shouldBeEqualTo
                MegolmSessionResolution.Reject
    }

    @Test
    fun `a trusted unconnected key replaces what we have and does not inherit its trust`() {
        resolveMegolmSession(MegolmSessionOrdering.UNCONNECTED, existingTrusted = false, candidateTrusted = true) shouldBeEqualTo
                MegolmSessionResolution.UseCandidate(trusted = true)
    }

    @Test
    fun `a candidate at a higher index is dropped but can still upgrade our trust`() {
        resolveMegolmSession(MegolmSessionOrdering.BETTER, existingTrusted = false, candidateTrusted = true) shouldBeEqualTo
                MegolmSessionResolution.KeepExisting(upgradeTrust = true)
        resolveMegolmSession(MegolmSessionOrdering.BETTER, existingTrusted = true, candidateTrusted = true) shouldBeEqualTo
                MegolmSessionResolution.KeepExisting(upgradeTrust = false)
        resolveMegolmSession(MegolmSessionOrdering.BETTER, existingTrusted = false, candidateTrusted = false) shouldBeEqualTo
                MegolmSessionResolution.KeepExisting(upgradeTrust = false)
    }

    @Test
    fun `a candidate at the same index is dropped but can still upgrade our trust`() {
        resolveMegolmSession(MegolmSessionOrdering.EQUAL, existingTrusted = false, candidateTrusted = true) shouldBeEqualTo
                MegolmSessionResolution.KeepExisting(upgradeTrust = true)
        resolveMegolmSession(MegolmSessionOrdering.EQUAL, existingTrusted = true, candidateTrusted = false) shouldBeEqualTo
                MegolmSessionResolution.KeepExisting(upgradeTrust = false)
    }

    @Test
    fun `an untrusted candidate from further back keeps the trust we already established`() {
        resolveMegolmSession(MegolmSessionOrdering.WORSE, existingTrusted = true, candidateTrusted = false) shouldBeEqualTo
                MegolmSessionResolution.UseCandidate(trusted = true)
    }

    @Test
    fun `a candidate from further back is taken as is when nothing was trusted`() {
        resolveMegolmSession(MegolmSessionOrdering.WORSE, existingTrusted = false, candidateTrusted = false) shouldBeEqualTo
                MegolmSessionResolution.UseCandidate(trusted = false)
        resolveMegolmSession(MegolmSessionOrdering.WORSE, existingTrusted = false, candidateTrusted = true) shouldBeEqualTo
                MegolmSessionResolution.UseCandidate(trusted = true)
    }
}
