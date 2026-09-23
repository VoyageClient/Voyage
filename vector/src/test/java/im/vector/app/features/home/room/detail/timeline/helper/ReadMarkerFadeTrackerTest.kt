/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

private const val DURATION = 5_300L

class ReadMarkerFadeTrackerTest {

    @Test
    fun `given a marker never seen then there is nothing to play`() {
        val tracker = ReadMarkerFadeTracker(DURATION)
        tracker.onSession("\$first")

        tracker.remainingMs(1_000L) shouldBe null
    }

    @Test
    fun `given a seen marker then the animation counts down and never restarts`() {
        val tracker = ReadMarkerFadeTracker(DURATION)
        tracker.onSession("\$first")

        tracker.onSeen(1_000L)
        tracker.remainingMs(2_000L) shouldBeEqualTo 4_300L
        // A rebind reports it seen again; the animation must resume, not start over.
        tracker.onSeen(2_000L)
        tracker.remainingMs(2_000L) shouldBeEqualTo 4_300L
        tracker.remainingMs(9_000L) shouldBeEqualTo 0L
    }

    @Test
    fun `given a faded out marker when the same unread session is rebuilt then it stays faded out`() {
        val tracker = ReadMarkerFadeTracker(DURATION)
        tracker.onSession("\$first") shouldBeEqualTo "\$first"
        tracker.markFadedOut()

        tracker.onSession("\$first")

        tracker.isFadedOut shouldBe true
    }

    @Test
    fun `given a faded out marker when a new unread session starts then it fades again`() {
        val tracker = ReadMarkerFadeTracker(DURATION)
        tracker.onSession("\$first")
        tracker.onSeen(0L)
        tracker.markFadedOut()

        tracker.onSession("\$second") shouldBeEqualTo "\$second"

        tracker.isFadedOut shouldBe false
        tracker.remainingMs(0L) shouldBe null
    }

    @Test
    fun `given no unread messages when a marker comes back then it fades again`() {
        val tracker = ReadMarkerFadeTracker(DURATION)
        tracker.onSession("\$first")
        tracker.markFadedOut()

        tracker.onSession(null)
        tracker.onSession("\$first")

        tracker.isFadedOut shouldBe false
    }
}
