/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.delayedevents

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.session.delayedevents.DelayedEvent
import org.matrix.android.sdk.api.session.delayedevents.DelayedEventService
import org.matrix.android.sdk.api.session.delayedevents.PowerLevelRestore
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.util.TextContent
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.delayedevents.PowerLevelRestorePlanner.Action
import org.matrix.android.sdk.internal.session.delayedevents.PowerLevelRestorePlanner.Pending
import org.matrix.android.sdk.internal.session.delayedevents.PowerLevelRestorePlanner.Restore
import org.matrix.android.sdk.internal.session.room.send.LocalEchoEventFactory
import org.matrix.android.sdk.internal.session.room.state.toSafePowerLevelsContentDict
import org.matrix.android.sdk.internal.util.time.Clock
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

private const val MAX_LIST_PAGES = 20
private const val MEMBERSHIP_LEAVE = "leave"
private const val MEMBERSHIP_INVITE = "invite"
private const val REINVITE_AFTER_UNBAN_MS = 1_000L

@SessionScope
internal class DefaultDelayedEventService @Inject constructor(
        private val delayedEventsAPI: DelayedEventsAPI,
        private val globalErrorReceiver: GlobalErrorReceiver,
        private val localEchoEventFactory: LocalEchoEventFactory,
        private val clock: Clock,
) : DelayedEventService {

    private val powerLevelsMutex = Mutex()

    override suspend fun scheduleEvent(roomId: String, eventType: String, content: Content, delayMs: Long, stateKey: String?): String {
        val txnId = UUID.randomUUID().toString()
        return executeRequest(globalErrorReceiver) {
            if (stateKey == null) {
                delayedEventsAPI.sendDelayedEvent(roomId, eventType, txnId, delayMs, content)
            } else {
                delayedEventsAPI.sendDelayedStateEvent(roomId, eventType, stateKey, delayMs, content)
            }
        }.delayId
    }

    override suspend fun scheduleTextMessage(
            roomId: String,
            text: CharSequence,
            formattedText: String?,
            autoMarkdown: Boolean,
            delayMs: Long,
    ): String {
        val event = if (formattedText != null) {
            localEchoEventFactory.createFormattedTextEvent(roomId, TextContent(text.toString(), formattedText), MessageType.MSGTYPE_TEXT, mentionSource = text)
        } else {
            localEchoEventFactory.createTextEvent(roomId, MessageType.MSGTYPE_TEXT, text, autoMarkdown)
        }
        return scheduleEvent(roomId, EventType.MESSAGE, event.content.orEmpty(), delayMs)
    }

    override suspend fun getDelayedEvents(): List<DelayedEvent> {
        val result = mutableListOf<DelayedEvent>()
        var from: String? = null
        repeat(MAX_LIST_PAGES) {
            val page = executeRequest(globalErrorReceiver) { delayedEventsAPI.getDelayedEvents(from) }
            page.delayedEvents.orEmpty().mapNotNullTo(result) { it.toDelayedEvent() }
            from = page.nextBatch ?: return result
        }
        return result
    }

    override suspend fun cancel(delayId: String) {
        executeRequest(globalErrorReceiver) { delayedEventsAPI.updateDelayedEvent(delayId, UpdateDelayedEventBody.CANCEL) }
    }

    override suspend fun sendNow(delayId: String) {
        executeRequest(globalErrorReceiver) { delayedEventsAPI.updateDelayedEvent(delayId, UpdateDelayedEventBody.SEND) }
    }

    override suspend fun schedulePowerLevelRestore(roomId: String, currentContent: Content, userId: String, level: Int?, delayMs: Long): String {
        val users = currentContent.powerLevelUsers()
        val added = Restore(delayId = null, sendTs = clock.epochMillis() + delayMs, delta = mapOf(userId to level))
        return powerLevelsMutex.withLock {
            rebase(roomId, users, currentContent, added)
        } ?: error("Restore was not scheduled")
    }

    override suspend fun cancelPowerLevelRestore(roomId: String, currentContent: Content, userId: String) {
        val users = currentContent.powerLevelUsers()
        powerLevelsMutex.withLock {
            rebase(roomId, users, currentContent, added = null, dropped = setOf(userId))
        }
    }

    override fun getPowerLevelRestores(roomId: String, currentContent: Content, delayedEvents: List<DelayedEvent>): List<PowerLevelRestore> {
        val pending = delayedEvents.pendingPowerLevels(roomId)
        return PowerLevelRestorePlanner.extractRestores(currentContent.powerLevelUsers(), pending).flatMap { restore ->
            restore.delta.map { (userId, level) -> PowerLevelRestore(restore.delayId.orEmpty(), userId, level, restore.sendTs) }
        }
    }

    override suspend fun scheduleUnban(roomId: String, userId: String, delayMs: Long, reinvite: Boolean) {
        scheduleEvent(roomId, EventType.STATE_ROOM_MEMBER, mapOf("membership" to MEMBERSHIP_LEAVE), delayMs, stateKey = userId)
        if (reinvite) {
            // A banned user cannot be invited, and events due at the same moment may go out in any order.
            scheduleEvent(roomId, EventType.STATE_ROOM_MEMBER, mapOf("membership" to MEMBERSHIP_INVITE), delayMs + REINVITE_AFTER_UNBAN_MS, stateKey = userId)
        }
    }

    override fun getPendingUnban(roomId: String, userId: String, delayedEvents: List<DelayedEvent>): DelayedEvent? {
        return delayedEvents.pendingMembershipChanges(roomId, userId)
                .filter { it.content["membership"] == MEMBERSHIP_LEAVE }
                .minByOrNull { it.sendTs }
    }

    /**
     * Called after we changed [userId]'s membership in [roomId]: a membership change we scheduled earlier
     * would otherwise still fire on top of it.
     */
    suspend fun onMembershipChanged(roomId: String, userId: String) {
        getDelayedEvents().pendingMembershipChanges(roomId, userId).forEach { cancelIfPending(it.delayId) }
    }

    /**
     * Called after we changed the room's power levels from [oldUsers] to [newContent], so that the restores
     * we hold there keep composing with it.
     */
    suspend fun onPowerLevelsChanged(roomId: String, oldUsers: Map<String, Int>, newContent: Content) {
        powerLevelsMutex.withLock {
            rebase(roomId, oldUsers, newContent, added = null)
        }
    }

    /** @return the delay id of [added], when given */
    private suspend fun rebase(
            roomId: String,
            oldUsers: Map<String, Int>,
            newContent: Content,
            added: Restore?,
            dropped: Set<String> = emptySet(),
    ): String? {
        val pending = getDelayedEvents().pendingPowerLevels(roomId)
        if (pending.isEmpty() && added == null) return null
        val newUsers = newContent.powerLevelUsers()
        var addedDelayId: String? = null
        PowerLevelRestorePlanner.plan(oldUsers, newUsers, pending, added, dropped).forEach { action ->
            when (action) {
                is Action.Cancel -> cancelIfPending(action.delayId)
                is Action.Schedule -> {
                    if (action.replacing != null && !cancelIfPending(action.replacing)) return@forEach
                    val content = (newContent + ("users" to action.users)).toSafePowerLevelsContentDict()
                    val delayMs = (action.sendTs - clock.epochMillis()).coerceAtLeast(1)
                    val delayId = scheduleEvent(roomId, EventType.STATE_ROOM_POWER_LEVELS, content, delayMs, stateKey = "")
                    if (action.replacing == null) addedDelayId = delayId
                }
            }
        }
        return addedDelayId
    }

    /** @return false when the server no longer holds it, i.e. it has already fired or been cancelled */
    private suspend fun cancelIfPending(delayId: String): Boolean {
        return try {
            cancel(delayId)
            true
        } catch (failure: Failure.ServerError) {
            Timber.w("Delayed event $delayId is gone (${failure.httpCode})")
            false
        }
    }

    private fun List<DelayedEvent>.pendingPowerLevels(roomId: String): List<Pending> {
        return filter { it.roomId == roomId && it.type == EventType.STATE_ROOM_POWER_LEVELS && it.stateKey == "" }
                .map { Pending(it.delayId, it.sendTs, it.content.powerLevelUsers()) }
                .sortedBy { it.sendTs }
    }

    private fun List<DelayedEvent>.pendingMembershipChanges(roomId: String, userId: String): List<DelayedEvent> {
        return filter { it.roomId == roomId && it.type == EventType.STATE_ROOM_MEMBER && it.stateKey == userId }
    }

    private fun DelayedEventJson.toDelayedEvent(): DelayedEvent? {
        return DelayedEvent(
                delayId = delayId ?: return null,
                roomId = roomId ?: return null,
                type = type ?: return null,
                stateKey = stateKey,
                content = content.orEmpty(),
                delayMs = delayMs ?: delayLegacy ?: return null,
                delayedSinceTs = delayedSinceTs ?: runningSinceLegacy ?: return null,
        )
    }
}

internal fun Content.powerLevelUsers(): Map<String, Int> {
    val users = this["users"] as? Map<*, *> ?: return emptyMap()
    return buildMap {
        users.forEach { (userId, level) ->
            if (userId is String && level is Number) put(userId, level.toInt())
        }
    }
}
