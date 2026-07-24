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
        private val timelineEventController: TimelineEventController,
        private val onLanded: () -> Unit = {}
) : DefaultListUpdateCallback {

    private val scheduledEventId = AtomicReference<String?>()

    // A deadline rather than a retry count: after a restart the controller still dispatches deferred
    // model-build diffs for the outgoing snapshot in a rapid burst, which would exhaust any
    // per-callback budget before the new snapshot (with the target) ever arrives.
    private var scheduleDeadlineMs = 0L

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
                if (SystemClock.uptimeMillis() > scheduleDeadlineMs) {
                    // Waited long enough and it never got a row (hidden/aggregated away): approximate once.
                    scheduledEventId.set(null)
                    timelineEventController.searchPositionOfEventOrNearest(eventId) ?: return
                } else {
                    // Wait for the model; a later build re-fires this and we snap exactly. Meanwhile
                    // keep the viewport near the nearest built row so the list replacement doesn't
                    // leave a page of unrelated rows on screen (builds focus around the target, so
                    // "nearest built" converges on it). ensureOnScreen only acts when it's fully off.
                    timelineEventController.searchPositionOfEventOrNearest(eventId)?.let { near ->
                        recyclerView.post { ensureOnScreen(near) }
                    }
                    return
                }
            }
            // Not loaded yet (context still paginating in, or stale pre-restart callbacks firing):
            // wait for the deadline, approximating toward it when something nearby is built.
            SystemClock.uptimeMillis() <= scheduleDeadlineMs -> {
                val nearest = timelineEventController.searchPositionOfEventOrNearest(eventId)
                nearest?.let { near -> recyclerView.post { ensureOnScreen(near) } }
                return
            }
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
            if (pinnedEventId != null) onLanded()
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

    /** True while a jump is in flight or its target is still pinned (surroundings still loading in). */
    fun isSettling(): Boolean = scheduledEventId.get() != null || pinnedEventId != null

    fun scheduleScrollTo(eventId: String?) {
        scheduledEventId.set(eventId)
        scheduleDeadlineMs = SystemClock.uptimeMillis() + SCHEDULE_TIMEOUT_MS
        pinnedEventId = null
    }

    companion object {
        // How long the exact target may take to get a row (restart + context fetch + model build)
        // before giving up on it (it may be hidden or aggregated away and never get one).
        private const val SCHEDULE_TIMEOUT_MS = 10_000L

        // How long after landing to keep the target on screen as its surrounding events load/decrypt in.
        private const val PIN_DURATION_MS = 2500L
    }
}
