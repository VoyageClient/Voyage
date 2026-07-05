/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail

import android.os.SystemClock
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

    // After landing on the target we keep it on screen for a short window: surrounding events are still
    // loading/decrypting in, and an encrypted neighbour growing from its short "Encrypted message"
    // placeholder to full height shoves the target off screen. Released early once the user scrolls.
    private var pinnedEventId: String? = null
    private var pinDeadlineMs = 0L

    init {
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) pinnedEventId = null
            }
        })
        // Neighbour height changes (a decrypted message replacing its placeholder) apply during bind/layout,
        // not on a list-diff callback, so correct per frame — after layout, before draw — while pinned.
        recyclerView.viewTreeObserver.addOnPreDrawListener {
            keepPinnedOnScreen()
            true
        }
    }

    override fun onInserted(position: Int, count: Int) {
        scrollIfNeeded()
    }

    override fun onChanged(position: Int, count: Int, tag: Any?) {
        scrollIfNeeded()
    }

    private fun scrollIfNeeded() {
        val eventId = scheduledEventId.get() ?: return
        val exactPosition = timelineEventController.searchPositionOfEvent(eventId)
        val positionToScroll = when {
            exactPosition != null -> exactPosition.also {
                scheduledEventId.set(null)
                pinnedEventId = eventId
                pinDeadlineMs = SystemClock.uptimeMillis() + PIN_DURATION_MS
            }
            // Target loaded but its model isn't built yet: WAIT for it, don't scroll to a nearest row.
            // A nearest scroll lands on the oldest loaded edge, makes the backward loader visible and
            // triggers pagination; in an encrypted room the decrypt storm keeps rebuilding, so each
            // rebuild re-fires this and paginates again — the window runs away to hundreds of events,
            // starving the single DB thread (blank timeline + blank toolbar, here and in the next room
            // opened). A later model build re-fires this and we snap exactly.
            timelineEventController.isEventInSnapshot(eventId) -> {
                if (nearestScrollBudget-- <= 0) {
                    // Waited long enough and it never got a row (hidden/aggregated away): approximate once.
                    scheduledEventId.set(null)
                    timelineEventController.searchPositionOfEventOrNearest(eventId) ?: return
                } else {
                    // Wait for the model; a later build re-fires this and we snap exactly.
                    return
                }
            }
            // Not loaded yet (context still paginating in): approximate toward it while it arrives.
            nearestScrollBudget-- > 0 -> timelineEventController.searchPositionOfEventOrNearest(eventId) ?: return
            else -> {
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
            ensureOnScreen(positionToScroll)
        }
    }

    private fun keepPinnedOnScreen() {
        val pinned = pinnedEventId ?: return
        if (SystemClock.uptimeMillis() > pinDeadlineMs) {
            pinnedEventId = null
            return
        }
        timelineEventController.searchPositionOfEvent(pinned)?.let { ensureOnScreen(it) }
    }

    // Bring the target back into view only if it's fully off screen; if any part of it shows (or it's just
    // shifted), leave it where it is. Aligns it to an edge, which is all that's needed.
    private fun ensureOnScreen(position: Int) {
        val view = layoutManager.findViewByPosition(position)
        val onScreen = view != null && view.bottom > 0 && view.top < recyclerView.height
        if (!onScreen) {
            recyclerView.stopScroll()
            layoutManager.scrollToPosition(position)
        }
    }

    fun scheduleScrollTo(eventId: String?) {
        scheduledEventId.set(eventId)
        nearestScrollBudget = NEAREST_SCROLL_BUDGET
        pinnedEventId = null
    }

    companion object {
        // How many model rebuilds may settle before giving up on the exact target (it may be hidden or
        // aggregated away and never get a row, or its context may never paginate in).
        private const val NEAREST_SCROLL_BUDGET = 20

        // How long after landing to keep the target on screen as its surrounding events load/decrypt in.
        private const val PIN_DURATION_MS = 2500L
    }
}
