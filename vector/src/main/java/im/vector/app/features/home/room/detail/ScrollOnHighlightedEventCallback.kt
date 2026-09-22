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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Jump-to-event: waits for the target to exist, lands on it, then holds it there.
 *
 * The timeline keeps changing shape for seconds after a jump (budgeted model builds, decryption,
 * previews, media), and RecyclerView re-anchors each layout on whichever row is nearest the layout
 * start — never on the one the reader asked for. So the target is re-applied as the anchor, at its
 * measured offset, until the shape stops changing.
 */
class ScrollOnHighlightedEventCallback(
        private val recyclerView: RecyclerView,
        private val layoutManager: LinearLayoutManager,
        private val timelineEventController: TimelineEventController,
        private val onLanded: () -> Unit = {},
) : DefaultListUpdateCallback {

    private class Anchor(
            val alignment: ScrollAnchorAlignment,
            val resolvePosition: () -> Int?,
    )

    private val scheduledEventId = AtomicReference<String?>()

    // A deadline rather than a retry count: after a restart the controller still dispatches deferred
    // model-build diffs for the outgoing snapshot in a rapid burst, which would exhaust any
    // per-callback budget before the new snapshot (with the target) ever arrives.
    private var scheduleDeadlineMs = 0L

    // Set once the target first has a row: how long we still wait for a screenful around it to build.
    @Volatile private var neighborhoodDeadlineMs = 0L

    private var anchor: Anchor? = null
    private var anchorOffset = 0
    private var anchorDeadlineMs = 0L
    @Volatile private var lastAnchorActivityMs = 0L
    private var consecutiveDrawSkips = 0

    // Where the row is held once the layout proves it cannot give it the offset we ask for (it is
    // against the end of the list), instead of asking for the same impossible place every frame.
    private var settledOffset: Int? = null
    private var lastRequestedOffset: Int? = null
    private var lastObservedOffset: Int? = null

    // Invalidates a landing posted before the jump was cancelled, which would otherwise pull the
    // reader back a frame after they took over.
    private val generation = AtomicInteger(0)

    init {
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                // The user taking over ends the jump, pending or landed: a target still waiting for its
                // model would otherwise snap them back to it whenever the build finally arrives, which
                // is seconds after they scrolled away.
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) cancel()
            }
        })
        // A url preview or an image resizing the view it is already bound to reaches no list-diff
        // callback at all, so the anchor is enforced per frame — after layout, before draw, which is
        // the last point a correction is still free.
        recyclerView.viewTreeObserver.addOnPreDrawListener { holdAnchor() }
    }

    override fun onInserted(position: Int, count: Int) = onListChanged()

    override fun onRemoved(position: Int, count: Int) = onListChanged()

    override fun onMoved(position: Int, count: Int) = onListChanged()

    override fun onChanged(position: Int, count: Int, tag: Any?) = onListChanged()

    private fun onListChanged() {
        lastAnchorActivityMs = SystemClock.uptimeMillis()
        scrollIfNeeded()
    }

    private fun scrollIfNeeded() {
        val eventId = scheduledEventId.get() ?: return
        val now = SystemClock.uptimeMillis()
        val position = timelineEventController.searchPositionOfEvent(eventId)
        if (position == null) {
            // Nothing moves until the target has a row: the nearest built row is typically the oldest
            // loaded edge, so landing there makes the backward loader visible and paginates — and in an
            // encrypted room the decrypt storm re-fires this and paginates again, running the window
            // away to hundreds of events.
            if (now > scheduleDeadlineMs) {
                // It never got a row (hidden, or aggregated into a neighbor): approximate once.
                scheduledEventId.set(null)
                val nearest = timelineEventController.searchPositionOfEventOrNearest(eventId)
                if (nearest != null) {
                    land(nearest, ScrollAnchorAlignment.CENTER) {
                        timelineEventController.searchPositionOfEventOrNearest(eventId)
                    }
                }
            }
            return
        }
        if (neighborhoodDeadlineMs == 0L) neighborhoodDeadlineMs = now + NEIGHBORHOOD_TIMEOUT_MS
        // Rows materialize one budgeted build pass at a time, so a span that has only its target built is
        // a span the reader would watch fill in around them. Let it fill first, then land once.
        if (now < neighborhoodDeadlineMs &&
                !timelineEventController.isNeighborhoodBuilt(eventId, NEIGHBORHOOD_RADIUS)) {
            return
        }
        scheduledEventId.set(null)
        land(position, ScrollAnchorAlignment.CENTER) { timelineEventController.searchPositionOfEvent(eventId) }
    }

    private fun land(position: Int, alignment: ScrollAnchorAlignment, resolvePosition: () -> Int?) {
        // Epoxy dispatches model-build-finished on a background handler, so scrolling here directly would
        // call scrollToPosition()/requestLayout() off the main thread — which doesn't reliably schedule a
        // layout pass, leaving a jump-to-event blank until something else forces one. Post it onto the
        // RecyclerView's (main) thread so the scroll, and the layout it triggers, actually happen.
        val landGeneration = generation.get()
        recyclerView.post {
            if (generation.get() != landGeneration) return@post
            releaseAnchor()
            recyclerView.stopScroll()
            // The row has not been measured yet, so this is only a first guess; the next frame's
            // holdAnchor() places it properly from its real height, before it is drawn.
            anchorOffset = ScrollAnchorMath.desiredOffset(alignment, usableHeight(), usableHeight() / 3)
            layoutManager.scrollToPositionWithOffset(position, anchorOffset)
            val now = SystemClock.uptimeMillis()
            anchor = Anchor(alignment, resolvePosition)
            anchorDeadlineMs = now + ANCHOR_MAX_MS
            lastAnchorActivityMs = now
            onLanded()
        }
    }

    /**
     * Keeps the landed row at its offset. Returns false to drop the frame when a correction was made,
     * so the reader never sees the drifted position — bounded, since a correction that cannot converge
     * would otherwise stop the timeline drawing at all.
     */
    private fun holdAnchor(): Boolean {
        val safeAnchor = anchor ?: return true
        val now = SystemClock.uptimeMillis()
        if (now > anchorDeadlineMs || now - lastAnchorActivityMs > ANCHOR_QUIET_MS) {
            releaseAnchor()
            return true
        }
        val position = safeAnchor.resolvePosition() ?: return true
        val view = layoutManager.findViewByPosition(position)
        if (view == null) {
            // A diff moved the row out of the laid-out range entirely; put it back at its last offset.
            layoutManager.scrollToPositionWithOffset(position, anchorOffset)
            lastAnchorActivityMs = now
            return skipFrame()
        }
        val desired = settledOffset ?: ScrollAnchorMath.desiredOffset(
                safeAnchor.alignment, usableHeight(), layoutManager.getDecoratedMeasuredHeight(view)
        )
        val current = ScrollAnchorMath.currentOffset(endAfterPadding(), layoutManager.getDecoratedBottom(view))
        if (abs(desired - current) <= DRIFT_TOLERANCE_PX) {
            lastRequestedOffset = null
            consecutiveDrawSkips = 0
            return true
        }
        if (lastRequestedOffset == desired && lastObservedOffset == current) {
            // Asking again changed nothing: the list has no more to scroll on that side. Hold the row
            // where it did land, which still keeps it still while its neighbors grow.
            settledOffset = current
            lastRequestedOffset = null
            consecutiveDrawSkips = 0
            return true
        }
        lastRequestedOffset = desired
        lastObservedOffset = current
        anchorOffset = desired
        layoutManager.scrollToPositionWithOffset(position, desired)
        lastAnchorActivityMs = now
        // A correction of a pixel or two is not worth dropping a frame over; a neighbor that just grew
        // by a paragraph is, or the row is drawn at the wrong place for a frame before it comes back.
        return if (abs(desired - current) > DRAW_SKIP_DRIFT_PX) skipFrame() else true
    }

    private fun skipFrame(): Boolean {
        if (consecutiveDrawSkips >= MAX_DRAW_SKIPS) {
            consecutiveDrawSkips = 0
            return true
        }
        consecutiveDrawSkips++
        return false
    }

    private fun usableHeight() = recyclerView.height - recyclerView.paddingTop - recyclerView.paddingBottom

    private fun endAfterPadding() = recyclerView.height - recyclerView.paddingBottom

    private fun releaseAnchor() {
        anchor = null
        settledOffset = null
        lastRequestedOffset = null
        lastObservedOffset = null
        consecutiveDrawSkips = 0
    }

    /** True while a jump is in flight or its target is still held (the timeline around it still moving). */
    fun isSettling(): Boolean = scheduledEventId.get() != null || anchor != null

    /** Drop a pending jump and release the landed target, so an explicit scroll elsewhere isn't pulled back. */
    fun cancel() {
        generation.incrementAndGet()
        scheduledEventId.set(null)
        neighborhoodDeadlineMs = 0L
        releaseAnchor()
    }

    /** Jump to [eventId], waiting for it to load and build rather than moving the viewport meanwhile. */
    fun scheduleScrollTo(eventId: String?) {
        cancel()
        eventId ?: return
        val position = timelineEventController.searchPositionOfEvent(eventId)
        if (position != null && layoutManager.findViewByPosition(position) != null) {
            // Already on screen: there is nothing to wait for, and nothing for the reader to be told.
            land(position, ScrollAnchorAlignment.CENTER) { timelineEventController.searchPositionOfEvent(eventId) }
            return
        }
        scheduledEventId.set(eventId)
        scheduleDeadlineMs = SystemClock.uptimeMillis() + SCHEDULE_TIMEOUT_MS
        scrollIfNeeded()
    }

    /** Jump to a row identified by position rather than by event, e.g. the read marker. */
    fun scrollToResolvedPosition(alignment: ScrollAnchorAlignment, resolvePosition: () -> Int?): Boolean {
        cancel()
        val position = resolvePosition() ?: return false
        land(position, alignment, resolvePosition)
        return true
    }

    companion object {
        // How long the exact target may take to get a row (restart + context fetch + model build)
        // before giving up on it (it may be hidden or aggregated away and never get one).
        private const val SCHEDULE_TIMEOUT_MS = 10_000L

        // Once the target has a row, how long to keep waiting for its surroundings to build before
        // landing anyway — a jump that never visibly happens is worse than landing into a filling span.
        private const val NEIGHBORHOOD_TIMEOUT_MS = 1_500L

        // Events either side of the target that must have been built before landing: roughly a screenful.
        private const val NEIGHBORHOOD_RADIUS = 8

        // Ceiling on holding the target, for a room that never stops changing shape.
        private const val ANCHOR_MAX_MS = 15_000L

        // Released once nothing has moved for this long, rather than on a fixed timer: what the target
        // has to survive is decryption and media, whose timing has nothing to do with when it landed.
        private const val ANCHOR_QUIET_MS = 400L

        private const val DRIFT_TOLERANCE_PX = 1

        private const val DRAW_SKIP_DRIFT_PX = 8

        private const val MAX_DRAW_SKIPS = 2
    }
}
