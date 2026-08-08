/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import androidx.recyclerview.widget.DiffUtil
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent

class TimelineEventDiffUtilCallback(
        private val oldList: List<TimelineEvent>,
        private val newList: List<TimelineEvent>
) : DiffUtil.Callback() {

    override fun getOldListSize(): Int {
        return oldList.size
    }

    override fun getNewListSize(): Int {
        return newList.size
    }

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        val oldItem = oldList[oldItemPosition]
        val newItem = newList[newItemPosition]
        // Not localId: a local echo and the synced event that replaces it have different localIds but
        // the same stable id, so they are treated as one item (in-place rebind, no remove/insert flash).
        return oldItem.timelineStableId() == newItem.timelineStableId()
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        val oldItem = oldList[oldItemPosition]
        val newItem = newList[newItemPosition]
        // sendState is a body var outside Event's data-class equals, so it has to be compared
        // explicitly — otherwise a SENDING→UNDELIVERED flip is invisible to the diff and the
        // failure footer only appears once the room is reopened.
        if (oldItem.root.sendState != newItem.root.sendState) return false
        // The SDK memoizes mapped events, so unchanged events are the same instance — the reference
        // check skips the deep data-class equals (content maps etc.) for almost every row.
        return oldItem === newItem || oldItem == newItem
    }
}
