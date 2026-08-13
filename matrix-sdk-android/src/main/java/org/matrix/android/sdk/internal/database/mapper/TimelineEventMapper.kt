/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.database.mapper

import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.session.SessionScope
import javax.inject.Inject

// Session-scoped so the memo below survives across room opens. Unscoped, DefaultRoomGetter built a fresh
// mapper per getRoom() call, so every room open re-parsed every event's JSON from cold (~110ms for 74 events).
@SessionScope
internal class TimelineEventMapper @Inject constructor(private val readReceiptsSummaryMapper: ReadReceiptsSummaryMapper) {

    // Parsing an event's JSON (2-3 Moshi passes) dominates timeline snapshot mapping (~1.5ms/event on
    // device), and the live chunk re-maps in full on every sync. Memoize the mapped TimelineEvent per
    // eventId, guarded by a fingerprint over every entity field that feeds the mapping — decryption,
    // edits, redactions and reactions all change the fingerprint, so a stale hit can only come from a
    // 64-bit hash collision. Read receipts are deliberately outside the memo, see below.
    private class MemoEntry(val fingerprint: Long, val base: TimelineEvent) {
        @Volatile var withReceipts: TimelineEvent? = null
    }

    private val memo = object : LinkedHashMap<String, MemoEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MemoEntry>) = size > MEMO_MAX_SIZE
    }

    fun map(timelineEventEntity: TimelineEventEntity, buildReadReceipts: Boolean = true): TimelineEvent {
        val fingerprint = fingerprintOf(timelineEventEntity)
        val entry = synchronized(memo) {
            memo[timelineEventEntity.eventId]?.takeIf { it.fingerprint == fingerprint }
        } ?: MemoEntry(fingerprint, doMap(timelineEventEntity)).also {
            synchronized(memo) { memo[timelineEventEntity.eventId] = it }
        }
        if (!buildReadReceipts) return entry.base
        // Receipts resolve against member rows, which the fingerprint cannot see, so they are re-read on
        // every call: cached alongside the base event, a receipt mapped before the room's members landed
        // would stay a bare user id for the whole session. Reuse the last instance while they are equal —
        // rebuildSnapshot detects a no-op rebuild by reference.
        val readReceipts = timelineEventEntity.readReceipts
                ?.let { readReceiptsSummaryMapper.map(it) }
                // Sort before dedup: a user can have several receipt rows (threads) and distinctBy
                // keeps the first, so dedup-first would surface an arbitrary row's timestamp.
                ?.sortedByDescending { it.originServerTs }
                ?.distinctBy { it.roomMember }
                .orEmpty()
        entry.withReceipts?.takeIf { it.readReceipts == readReceipts }?.let { return it }
        val event = if (readReceipts.isEmpty()) entry.base else entry.base.copy(readReceipts = readReceipts)
        entry.withReceipts = event
        return event
    }

    private fun doMap(timelineEventEntity: TimelineEventEntity): TimelineEvent {
        return TimelineEvent(
                root = timelineEventEntity.root?.asDomain()
                        ?: Event("", timelineEventEntity.eventId),
                eventId = timelineEventEntity.eventId,
                annotations = timelineEventEntity.annotations?.asDomain(),
                localId = timelineEventEntity.localId,
                displayIndex = timelineEventEntity.displayIndex,
                senderInfo = SenderInfo(
                        userId = timelineEventEntity.root?.sender ?: "",
                        displayName = timelineEventEntity.senderName,
                        isUniqueDisplayName = timelineEventEntity.isUniqueDisplayName,
                        avatarUrl = timelineEventEntity.senderAvatar
                ),
                ownedByThreadChunk = timelineEventEntity.ownedByThreadChunk,
        )
    }

    private fun fingerprintOf(e: TimelineEventEntity): Long {
        var h = 1L
        fun add(v: Any?) {
            // -1 for null so it differs from "" (whose hashCode is 0) — sender fields use "" vs null
            // to mean known-empty vs unknown.
            h = 31 * h + (v?.hashCode() ?: -1)
        }
        add(e.eventId)
        add(e.localId)
        add(e.displayIndex)
        add(e.senderName)
        add(e.senderAvatar)
        add(e.isUniqueDisplayName)
        add(e.senderMembershipEventId)
        add(e.ownedByThreadChunk)
        e.root?.let { r ->
            add(r.type)
            add(r.content)
            add(r.prevContent)
            add(r.unsignedData)
            add(r.decryptionResultJson)
            add(r.decryptionErrorCode)
            add(r.decryptionErrorReason)
            add(r.sendState)
            add(r.sendStateDetails)
            add(r.originServerTs)
            add(r.ageLocalTs)
            add(r.isVerificationStateDirty)
            add(r.isRootThread)
            add(r.numberOfThreads)
            add(r.threadNotificationState)
            add(r.threadSummaryLatestMessage?.eventId)
            add(r.threadSummaryLatestMessage?.root?.content)
        }
        e.annotations?.let { a ->
            a.reactionsSummary.forEach {
                add(it.key)
                add(it.count)
                add(it.addedByMe)
                add(it.sourceEvents.size)
                add(it.sourceLocalEcho.size)
            }
            a.editSummary?.editions?.forEach {
                add(it.eventId)
                add(it.timestamp)
                add(it.isLocalEcho)
            }
            add(a.referencesSummaryEntity?.content)
            a.pollResponseSummary?.let {
                add(it.aggregatedContent)
                add(it.closedTime)
                add(it.nbOptions)
            }
            a.liveLocationShareAggregatedSummary?.let {
                add(it.isActive)
                add(it.endOfLiveTimestampMillis)
                add(it.lastLocationContent)
            }
        }
        return h
    }

    private companion object {
        private const val MEMO_MAX_SIZE = 3000
    }
}
