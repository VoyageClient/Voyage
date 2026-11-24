/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.profile

import android.content.Context
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Before
import org.junit.Test

class IdentityPresentationTest {

    private val self = "@me:example.org"

    @Before
    fun setUp() {
        IdentityPresentation.initialize(mockk<Context>(relaxed = true), self, SESSION_ID)
        IdentityPresentation.update(self, AnonymizationConfig(rule = AnonymizationRule(enabled = true)))
    }

    @After
    fun tearDown() {
        IdentityPresentation.release(SESSION_ID)
    }

    // Two made-up users whose generated names happen to be the same.
    private fun collidingPair(): Pair<String, String> {
        val seen = HashMap<String, String>()
        for (index in 0 until 100_000) {
            val userId = "@user$index:example.org"
            seen.put(IdentityAliases.name(userId), userId)?.let { return it to userId }
        }
        error("no collision")
    }

    @Test
    fun `people sharing a name are numbered within the room, the first keeping the bare name`() {
        val (first, second) = collidingPair()
        val name = IdentityAliases.name(first)

        IdentityPresentation.name(first, ROOM_A) shouldBeEqualTo name
        IdentityPresentation.name(second, ROOM_A) shouldBeEqualTo "$name 2"
        // Asking again changes nothing.
        IdentityPresentation.name(first, ROOM_A) shouldBeEqualTo name
        IdentityPresentation.name(second, ROOM_A) shouldBeEqualTo "$name 2"
    }

    @Test
    fun `a name shared in one room is not numbered in another`() {
        val (first, second) = collidingPair()
        IdentityPresentation.name(first, ROOM_A)
        IdentityPresentation.name(second, ROOM_A)

        IdentityPresentation.name(second, ROOM_B) shouldBeEqualTo IdentityAliases.name(second)
    }

    @Test
    fun `stand-in ids follow the generated name, numbered like it`() {
        val (first, second) = collidingPair()
        val localpart = IdentityAliases.name(first).lowercase().replace(' ', '_')

        IdentityPresentation.displayedId(first, ROOM_A) shouldBeEqualTo "@$localpart:anonymized.local"
        IdentityPresentation.displayedId(second, ROOM_A) shouldBeEqualTo "@${localpart}_2:anonymized.local"
        IdentityPresentation.displayedId(self) shouldBeEqualTo "@me:anonymized.local"
        IdentityPresentation.displayedId(first, IdentityPresentation.REVEALED_SCOPE) shouldBeEqualTo first
    }

    @Test
    fun `with one shared name the stand-in id is a short fingerprint`() {
        IdentityPresentation.update(self, AnonymizationConfig(rule = AnonymizationRule(enabled = true), naming = "SHARED"))

        IdentityPresentation.displayedId("@river:example.org").matches(Regex("@[0-9a-f]{8}:anonymized\\.local")) shouldBeEqualTo true
    }

    private companion object {
        const val SESSION_ID = "identity-test"
        const val ROOM_A = "!garden:example.org"
        const val ROOM_B = "!orchard:example.org"
    }
}
