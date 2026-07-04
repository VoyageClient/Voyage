/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.room.prune

import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.LocalEcho
import org.matrix.android.sdk.api.session.events.model.UnsignedData
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.mapper.EventMapper
import org.matrix.android.sdk.internal.database.model.EventInsertType
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.matrix.android.sdk.internal.session.EventInsertLiveProcessor
import org.matrix.android.sdk.internal.session.room.summary.SqlRoomSummaryUpdater
import org.matrix.android.sdk.internal.session.search.index.EventIndexer
import timber.log.Timber
import javax.inject.Inject

/**
 * Listens to the database for the insertion of any redaction event.
 * As it actually deletes the content, it should be called last in the list of processors.
 */
internal class RedactionEventProcessor @Inject constructor(
        private val roomSummaryUpdater: SqlRoomSummaryUpdater,
        private val eventIndexer: EventIndexer,
) : EventInsertLiveProcessor {

    override fun shouldProcess(eventId: String, eventType: String, insertType: EventInsertType): Boolean {
        return eventType == EventType.REDACTION
    }

    override fun process(stores: SessionStores, event: Event) {
        pruneEvent(stores, event)
    }

    private fun pruneEvent(stores: SessionStores, redactionEvent: Event) {
        if (redactionEvent.redacts.isNullOrBlank()) {
            return
        }
        val roomId = redactionEvent.roomId ?: return

        // Check that we know the redaction event itself
        if (stores.event.getDbId(roomId, redactionEvent.eventId ?: "") == null) return

        val isLocalEcho = LocalEcho.isLocalEchoId(redactionEvent.eventId ?: "")
        Timber.v("Redact event for ${redactionEvent.redacts} localEcho=$isLocalEcho")

        // The target may exist only in the search index (crawled history), so don't gate on the DB row.
        eventIndexer.onEventRedacted(redactionEvent.redacts)

        val pruneDbId = stores.event.getDbId(roomId, redactionEvent.redacts) ?: return
        val eventToPrune = stores.event.getById(pruneDbId) ?: return

        val typeToPrune = eventToPrune.type
        val stateKey = eventToPrune.stateKey
        val allowedKeys = computeAllowedKeys(typeToPrune)
        when {
            allowedKeys.isNotEmpty() -> {
                val prunedContent = ContentMapper.map(eventToPrune.content)?.filterKeys { key -> allowedKeys.contains(key) }
                stores.event.updateContentOnly(pruneDbId, ContentMapper.map(prunedContent))
            }
            canPruneEventType(typeToPrune) -> {
                Timber.d("REDACTION for message ${eventToPrune.eventId}")
                val unsignedData = EventMapper.map(eventToPrune).unsignedData ?: UnsignedData(null, null)
                val modified = unsignedData.copy(redactedEvent = redactionEvent)
                stores.event.updatePruned(
                        id = pruneDbId,
                        content = ContentMapper.map(emptyMap()),
                        unsignedData = MoshiProvider.providesMoshi().adapter(UnsignedData::class.java).toJson(modified),
                )
                val rootThreadId = eventToPrune.rootThreadEventId
                if (rootThreadId != null && !isLocalEcho) {
                    val remaining = stores.event.countThreadReplies(eventToPrune.roomId, rootThreadId)
                    stores.event.getDbId(eventToPrune.roomId, rootThreadId)?.let { rootDbId ->
                        if (remaining > 0) {
                            val latestTimelineId = stores.timelineEvent.latestThreadReplyId(eventToPrune.roomId, rootThreadId)
                            stores.event.markEventAsRoot(rootDbId, remaining, latestTimelineId)
                        } else {
                            // last in-thread message redacted: the root is no longer a thread root
                            stores.event.unmarkEventAsRoot(rootDbId)
                        }
                    }
                }
            }
            typeToPrune == EventType.REACTION -> {
                // Reactions are aggregated (the relations processor needs the key to remove the chip), so
                // we keep their content but still flag them redacted, then refresh the preview right away —
                // otherwise an undone reaction lingers as the room-list preview until the next message.
                val unsignedData = EventMapper.map(eventToPrune).unsignedData ?: UnsignedData(null, null)
                val modified = unsignedData.copy(redactedEvent = redactionEvent)
                stores.event.updateUnsignedData(pruneDbId, MoshiProvider.providesMoshi().adapter(UnsignedData::class.java).toJson(modified))
                roomSummaryUpdater.refreshLatestPreviewableEvent(stores, eventToPrune.roomId)
            }
            else -> {
                Timber.w("Not pruning event (type $typeToPrune)")
            }
        }
        if (typeToPrune == EventType.STATE_ROOM_MEMBER && stateKey != null) {
            stores.timelineEvent.clearSenderInfoForMembershipEvent(eventToPrune.eventId)
        }
    }

    private fun computeAllowedKeys(type: String): List<String> {
        // Add filtered content, allowed keys in content depends on the event type
        return when (type) {
            EventType.STATE_ROOM_MEMBER -> listOf("membership")
            EventType.STATE_ROOM_CREATE -> listOf("creator")
            EventType.STATE_ROOM_JOIN_RULES -> listOf("join_rule")
            EventType.STATE_ROOM_POWER_LEVELS -> listOf(
                    "users",
                    "users_default",
                    "events",
                    "events_default",
                    "state_default",
                    "ban",
                    "kick",
                    "redact",
                    "invite"
            )
            EventType.STATE_ROOM_ALIASES -> listOf("aliases")
            EventType.STATE_ROOM_CANONICAL_ALIAS -> listOf("alias")
            EventType.FEEDBACK -> listOf("type", "target_event_id")
            else -> emptyList()
        }
    }

    private fun canPruneEventType(eventType: String): Boolean {
        return when {
            EventType.isCallEvent(eventType) -> false
            EventType.isVerificationEvent(eventType) -> false
            eventType == EventType.STATE_ROOM_WIDGET_LEGACY ||
                    eventType == EventType.STATE_ROOM_WIDGET ||
                    eventType == EventType.STATE_ROOM_NAME ||
                    eventType == EventType.STATE_ROOM_TOPIC ||
                    eventType == EventType.STATE_ROOM_AVATAR ||
                    eventType == EventType.STATE_ROOM_THIRD_PARTY_INVITE ||
                    eventType == EventType.STATE_ROOM_GUEST_ACCESS ||
                    eventType == EventType.STATE_SPACE_CHILD ||
                    eventType == EventType.STATE_SPACE_PARENT ||
                    eventType == EventType.STATE_ROOM_TOMBSTONE ||
                    eventType == EventType.STATE_ROOM_HISTORY_VISIBILITY ||
                    eventType == EventType.STATE_ROOM_RELATED_GROUPS ||
                    eventType == EventType.STATE_ROOM_PINNED_EVENT ||
                    eventType == EventType.STATE_ROOM_ENCRYPTION ||
                    eventType == EventType.STATE_ROOM_SERVER_ACL ||
                    eventType == EventType.REACTION -> false
            else -> true
        }
    }
}
