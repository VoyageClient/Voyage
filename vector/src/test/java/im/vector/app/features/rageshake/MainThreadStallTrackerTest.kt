/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.rageshake

import im.vector.app.core.utils.MainThreadStallTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainThreadStallTrackerTest {
    @Test
    fun `a stalled heartbeat is reported once and reports its recovery duration`() {
        val tracker = MainThreadStallTracker()
        assertEquals(MainThreadStallTracker.Action.POST, tracker.poll(0, true))
        assertEquals(MainThreadStallTracker.Action.NONE, tracker.poll(4999, true))
        assertEquals(MainThreadStallTracker.Action.STALLED, tracker.poll(5000, true))
        assertEquals(MainThreadStallTracker.Action.NONE, tracker.poll(6000, true))
        assertEquals(7000L, tracker.acknowledge(7000))
        assertEquals(MainThreadStallTracker.Action.POST, tracker.poll(8000, true))
    }

    @Test
    fun `background and debugger pauses discard the old heartbeat deadline`() {
        val tracker = MainThreadStallTracker()
        tracker.poll(0, true)
        assertEquals(MainThreadStallTracker.Action.NONE, tracker.poll(60000, false))
        assertNull(tracker.acknowledge(60000))
        assertEquals(MainThreadStallTracker.Action.POST, tracker.poll(60001, true))
        assertEquals(MainThreadStallTracker.Action.NONE, tracker.poll(61000, true))
    }
}
