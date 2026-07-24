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
import org.matrix.android.sdk.api.extensions.tryOrNull
import java.util.concurrent.CopyOnWriteArrayList

class ScrollOnNewMessageCallback(
        recyclerView: RecyclerView,
        private val layoutManager: LinearLayoutManager,
        private val timelineEventController: TimelineEventController
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
    }

    fun forceScrollOnNextUpdate() {
        // A window rather than a one-shot: a jump-to-bottom restarts the timeline, and between the
        // press and the live-edge list replacement several unrelated small diffs can land — a
        // one-shot flag was consumed by the first of those, leaving the actual replacement
        // unscrolled (a "random page" until something else moved the viewport).
        forceScrollUntilMs = SystemClock.uptimeMillis() + FORCE_SCROLL_WINDOW_MS
    }

    override fun onInserted(position: Int, count: Int) {
        if (position != 0) {
            return
        }
        if (SystemClock.uptimeMillis() < forceScrollUntilMs) {
            layoutManager.scrollToPosition(0)
            return
        }
        if (layoutManager.findFirstVisibleItemPosition() > 1) {
            return
        }
        val firstNewItem = tryOrNull {
            timelineEventController.adapter.getModelAtPosition(position)
        } as? ItemWithEvents ?: return
        val firstNewItemIds = firstNewItem.getEventIds().firstOrNull() ?: return
        val indexOfFirstNewItem = newTimelineEventIds.indexOf(firstNewItemIds)
        if (indexOfFirstNewItem != -1) {
            while (newTimelineEventIds.lastOrNull() != firstNewItemIds) {
                newTimelineEventIds.removeLastOrNull()
            }
            layoutManager.scrollToPosition(0)
        }
    }

    companion object {
        private const val FORCE_SCROLL_WINDOW_MS = 1500L
    }
}
