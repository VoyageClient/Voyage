/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail

import android.os.Looper
import android.os.SystemClock
import android.view.View
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
 * start — never on the one the reader asked for. So the target's top edge is held where it first
 * showed until the reader scrolls, another jump starts, or [ANCHOR_MAX_MS] passes.
 */
class ScrollOnHighlightedEventCallback(
        private val recyclerView: RecyclerView,
        private val layoutManager: LinearLayoutManager,
        private val timelineEventController: TimelineEventController,
        private val onLanded: () -> Unit = {},
) : DefaultListUpdateCallback {

    private class Anchor(
            val alignment: ScrollAnchorAlignment,
            // Null when the row isn't one event (the read marker, or a nearest-row fallback).
            val eventId: String?,
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

    // The target's top edge as first shown, held there until the reader takes over. Its top rather than
    // its center: the reader is reading it, and its own media growing must not slide it either.
    private var lockedTop: Int? = null
    private var lockedHeight = 0

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
        // callback at all, so the anchor is checked after layout on each frame.
        recyclerView.viewTreeObserver.addOnPreDrawListener { holdAnchor() }
    }

    override fun onInserted(position: Int, count: Int) = scrollIfNeeded()

    override fun onRemoved(position: Int, count: Int) = scrollIfNeeded()

    override fun onMoved(position: Int, count: Int) = scrollIfNeeded()

    override fun onChanged(position: Int, count: Int, tag: Any?) = scrollIfNeeded()

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
                    land(nearest, ScrollAnchorAlignment.CENTER, eventId = null) {
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
        land(position, ScrollAnchorAlignment.CENTER, eventId) { timelineEventController.searchPositionOfEvent(eventId) }
    }

    private fun land(position: Int, alignment: ScrollAnchorAlignment, eventId: String?, resolvePosition: () -> Int?) {
        // Off the main thread (Epoxy can dispatch model-build-finished on a background handler), scrolling
        // directly doesn't reliably schedule a layout pass, leaving the jump blank until something else forces
        // one, so it is posted there. On main it applies now: the diff that gave the target its row is laid
        // out next frame, and a post can wait behind that whole bind pass, drawing the new rows at the bottom.
        val landGeneration = generation.get()
        val apply = apply@{
            if (generation.get() != landGeneration) return@apply
            releaseAnchor()
            recyclerView.stopScroll()
            // The row has not been measured yet, so this is only a first guess; holdAnchor() corrects it
            // from its real height in the pre-draw of the frame that lays it out, before it is drawn.
            anchorOffset = ScrollAnchorMath.desiredOffset(alignment, usableHeight(), usableHeight() / 3)
            layoutManager.scrollToPositionWithOffset(position, anchorOffset)
            anchor = Anchor(alignment, eventId, resolvePosition)
            anchorDeadlineMs = SystemClock.uptimeMillis() + ANCHOR_MAX_MS
            onLanded()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply() else recyclerView.post(apply)
    }

    // The reader can already see the target, so it only flashes; it is still held so a neighbor
    // decrypting or loading media doesn't push it away mid-flash.
    private fun holdInPlace(view: View, eventId: String, resolvePosition: () -> Int?) {
        recyclerView.stopScroll()
        anchorOffset = ScrollAnchorMath.currentOffset(endAfterPadding(), layoutManager.getDecoratedBottom(view))
        lockedTop = layoutManager.getDecoratedTop(view)
        anchor = Anchor(ScrollAnchorAlignment.CENTER, eventId, resolvePosition)
        anchorDeadlineMs = SystemClock.uptimeMillis() + ANCHOR_MAX_MS
        onLanded()
    }

    private fun holdAnchor(): Boolean {
        val safeAnchor = anchor ?: return true
        if (SystemClock.uptimeMillis() > anchorDeadlineMs) {
            releaseAnchor()
            return true
        }
        val position = safeAnchor.resolvePosition() ?: return true
        // The mapping can run ahead of the adapter; acting on it would move some other row. The diff it
        // belongs to lands within a frame or two.
        if (safeAnchor.eventId != null && !timelineEventController.isAdapterPositionOf(safeAnchor.eventId, position)) return true
        val view = layoutManager.findViewByPosition(position)
        if (view == null) {
            // A diff moved the row out of the laid-out range entirely; put it back where it was held.
            lockedTop?.let { anchorOffset = endAfterPadding() - it - lockedHeight }
            layoutManager.scrollToPositionWithOffset(position, anchorOffset)
            // Cancelling pre-draw here blanks the whole list while RecyclerView waits for its next layout.
            return true
        }
        val height = layoutManager.getDecoratedMeasuredHeight(view)
        val wantedTop = lockedTop
                ?: (endAfterPadding() - ScrollAnchorMath.desiredOffset(safeAnchor.alignment, usableHeight(), height) - height)
        val drift = layoutManager.getDecoratedTop(view) - wantedTop
        if (abs(drift) > DRIFT_TOLERANCE_PX) {
            // scrollBy moves the laid-out rows right away, so this frame already draws the corrected
            // position; scrollToPositionWithOffset only applies on the next layout, drawing this one stale.
            recyclerView.scrollBy(0, drift)
        }
        if (lockedTop == null) {
            // Where it actually shows: against the end of a short list that can be short of the wanted spot,
            // and moving it once more content arrives would be exactly the shift this avoids.
            lockedTop = layoutManager.getDecoratedTop(view)
        }
        lockedHeight = height
        return true
    }

    private fun usableHeight() = recyclerView.height - recyclerView.paddingTop - recyclerView.paddingBottom

    private fun endAfterPadding() = recyclerView.height - recyclerView.paddingBottom

    private fun releaseAnchor() {
        anchor = null
        lockedTop = null
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
        val view = position?.let { layoutManager.findViewByPosition(it) }
        if (position != null && view != null) {
            val resolvePosition = { timelineEventController.searchPositionOfEvent(eventId) }
            if (ScrollAnchorMath.isFullyVisible(
                            recyclerView.paddingTop, endAfterPadding(),
                            layoutManager.getDecoratedTop(view), layoutManager.getDecoratedBottom(view)
                    )) {
                holdInPlace(view, eventId, resolvePosition)
            } else {
                land(position, ScrollAnchorAlignment.CENTER, eventId, resolvePosition)
            }
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
        land(position, alignment, eventId = null, resolvePosition)
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

        // How long the target is held unless the reader scrolls first: long enough for late media, link
        // previews and the pages loading around it to arrive, which take seconds on a slow link.
        private const val ANCHOR_MAX_MS = 15_000L

        private const val DRIFT_TOLERANCE_PX = 1
    }
}
