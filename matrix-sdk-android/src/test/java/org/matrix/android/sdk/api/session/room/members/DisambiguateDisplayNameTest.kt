/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.members

import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

private const val ALICE = "@alice:example.org"

class DisambiguateDisplayNameTest {

    private var ambiguous: Boolean? = false

    private val membershipService: MembershipService = mockk {
        every { isDisplayNameAmbiguous(any()) } answers { ambiguous }
        every { disambiguateDisplayName(any(), any()) } answers { callOriginal() }
    }

    @Test
    fun `a unique name is shown as is`() {
        membershipService.disambiguateDisplayName(ALICE, "Alice") shouldBeEqualTo "Alice"
    }

    @Test
    fun `a name another member shares gets the user id`() {
        ambiguous = true

        membershipService.disambiguateDisplayName(ALICE, "Alice") shouldBeEqualTo "Alice ($ALICE)"
    }

    @Test
    fun `unknown members keep the bare name`() {
        ambiguous = null

        membershipService.disambiguateDisplayName(ALICE, "Alice") shouldBeEqualTo "Alice"
    }

    @Test
    fun `no name falls back to the user id`() {
        membershipService.disambiguateDisplayName(ALICE, null) shouldBeEqualTo ALICE
    }
}
