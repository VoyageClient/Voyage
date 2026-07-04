/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import im.vector.app.core.platform.DefaultListUpdateCallback
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import java.util.concurrent.atomic.AtomicReference

/**
 * This handles scrolling to an event which wasn't yet loaded when scheduled.
 */
class ScrollOnHighlightedEventCallback(
        private val recyclerView: RecyclerView,
        private val layoutManager: LinearLayoutManager,
        private val timelineEventController: TimelineEventController
) : DefaultListUpdateCallback {

    private val scheduledEventId = AtomicReference<String?>()
    private var nearestScrollBudget = 0

    override fun onInserted(position: Int, count: Int) {
        scrollIfNeeded()
    }

    override fun onChanged(position: Int, count: Int, tag: Any?) {
        scrollIfNeeded()
    }

    private fun scrollIfNeeded() {
        val eventId = scheduledEventId.get() ?: return
        // The target may not have a built model yet (context still paginating/decrypting in): scroll
        // to the nearest event but stay scheduled, and snap exactly once the target's row exists —
        // consuming the schedule on the nearest match left the jump landing "close but not on it".
        val exactPosition = timelineEventController.searchPositionOfEvent(eventId)
        val positionToScroll = when {
            exactPosition != null -> exactPosition.also { scheduledEventId.set(null) }
            nearestScrollBudget-- > 0 -> timelineEventController.searchPositionOfEventOrNearest(eventId) ?: return
            else -> {
                // Give up: the target may be hidden or aggregated away and never get a row, and a
                // still-armed schedule would yank the list whenever it materialized much later.
                scheduledEventId.set(null)
                return
            }
        }
        // Epoxy dispatches model-build-finished on a background handler, so scrolling here directly would
        // call scrollToPosition()/requestLayout() off the main thread — which doesn't reliably schedule a
        // layout pass, leaving a jump-to-event blank until something else forces one. Post it onto the
        // RecyclerView's (main) thread so the scroll, and the layout it triggers, actually happen.
        recyclerView.post {
            recyclerView.stopScroll()
            layoutManager.scrollToPosition(positionToScroll)
        }
    }

    fun scheduleScrollTo(eventId: String?) {
        scheduledEventId.set(eventId)
        nearestScrollBudget = NEAREST_SCROLL_BUDGET
    }

    companion object {
        // How many model rebuilds may settle for a nearest-match scroll before giving up on the
        // exact target (it may be hidden or aggregated away and never get a row).
        private const val NEAREST_SCROLL_BUDGET = 20
    }
}
