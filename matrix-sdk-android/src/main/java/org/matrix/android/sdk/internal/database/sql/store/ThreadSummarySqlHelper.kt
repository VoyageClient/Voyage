/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.session.room.threads.model.ThreadSummaryUpdateType
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.mapper.toEntity
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.EventInsertType
import org.matrix.android.sdk.internal.database.model.threads.ThreadSummaryEntity
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.events.getFixedRoomMemberContent
import org.matrix.android.sdk.internal.session.room.timeline.RoomSummaryEventDecryptor
import javax.inject.Inject

/**
 * SQL port of the Realm `ThreadSummaryEntity.createOrUpdate` helper. Builds/updates the per-room
 * thread summaries (MSC3440). Root/latest thread events are inserted into the `event` table and the
 * summary references them by db id. Encrypted root/latest events are queued for async decryption
 * (via [RoomSummaryEventDecryptor]) so their thread-list previews show plaintext, rather than blocking
 * the sync transaction on crypto.
 */
internal class ThreadSummarySqlHelper @Inject constructor(
        @UserId private val userId: String,
        private val summaryEventDecryptor: RoomSummaryEventDecryptor,
) {

    fun createOrUpdate(
            type: ThreadSummaryUpdateType,
            stores: SessionStores,
            roomId: String,
            threadEventEntity: EventEntity? = null,
            rootThreadEvent: Event? = null,
            roomMemberContentsByUser: HashMap<String, RoomMemberContent?>,
            currentTimeMillis: Long,
    ): ThreadSummaryEntity? {
        return when (type) {
            ThreadSummaryUpdateType.REPLACE -> handleReplace(stores, roomId, rootThreadEvent, roomMemberContentsByUser, currentTimeMillis)
            ThreadSummaryUpdateType.ADD -> handleAdd(stores, roomId, threadEventEntity, roomMemberContentsByUser)
        }
    }

    private fun handleReplace(
            stores: SessionStores,
            roomId: String,
            rootThreadEvent: Event?,
            roomMemberContentsByUser: HashMap<String, RoomMemberContent?>,
            currentTimeMillis: Long,
    ): ThreadSummaryEntity? {
        rootThreadEvent ?: return null
        val rootEventId = rootThreadEvent.eventId ?: return null
        val rootSenderId = rootThreadEvent.senderId ?: return null
        val latestThread = rootThreadEvent.unsignedData?.relations?.latestThread ?: return null
        val numberOfThreads = latestThread.count ?: return null
        if (numberOfThreads <= 0) return null

        val summary = stores.threadSummary.getByRootEventId(roomId, rootEventId) ?: ThreadSummaryEntity(rootThreadEventId = rootEventId)

        val rootEntity = insertOrGetEvent(stores, roomId, rootThreadEvent, currentTimeMillis)
        val latestEvent = latestThread.event
        val latestEntity = latestEvent?.let {
            it.senderId?.let { sender -> addSenderState(stores, roomMemberContentsByUser, roomId, sender) }
            insertOrGetEvent(stores, roomId, it, currentTimeMillis)
        }
        // These come straight from the server bundle (never through the live decryptor), so queue any
        // encrypted ones for async decryption — their thread-list previews then show plaintext.
        requestDecryptionIfNeeded(roomId, rootThreadEvent)
        latestEvent?.let { requestDecryptionIfNeeded(roomId, it) }
        val isUserParticipating = latestThread.isUserParticipating == true ||
                rootSenderId == userId
        addSenderState(stores, roomMemberContentsByUser, roomId, rootSenderId)

        updateThreadSummary(summary, rootEntity, numberOfThreads, latestEntity, isUserParticipating, roomMemberContentsByUser)
        persist(stores, roomId, summary, rootEntity, latestEntity)
        stores.threadSummary.upsertPage(roomId)
        return summary
    }

    private fun handleAdd(
            stores: SessionStores,
            roomId: String,
            threadEventEntity: EventEntity?,
            roomMemberContentsByUser: HashMap<String, RoomMemberContent?>,
    ): ThreadSummaryEntity? {
        val rootThreadEventId = threadEventEntity?.rootThreadEventId ?: return null
        val existing = stores.threadSummary.getByRootEventId(roomId, rootThreadEventId)
        if (existing != null) {
            updateThreadSummaryLatestEvent(existing, threadEventEntity, roomMemberContentsByUser)
            existing.numberOfThreads++
            if (threadEventEntity.sender == userId) existing.isUserParticipating = true
            persist(stores, roomId, existing, existing.rootThreadEventEntity, threadEventEntity)
            return existing
        }
        // Root thread event must already be known to create a fresh summary
        val rootEntity = stores.event.getByEventIdInRoom(roomId, rootThreadEventId) ?: return null
        val summary = ThreadSummaryEntity(rootThreadEventId = rootEntity.eventId)
        updateThreadSummary(summary, rootEntity, numberOfThreads = 1, latestEntity = threadEventEntity,
                isUserParticipating = threadEventEntity.sender == userId, roomMemberContentsByUser = roomMemberContentsByUser)
        persist(stores, roomId, summary, rootEntity, threadEventEntity)
        stores.threadSummary.upsertPage(roomId)
        return summary
    }

    private fun updateThreadSummary(
            summary: ThreadSummaryEntity,
            rootEntity: EventEntity?,
            numberOfThreads: Int?,
            latestEntity: EventEntity?,
            isUserParticipating: Boolean,
            roomMemberContentsByUser: HashMap<String, RoomMemberContent?>,
    ) {
        rootEntity?.let {
            val member = roomMemberContentsByUser[it.sender ?: ""]
            summary.rootThreadEventEntity = it
            summary.rootThreadSenderAvatar = member?.avatarUrl
            summary.rootThreadSenderName = member?.displayName
            summary.rootThreadIsUniqueDisplayName = member?.displayName?.let { _ -> isHistoricalUnique(member, roomMemberContentsByUser) } ?: true
        }
        updateThreadSummaryLatestEvent(summary, latestEntity, roomMemberContentsByUser)
        summary.isUserParticipating = isUserParticipating
        numberOfThreads?.let { summary.numberOfThreads = it }
    }

    private fun updateThreadSummaryLatestEvent(
            summary: ThreadSummaryEntity,
            latestEntity: EventEntity?,
            roomMemberContentsByUser: HashMap<String, RoomMemberContent?>,
    ) {
        latestEntity ?: return
        val member = roomMemberContentsByUser[latestEntity.sender ?: ""]
        summary.latestThreadEventEntity = latestEntity
        summary.latestThreadSenderAvatar = member?.avatarUrl
        summary.latestThreadSenderName = member?.displayName
        summary.latestThreadIsUniqueDisplayName = member?.displayName?.let { isHistoricalUnique(member, roomMemberContentsByUser) } ?: true
    }

    private fun isHistoricalUnique(content: RoomMemberContent, byUser: Map<String, RoomMemberContent?>): Boolean =
            byUser.values.none { it != content && it?.displayName == content.displayName }

    private fun requestDecryptionIfNeeded(roomId: String, event: Event) {
        if (event.isEncrypted() && event.mxDecryptionResult == null) {
            summaryEventDecryptor.requestDecryption(event.copy(roomId = roomId))
        }
    }

    private fun insertOrGetEvent(stores: SessionStores, roomId: String, event: Event, currentTimeMillis: Long): EventEntity {
        val ageLocalTs = currentTimeMillis - (event.unsignedData?.age ?: 0)
        val entity = event.toEntity(roomId, SendState.SYNCED, ageLocalTs)
        val existing = stores.event.getDbId(roomId, entity.eventId)
        if (existing == null) {
            stores.eventInsert.insert(entity.eventId, entity.type, canBeProcessed = true, insertType = EventInsertType.PAGINATION)
            stores.event.insert(entity)
        }
        return stores.event.getByEventIdInRoom(roomId, entity.eventId) ?: entity
    }

    private fun persist(stores: SessionStores, roomId: String, summary: ThreadSummaryEntity, rootEntity: EventEntity?, latestEntity: EventEntity?) {
        val rootDbId = rootEntity?.eventId?.let { stores.event.getDbId(roomId, it) }
        val latestDbId = latestEntity?.eventId?.let { stores.event.getDbId(roomId, it) }
        stores.threadSummary.upsert(roomId, summary, rootDbId, latestDbId)
    }

    /** If we don't have any new state on this user, get it from db. */
    private fun addSenderState(stores: SessionStores, byUser: HashMap<String, RoomMemberContent?>, roomId: String, senderId: String) {
        byUser.getOrPut(senderId) {
            stores.currentStateEvent.getOne(roomId, EventType.STATE_ROOM_MEMBER, senderId)
                    ?.root?.asDomain()?.getFixedRoomMemberContent()
        }
    }
}
