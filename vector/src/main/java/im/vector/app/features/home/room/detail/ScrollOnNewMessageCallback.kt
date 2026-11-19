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
import im.vector.app.features.home.room.detail.timeline.item.ItemWithEvents
import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.extensions.tryOrNull
import java.util.concurrent.CopyOnWriteArrayList

class ScrollOnNewMessageCallback(
        private val recyclerView: RecyclerView,
        private val layoutManager: LinearLayoutManager,
        private val timelineEventController: TimelineEventController,
        // Position 0 is only the live edge when the timeline is live. Reading a search result, the window
        // is bounded on the newer side, so the reader can sit at position 0 and be nowhere near the newest
        // event — and a message arriving there used to scroll them to it.
        private val isTimelineLive: () -> Boolean = { true },
        // Called before following a new message to the bottom, so a jump target held there lets go.
        private val onScrollToLatest: () -> Unit = {},
        private val isJumping: () -> Boolean = { false },
) : DefaultListUpdateCallback {

    private val newTimelineEventIds = CopyOnWriteArrayList<String>()
    private var forceScrollUntilMs = 0L

    init {
        // A drag means the user took over mid-transition — stop snapping them back to the bottom.
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && forceScrollUntilMs != 0L) {
                    forceScrollUntilMs = 0L
                }
            }
        })
    }

    fun addNewTimelineEventIds(eventIds: List<String>) {
        newTimelineEventIds.addAll(0, eventIds)
        TimelineScrollDebug.log { "newIds +$eventIds tracked=${newTimelineEventIds.size} ${debugState()}" }
    }

    fun cancelPendingScroll() {
        TimelineScrollDebug.log { "cancelPendingScroll forceLeft=${forceLeftMs()} caller=${TimelineScrollDebug.caller()}" }
        forceScrollUntilMs = 0L
        newTimelineEventIds.clear()
    }

    private fun forceLeftMs() = (forceScrollUntilMs - SystemClock.uptimeMillis()).coerceAtLeast(0L)

    private fun debugState() = TimelineScrollDebug.state(recyclerView, layoutManager)

    override fun onChanged(position: Int, count: Int, tag: Any?) {
        TimelineScrollDebug.log { "diff changed pos=$position count=$count ${debugState()}" }
    }

    override fun onMoved(position: Int, count: Int) {
        TimelineScrollDebug.log { "diff moved from=$position to=$count ${debugState()}" }
    }

    override fun onRemoved(position: Int, count: Int) {
        TimelineScrollDebug.log { "diff removed pos=$position count=$count ${debugState()}" }
    }

    fun forceScrollOnNextUpdate() {
        // A window rather than a one-shot: a jump-to-bottom restarts the timeline, and between the
        // press and the live-edge list replacement several unrelated small diffs can land — a
        // one-shot flag was consumed by the first of those, leaving the actual replacement
        // unscrolled (a "random page" until something else moved the viewport).
        forceScrollUntilMs = SystemClock.uptimeMillis() + FORCE_SCROLL_WINDOW_MS
        TimelineScrollDebug.log { "forceScrollOnNextUpdate caller=${TimelineScrollDebug.caller()}" }
    }

    override fun onInserted(position: Int, count: Int) {
        TimelineScrollDebug.log {
            val model = tryOrNull { timelineEventController.adapter.getModelAtPosition(position) }
            "diff inserted pos=$position count=$count model=${model?.javaClass?.simpleName} " +
                    "ids=${(model as? ItemWithEvents)?.getEventIds()} jumping=${isJumping()} live=${isTimelineLive()} " +
                    "forceLeft=${forceLeftMs()} tracked=${newTimelineEventIds.take(5)} ${debugState()}"
        }
        if (isJumping()) {
            TimelineScrollDebug.log { "inserted -> skip: jumping" }
            return
        }
        if (!isBelowNewestEvent(position)) return
        if (SystemClock.uptimeMillis() < forceScrollUntilMs && isTimelineLive()) {
            TimelineScrollDebug.log { "inserted -> scroll: force window" }
            scrollToLatest()
            return
        }
        if (!TimelineLiveEdge.isAtLiveEdge(recyclerView, layoutManager) || !isTimelineLive()) {
            TimelineScrollDebug.log {
                "inserted -> skip: atLiveEdge=${TimelineLiveEdge.isAtLiveEdge(recyclerView, layoutManager)} live=${isTimelineLive()}"
            }
            return
        }
        val firstNewItem = tryOrNull {
            timelineEventController.adapter.getModelAtPosition(position)
        } as? ItemWithEvents
        if (firstNewItem == null) {
            TimelineScrollDebug.log { "inserted -> skip: model at $position has no events" }
            return
        }
        val firstNewItemIds = firstNewItem.getEventIds().firstOrNull()
        if (firstNewItemIds == null) {
            TimelineScrollDebug.log { "inserted -> skip: model at $position has empty event ids" }
            return
        }
        val indexOfFirstNewItem = newTimelineEventIds.indexOf(firstNewItemIds)
        if (indexOfFirstNewItem != -1) {
            while (newTimelineEventIds.lastOrNull() != firstNewItemIds) {
                newTimelineEventIds.removeLastOrNull()
            }
            TimelineScrollDebug.log { "inserted -> scroll: $firstNewItemIds is new" }
            scrollToLatest()
        } else {
            TimelineScrollDebug.log { "inserted -> skip: $firstNewItemIds not tracked as new" }
        }
    }

    // The typing spacer always sits at position 0, so a new message lands just above it.
    private fun isBelowNewestEvent(position: Int): Boolean {
        val adapter = timelineEventController.adapter
        return (0 until position).none { tryOrNull { adapter.getModelAtPosition(it) } is ItemWithEvents }
    }

    private fun scrollToLatest() {
        onScrollToLatest()
        // scrollToPosition() only brings the row into view, keeping the reader's partial scroll offset.
        layoutManager.scrollToPositionWithOffset(0, 0)
        if (DebugLog.enabled) {
            recyclerView.post { TimelineScrollDebug.log { "scrollToLatest +1 frame ${debugState()}" } }
            recyclerView.postDelayed({ TimelineScrollDebug.log { "scrollToLatest +500ms ${debugState()}" } }, 500L)
        }
    }

    companion object {
        private const val FORCE_SCROLL_WINDOW_MS = 1500L
    }
}
