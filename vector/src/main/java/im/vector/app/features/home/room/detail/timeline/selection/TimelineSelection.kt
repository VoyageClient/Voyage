/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.selection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.isRedacted
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent

/**
 * Multi-selected timeline messages. Selection mode is on while this is non-empty, or [held] open.
 * @param isRevealed whether a redacted event is currently showing its recovered content
 */
class TimelineSelection(private val isRevealed: (TimelineEvent) -> Boolean = { false }) {

    private val _selected = MutableStateFlow<Map<String, TimelineEvent>>(emptyMap())
    val selected: StateFlow<Map<String, TimelineEvent>> = _selected.asStateFlow()

    private val _held = MutableStateFlow(false)

    /** Set for the length of a range drag, so deselecting everything mid-drag doesn't end selection under the finger. */
    val held: StateFlow<Boolean> = _held.asStateFlow()

    val isActive: Boolean get() = _selected.value.isNotEmpty() || _held.value

    fun hold(held: Boolean) {
        _held.value = held
    }

    operator fun contains(eventId: String): Boolean = eventId in _selected.value

    fun toggle(event: TimelineEvent) {
        val current = _selected.value
        _selected.value = if (event.eventId in current) current - event.eventId else current + (event.eventId to event)
    }

    /**
     * Sets every event of [range] to [select] on top of [base] (the selection when the drag started), so
     * rows the drag moves back off return to how they were.
     */
    fun applyRange(base: Map<String, TimelineEvent>, range: List<TimelineEvent>, select: Boolean) {
        val result = LinkedHashMap(base)
        range.forEach { event ->
            if (select) result.putIfAbsent(event.eventId, event) else result.remove(event.eventId)
        }
        _selected.value = result
    }

    fun clear() {
        _held.value = false
        _selected.value = emptyMap()
    }

    /** Refreshes held events from [latest] (edits, decryption) and drops ones no longer selectable. */
    fun refresh(latest: (String) -> TimelineEvent?) {
        val current = _selected.value
        if (current.isEmpty()) return
        val updated = LinkedHashMap<String, TimelineEvent>()
        current.forEach { (eventId, previous) ->
            val event = latest(eventId) ?: previous
            if (canSelect(event)) updated[eventId] = event
        }
        if (updated != current) _selected.value = updated
    }

    fun canSelect(event: TimelineEvent): Boolean = isSelectable(event, revealed = isRevealed(event))

    companion object {
        /**
         * Messages and stickers: polls and undecryptable events can be neither copied nor forwarded. A
         * deleted one only while its recovered content is [revealed], which is what copy and forward use.
         */
        fun isSelectable(event: TimelineEvent, revealed: Boolean = false): Boolean =
                event.root.sendState == SendState.SYNCED &&
                        (!event.root.isRedacted() || revealed) &&
                        event.root.getClearType().let { it == EventType.MESSAGE || it == EventType.STICKER }

        /** Oldest first, matching how they read in the timeline. */
        fun inTimelineOrder(events: Collection<TimelineEvent>): List<TimelineEvent> =
                events.sortedWith(compareBy<TimelineEvent> { it.root.originServerTs ?: 0L }.thenBy { it.seq })
    }
}
