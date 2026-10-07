/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.profile

import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test

class CachedProfileTest {

    private val alice = "@alice:example.org"

    private fun cache() = ExtendedProfileCache(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))

    @Test
    fun `a sync delta alone is not a cached profile`() {
        val cache = cache()

        cache.applyProfileUpdates(alice, mapOf("m.tz" to "Europe/London"))

        cache.getCachedProfile(alice).shouldBeNull()
    }

    @Test
    fun `a delta on top of a fetched profile keeps the rest of it`() {
        val cache = cache()
        cache.cacheFromProfile(alice, mapOf("displayname" to "Alice"))

        cache.applyProfileUpdates(alice, mapOf("m.tz" to "Europe/London"))

        cache.getCachedProfile(alice)?.get("displayname") shouldBeEqualTo "Alice"
        cache.getCachedProfile(alice)?.get("m.tz") shouldBeEqualTo "Europe/London"
    }
}
