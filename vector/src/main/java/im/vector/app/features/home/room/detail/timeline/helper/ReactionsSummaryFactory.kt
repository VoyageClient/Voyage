/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import android.os.SystemClock
import dagger.hilt.android.scopes.ActivityScoped
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.item.ReactionInfoData
import im.vector.app.features.home.room.detail.timeline.item.ReactionsSummaryData
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@ActivityScoped
class ReactionsSummaryFactory @Inject constructor() {

    /** Fed by the timeline controller from its partial state; false hides the add-reaction button. */
    @Volatile var canAddReaction: Boolean = true

    var onRequestBuild: (() -> Unit)? = null
    private val showAllReactionsByEvent = HashSet<String>()
    private val eventsRequestingBuild = HashSet<String>()

    private class PendingToggle(val addedByMe: Boolean, val expiresAt: Long)

    // The aggregation confirming a tap can land seconds later when the DB is busy; a rebind in between would
    // flip the pill back. Hold the tapped state until the summary agrees, or until a failed send lets it lapse.
    private val pendingToggles = ConcurrentHashMap<Pair<String, String>, PendingToggle>()

    fun needsRebuild(event: TimelineEvent): Boolean {
        return eventsRequestingBuild.remove(event.eventId)
    }

    fun onReactionToggled(eventId: String, key: String, addedByMe: Boolean) {
        pendingToggles[eventId to key] = PendingToggle(addedByMe, SystemClock.elapsedRealtime() + PENDING_TOGGLE_TTL_MS)
        onRequestBuild(eventId)
    }

    fun create(event: TimelineEvent): ReactionsSummaryData {
        val eventId = event.eventId
        val showAllStates = showAllReactionsByEvent.contains(eventId)
        val reactions = applyPendingToggles(
                eventId,
                event.annotations?.reactionsSummary?.map { ReactionInfoData(it.key, it.count, it.addedByMe) }
        )
        return ReactionsSummaryData(
                reactions = reactions,
                showAll = showAllStates,
                canAddReaction = canAddReaction,
        )
    }

    fun onAddMoreClicked(callback: TimelineEventController.Callback?, event: TimelineEvent) {
        callback?.onAddMoreReaction(event)
    }

    fun onShowMoreClicked(eventId: String) {
        showAllReactionsByEvent.add(eventId)
        onRequestBuild(eventId)
    }

    fun onShowLessClicked(eventId: String) {
        showAllReactionsByEvent.remove(eventId)
        onRequestBuild(eventId)
    }

    private fun onRequestBuild(eventId: String) {
        eventsRequestingBuild.add(eventId)
        onRequestBuild?.invoke()
    }

    private fun applyPendingToggles(eventId: String, reactions: List<ReactionInfoData>?): List<ReactionInfoData>? {
        if (pendingToggles.isEmpty()) return reactions
        val now = SystemClock.elapsedRealtime()
        var result = reactions
        pendingToggles.entries.filter { it.key.first == eventId }.forEach { (id, pending) ->
            val key = id.second
            val current = result?.firstOrNull { it.key == key }
            if (now > pending.expiresAt || (current?.addedByMe ?: false) == pending.addedByMe) {
                pendingToggles.remove(id, pending)
                return@forEach
            }
            val all = result.orEmpty()
            result = when {
                pending.addedByMe && current == null -> all + ReactionInfoData(key, 1, true)
                pending.addedByMe -> all.map { if (it.key == key) it.copy(count = it.count + 1, addedByMe = true) else it }
                current != null && current.count > 1 -> all.map { if (it.key == key) it.copy(count = it.count - 1, addedByMe = false) else it }
                else -> all.filter { it.key != key }
            }
        }
        return result
    }

    private companion object {
        const val PENDING_TOGGLE_TTL_MS = 15_000L
    }
}
