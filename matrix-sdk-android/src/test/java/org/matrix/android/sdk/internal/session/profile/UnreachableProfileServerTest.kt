/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.profile

import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class UnreachableProfileServerTest {

    private fun cache() = ExtendedProfileCache(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))

    @Test
    fun `a server is not unreachable until a lookup there runs out of time`() {
        cache().isServerUnreachable("@alice:example.org") shouldBeEqualTo false
    }

    @Test
    fun `a timed-out lookup spares the next profile on that server the same wait`() {
        val cache = cache()

        cache.markServerUnreachable("@alice:example.org")

        cache.isServerUnreachable("@alice:example.org") shouldBeEqualTo true
        cache.isServerUnreachable("@bob:example.org") shouldBeEqualTo true
    }

    /** One dead server must not stop lookups everywhere else. */
    @Test
    fun `only that server is spared`() {
        val cache = cache()

        cache.markServerUnreachable("@alice:example.org")

        cache.isServerUnreachable("@carol:other.example") shouldBeEqualTo false
    }

    @Test
    fun `an id with no server is ignored rather than marking everything`() {
        val cache = cache()

        cache.markServerUnreachable("alice")

        cache.isServerUnreachable("alice") shouldBeEqualTo false
        cache.isServerUnreachable("@alice:example.org") shouldBeEqualTo false
    }
}
