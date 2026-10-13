/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import android.os.Handler
import android.os.Looper
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

    @Volatile var canAddReaction: Boolean = false

    private val handler = Handler(Looper.getMainLooper())

    var onRequestBuild: (() -> Unit)? = null
    private val showAllReactionsByEvent = HashSet<String>()
    private val eventsRequestingBuild = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private class PendingToggle(val addedByMe: Boolean, val expiresAt: Long)

    // Hold taps across rebinds while the database catches up; expire unconfirmed changes after a failed send.
    private val pendingToggles = ConcurrentHashMap<Pair<String, String>, PendingToggle>()

    fun needsRebuild(event: TimelineEvent): Boolean {
        return eventsRequestingBuild.remove(event.eventId)
    }

    fun onReactionToggled(eventId: String, key: String, addedByMe: Boolean) {
        if (!canAddReaction) return
        val pending = PendingToggle(addedByMe, SystemClock.elapsedRealtime() + PENDING_TOGGLE_TTL_MS)
        pendingToggles[eventId to key] = pending
        handler.postDelayed({
            if (pendingToggles.remove(eventId to key, pending)) onRequestBuild(eventId)
        }, PENDING_TOGGLE_TTL_MS)
        onRequestBuild(eventId)
    }

    fun clearPendingToggles() {
        val eventIds = pendingToggles.keys.map { it.first }
        pendingToggles.clear()
        eventIds.forEach { onRequestBuild(it) }
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
