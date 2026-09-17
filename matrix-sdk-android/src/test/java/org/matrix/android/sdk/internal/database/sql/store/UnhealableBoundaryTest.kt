/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

private const val ROOM_ID = "!room:hs"
private const val OTHER_ROOM_ID = "!other:hs"

class UnhealableBoundaryTest {

    private val store = ChunkSqlStore(mockk(relaxed = true))

    @Test
    fun `a boundary is worth walking until a walk has failed on it`() {
        store.isBoundaryUnhealable(ROOM_ID, "717|1788811828647") shouldBeEqualTo false
    }

    /** The walk costs two round trips and its answer for a boundary does not change. */
    @Test
    fun `a failed walk is not repeated for the same boundary`() {
        store.markBoundaryUnhealable(ROOM_ID, "717|1788811828647")

        store.isBoundaryUnhealable(ROOM_ID, "717|1788811828647") shouldBeEqualTo true
    }

    @Test
    fun `another boundary in the same room is still worth walking`() {
        store.markBoundaryUnhealable(ROOM_ID, "717|1788811828647")

        store.isBoundaryUnhealable(ROOM_ID, "723|1788812225514") shouldBeEqualTo false
    }

    @Test
    fun `another room is unaffected`() {
        store.markBoundaryUnhealable(ROOM_ID, "717|1788811828647")

        store.isBoundaryUnhealable(OTHER_ROOM_ID, "717|1788811828647") shouldBeEqualTo false
    }

    /** A page landing under the boundary moves it, so the verdict no longer describes anything. */
    @Test
    fun `a fetched page clears what was learnt about that room's boundaries`() {
        store.markBoundaryUnhealable(ROOM_ID, "717|1788811828647")
        store.markBoundaryUnhealable(OTHER_ROOM_ID, "800|1788800000000")

        store.forgetUnhealableBoundaries(ROOM_ID)

        store.isBoundaryUnhealable(ROOM_ID, "717|1788811828647") shouldBeEqualTo false
        store.isBoundaryUnhealable(OTHER_ROOM_ID, "800|1788800000000") shouldBeEqualTo true
    }
}
