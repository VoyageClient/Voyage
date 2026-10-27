/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import im.vector.app.core.platform.DefaultListUpdateCallback
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Holds the landed row until the reader scrolls, another jump starts, or the hold expires. */
class ScrollOnHighlightedEventCallback(
        private val recyclerView: RecyclerView,
        private val layoutManager: LinearLayoutManager,
        private val timelineEventController: TimelineEventController,
        private val onLanded: (String?) -> Unit = {},
) : DefaultListUpdateCallback {

    private class Anchor(
            val alignment: ScrollAnchorAlignment,
            val eventId: String?,
            val modelId: Long,
            val requestedEventId: String?,
            var landed: Boolean = false,
    )

    private val scheduledEventId = AtomicReference<String?>()
    private var preferReadMarker = false
    private var scheduleDeadlineMs = 0L
    private var neighborhoodDeadlineMs = 0L
    private var anchor: Anchor? = null
    private var anchorOffset = 0
    private var anchorDeadlineMs = 0L
    private var lockedTop: Int? = null
    private var lockedHeight = 0
    private var viewportStart = 0
    private var viewportEnd = 0
    private val generation = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scrollRetry = Runnable { scrollIfNeeded() }
    private var disposed = false
    private var pausedAtMs: Long? = null

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
            if (newState == RecyclerView.SCROLL_STATE_DRAGGING) cancel()
        }
    }

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        holdAnchor()
        true
    }

    init {
        recyclerView.addOnScrollListener(scrollListener)
        recyclerView.viewTreeObserver.addOnPreDrawListener(preDrawListener)
    }

    override fun onInserted(position: Int, count: Int) = onListChanged()
    override fun onRemoved(position: Int, count: Int) = onListChanged()
    override fun onMoved(position: Int, count: Int) = onListChanged()
    override fun onChanged(position: Int, count: Int, tag: Any?) = onListChanged()

    private fun onListChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val requestedGeneration = generation.get()
            mainHandler.post {
                if (!disposed && generation.get() == requestedGeneration) onListChanged()
            }
            return
        }
        if (disposed) return
        scrollIfNeeded()
    }

    private fun retryAfter(delayMs: Long) {
        mainHandler.removeCallbacks(scrollRetry)
        mainHandler.postDelayed(scrollRetry, delayMs.coerceAtLeast(1L))
    }

    private fun scrollIfNeeded() {
        if (disposed || pausedAtMs != null) return
        val eventId = scheduledEventId.get() ?: return
        val now = SystemClock.uptimeMillis()
        if (recyclerView.isComputingLayout || usableHeight() <= 0) {
            if (now >= scheduleDeadlineMs) cancel() else retryAfter(16L)
            return
        }
        val markerPosition = if (preferReadMarker) timelineEventController.findReadMarkerPositionInAdapter(eventId) else null
        val exactPosition = timelineEventController.findEventPositionInAdapter(eventId)
        val position = markerPosition ?: exactPosition
        if (position == null) {
            if (now >= scheduleDeadlineMs) {
                val nearest = timelineEventController.searchPositionOfEventOrNearest(eventId)
                if (nearest != null) land(nearest, ScrollAnchorAlignment.CENTER, null, eventId)
                else cancel()
            } else {
                retryAfter(scheduleDeadlineMs - now)
            }
            return
        }
        val modelId = timelineEventController.adapter.getItemId(position)
        val view = matchingView(position, modelId)
        if (markerPosition == null && view != null && !recyclerView.hasPendingAdapterUpdates() &&
                ScrollAnchorMath.isFullyVisible(recyclerView.paddingTop, endAfterPadding(),
                        layoutManager.getDecoratedTop(view), layoutManager.getDecoratedBottom(view))) {
            land(position, ScrollAnchorAlignment.CENTER, eventId, eventId, view)
            return
        }
        if (neighborhoodDeadlineMs == 0L) neighborhoodDeadlineMs = now + NEIGHBORHOOD_TIMEOUT_MS
        if (markerPosition == null && now < neighborhoodDeadlineMs &&
                !timelineEventController.isNeighborhoodBuilt(eventId, NEIGHBORHOOD_RADIUS)) {
            retryAfter(neighborhoodDeadlineMs - now)
            return
        }
        land(position, if (markerPosition == null) ScrollAnchorAlignment.CENTER else ScrollAnchorAlignment.TOP,
                if (markerPosition == null) eventId else null, eventId)
    }

    private fun land(position: Int, alignment: ScrollAnchorAlignment, eventId: String?, requestedEventId: String?, visibleView: View? = null) {
        val modelId = timelineEventController.adapter.getItemId(position)
        releaseAnchor()
        recyclerView.stopScroll()
        mainHandler.removeCallbacks(scrollRetry)
        scheduledEventId.set(null)
        viewportStart = recyclerView.paddingTop
        viewportEnd = endAfterPadding()
        anchor = Anchor(alignment, eventId, modelId, requestedEventId)
        anchorDeadlineMs = SystemClock.uptimeMillis() + ANCHOR_MAX_MS
        if (visibleView != null) {
            lockedTop = layoutManager.getDecoratedTop(visibleView)
            lockedHeight = layoutManager.getDecoratedMeasuredHeight(visibleView)
            anchorOffset = ScrollAnchorMath.currentOffset(viewportEnd, layoutManager.getDecoratedBottom(visibleView))
        } else {
            anchorOffset = ScrollAnchorMath.desiredOffset(alignment, usableHeight(), usableHeight() / 3)
            layoutManager.scrollToPositionWithOffset(position, anchorOffset)
        }
    }

    private fun matchingView(position: Int, modelId: Long): View? = layoutManager.findViewByPosition(position)?.takeIf {
        val holder = recyclerView.getChildViewHolder(it)
        holder.itemId == modelId && holder.bindingAdapterPosition == position
    }

    private fun holdAnchor() {
        if (pausedAtMs != null) return
        val held = anchor ?: return
        if (SystemClock.uptimeMillis() > anchorDeadlineMs) {
            releaseAnchor()
            return
        }
        if (recyclerView.isComputingLayout || recyclerView.hasPendingAdapterUpdates() || usableHeight() <= 0) return
        val start = recyclerView.paddingTop
        val end = endAfterPadding()
        if (start != viewportStart || end != viewportEnd) {
            lockedTop = lockedTop?.let {
                ScrollAnchorMath.rebaseTop(it, lockedHeight, viewportStart, viewportEnd, start, end, held.alignment)
            }
            viewportStart = start
            viewportEnd = end
        }
        if (held.alignment == ScrollAnchorAlignment.TOP && held.requestedEventId != null &&
                timelineEventController.findReadMarkerPositionInAdapter(held.requestedEventId) == null) {
            releaseAnchor()
            return
        }
        val position = held.eventId?.let { timelineEventController.findEventPositionInAdapter(it) }
                ?: timelineEventController.findModelPositionInAdapter(held.modelId)
                ?: return
        val modelId = timelineEventController.adapter.getItemId(position)
        val view = matchingView(position, modelId)
        if (view == null) {
            lockedTop?.let { anchorOffset = end - it - lockedHeight }
            if (!recyclerView.isLayoutRequested) layoutManager.scrollToPositionWithOffset(position, anchorOffset)
            return
        }
        val height = layoutManager.getDecoratedMeasuredHeight(view)
        val wantedTop = lockedTop ?: (end - ScrollAnchorMath.desiredOffset(held.alignment, usableHeight(), height) - height)
        val drift = layoutManager.getDecoratedTop(view) - wantedTop
        if (abs(drift) > DRIFT_TOLERANCE_PX) {
            recyclerView.scrollBy(0, drift)
        }
        // scrollBy can recycle the view or consume adapter updates. Never retain that old view's geometry.
        val currentPosition = held.eventId?.let { timelineEventController.findEventPositionInAdapter(it) }
                ?: timelineEventController.findModelPositionInAdapter(modelId) ?: return
        val current = matchingView(currentPosition, timelineEventController.adapter.getItemId(currentPosition)) ?: return
        var actualTop = layoutManager.getDecoratedTop(current)
        val remainder = actualTop - wantedTop
        val missingDirection = if (remainder > 0) Timeline.Direction.FORWARDS else Timeline.Direction.BACKWARDS
        if (held.landed && abs(remainder) > DRIFT_TOLERANCE_PX && timelineEventController.timeline?.hasMoreToLoad(missingDirection) == true) {
            // A loading edge is temporary. Keep the landed row in place even when scrollBy cannot
            // fill that edge yet; the next page supplies the missing space.
            layoutManager.offsetChildrenVertical(-remainder)
            actualTop = layoutManager.getDecoratedTop(current)
        }
        if (lockedTop == null || abs(actualTop - wantedTop) > DRIFT_TOLERANCE_PX) {
            // A boundary can prevent the full correction. Do not replay the remainder when another page arrives.
            lockedTop = actualTop
        }
        val currentHeight = layoutManager.getDecoratedMeasuredHeight(current)
        lockedHeight = currentHeight
        anchorOffset = end - (lockedTop ?: actualTop) - currentHeight
        if (!held.landed && actualTop < end && layoutManager.getDecoratedBottom(current) > start) {
            held.landed = true
            onLanded(held.requestedEventId)
        }
    }

    private fun usableHeight() = recyclerView.height - recyclerView.paddingTop - recyclerView.paddingBottom

    private fun endAfterPadding() = recyclerView.height - recyclerView.paddingBottom

    private fun releaseAnchor() {
        anchor = null
        lockedTop = null
        lockedHeight = 0
    }

    fun isSettling(): Boolean = scheduledEventId.get() != null || anchor != null

    fun cancel() {
        generation.incrementAndGet()
        mainHandler.removeCallbacks(scrollRetry)
        scheduledEventId.set(null)
        neighborhoodDeadlineMs = 0L
        releaseAnchor()
    }

    fun scheduleScrollTo(eventId: String?, preferReadMarker: Boolean = false) {
        if (disposed) return
        cancel()
        eventId ?: return
        this.preferReadMarker = preferReadMarker
        scheduledEventId.set(eventId)
        scheduleDeadlineMs = SystemClock.uptimeMillis() + SCHEDULE_TIMEOUT_MS
        scrollIfNeeded()
    }

    fun setPaused(paused: Boolean) {
        if (paused) {
            if (pausedAtMs == null) pausedAtMs = SystemClock.uptimeMillis()
            mainHandler.removeCallbacks(scrollRetry)
        } else {
            pausedAtMs?.let { start ->
                val elapsed = SystemClock.uptimeMillis() - start
                scheduleDeadlineMs += elapsed
                if (neighborhoodDeadlineMs != 0L) neighborhoodDeadlineMs += elapsed
                anchorDeadlineMs += elapsed
            }
            pausedAtMs = null
            scrollIfNeeded()
        }
    }

    fun dispose() {
        cancel()
        disposed = true
        recyclerView.removeOnScrollListener(scrollListener)
        if (recyclerView.viewTreeObserver.isAlive) recyclerView.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
    }

    companion object {
        private const val SCHEDULE_TIMEOUT_MS = 10_000L
        private const val NEIGHBORHOOD_TIMEOUT_MS = 1_500L
        private const val NEIGHBORHOOD_RADIUS = 8
        private const val ANCHOR_MAX_MS = 15_000L
        private const val DRIFT_TOLERANCE_PX = 1
    }
}
