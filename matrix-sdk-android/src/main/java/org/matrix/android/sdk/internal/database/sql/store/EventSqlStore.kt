/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.api.session.crypto.model.OlmDecryptionResult
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.UnsignedData
import org.matrix.android.sdk.api.session.events.model.isRedacted
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.session.threads.ThreadNotificationState
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.Event as EventRow

/**
 * SQL access for the `event` table, reusing [EventEntity] as an unmanaged DTO so the existing
 * EventMapper stays untouched. Realm does not dedup events by eventId, so rows carry a synthetic id.
 */
internal class EventSqlStore(private val database: SessionSqlDatabase) {

    private val queries get() = database.eventQueries

    fun insert(entity: EventEntity): Long {
        queries.insert(
                event_id = entity.eventId,
                room_id = entity.roomId,
                type = entity.type,
                content = entity.content,
                prev_content = entity.prevContent,
                is_useless = entity.isUseless.toLong(),
                state_key = entity.stateKey,
                origin_server_ts = entity.originServerTs,
                sender = entity.sender,
                send_state_details = entity.sendStateDetails,
                age = entity.age,
                unsigned_data = entity.unsignedData,
                redacts = entity.redacts,
                decryption_result_json = entity.decryptionResultJson,
                age_local_ts = entity.ageLocalTs,
                is_root_thread = entity.isRootThread.toLong(),
                root_thread_event_id = entity.rootThreadEventId,
                number_of_threads = entity.numberOfThreads.toLong(),
                thread_summary_latest_timeline_id = null,
                is_verification_state_dirty = entity.isVerificationStateDirty?.toLong(),
                send_state_str = entity.sendState.name,
                thread_notification_state_str = entity.threadNotificationState.name,
                decryption_error_code = entity.decryptionErrorCode,
                decryption_error_reason = entity.decryptionErrorReason,
        )
        return queries.lastInsertRowId().executeAsOne()
    }

    fun getById(id: Long): EventEntity? = queries.selectById(id).executeAsOneOrNull()?.toResolvedEntity()

    fun getByEventId(eventId: String): EventEntity? = queries.selectByEventId(eventId).executeAsOneOrNull()?.toResolvedEntity()

    fun getByEventIdInRoom(roomId: String, eventId: String): EventEntity? =
            queries.selectByEventIdInRoom(roomId, eventId).executeAsOneOrNull()?.toResolvedEntity()

    // Encrypted events in a room still lacking a clear result — used to re-attempt decryption after a key
    // import, since decryption otherwise only runs at sync/insert time and never re-tries persisted UTDs.
    fun getUndecryptedEncryptedEvents(roomId: String, type: String): List<Event> =
            queries.selectUndecryptedEncryptedInRoom(roomId, type).executeAsList().map { it.toEntity().asDomain() }

    /** [toEntity] plus the thread-root preview (latest in-thread message), resolved only when set. */
    private fun EventRow.toResolvedEntity(): EventEntity = toEntity().also { entity ->
        thread_summary_latest_timeline_id?.let { entity.threadSummaryLatestMessage = resolveTimelineEvent(it) }
    }

    private fun resolveTimelineEvent(timelineId: Long): TimelineEventEntity? {
        val te = database.timelineEventQueries.selectById(timelineId).executeAsOneOrNull() ?: return null
        return TimelineEventEntity(
                localId = te.local_id,
                eventId = te.event_id,
                roomId = te.room_id,
                displayIndex = te.display_index.toInt(),
                // file-level toEntity (no further preview resolution) — the latest in-thread message is a
                // reply, never itself a thread root, so this can't recurse.
                root = te.root_event_db_id?.let { queries.selectById(it).executeAsOneOrNull()?.toEntity() },
                senderName = te.sender_name,
                isUniqueDisplayName = te.is_unique_display_name != 0L,
                senderAvatar = te.sender_avatar,
        )
    }

    fun getDbId(roomId: String, eventId: String): Long? =
            queries.selectIdByEventIdInRoom(roomId, eventId).executeAsOneOrNull()

    fun setDecryptionResult(id: Long, result: OlmDecryptionResult?, errorCode: String?, errorReason: String?, contentJson: String?) {
        queries.updateContentById(
                content = contentJson,
                decryption_result_json = result?.let { resultAdapter.toJson(it) },
                decryption_error_code = errorCode,
                decryption_error_reason = errorReason,
                id = id,
        )
    }

    fun updateEcho(entity: EventEntity) = queries.updateEchoByEventId(
            // Persist the type too: encrypting a local echo flips it to m.room.encrypted, and without this
            // the row keeps m.room.message while its content is the encrypted blob — so getClearContent()
            // (which only decrypts when the type is encrypted) returns the blob and the event renders as
            // "malformed" until the remote echo replaces it.
            type = entity.type,
            content = entity.content,
            send_state_str = entity.sendState.name,
            send_state_details = entity.sendStateDetails,
            decryption_result_json = entity.decryptionResultJson,
            decryption_error_code = entity.decryptionErrorCode,
            decryption_error_reason = entity.decryptionErrorReason,
            room_id = entity.roomId,
            event_id = entity.eventId,
    )

    fun updateSendState(roomId: String, eventId: String, sendState: org.matrix.android.sdk.api.session.room.send.SendState, details: String?) =
            queries.updateSendStateInRoom(sendState.name, details, roomId, eventId)

    fun markEventAsRoot(id: Long, numberOfThreads: Int, latestTimelineId: Long?) =
            queries.markEventAsRoot(numberOfThreads.toLong(), latestTimelineId, id)

    fun unmarkEventAsRoot(id: Long) = queries.unmarkEventAsRoot(id)

    fun updateThreadNotificationState(eventId: String, state: ThreadNotificationState) =
            queries.updateThreadNotificationStateByEventId(state.name, eventId)

    /** Distinct, non-redacted thread replies for the given root (matches the Realm helper's count). */
    fun countThreadReplies(roomId: String, rootThreadEventId: String): Int =
            queries.selectThreadRepliesForRoot(roomId, rootThreadEventId).executeAsList()
                    .count { !it.unsigned_data.toUnsignedData().isRedacted() }

    fun isUserParticipatingInThread(roomId: String, rootThreadEventId: String, senderId: String): Boolean =
            queries.selectThreadParticipation(roomId, rootThreadEventId, senderId).executeAsOneOrNull() != null

    /** Persist an on-demand decryption result (keyed by global event id) without touching content. */
    fun applyDecryptionResult(eventId: String, result: org.matrix.android.sdk.api.session.crypto.model.MXEventDecryptionResult) {
        val olm = OlmDecryptionResult(
                payload = result.clearEvent,
                senderKey = result.senderCurve25519Key,
                keysClaimed = result.claimedEd25519Key?.let { mapOf("ed25519" to it) },
                forwardingCurve25519KeyChain = result.forwardingCurve25519KeyChain,
                verificationState = result.messageVerificationState,
        )
        queries.updateDecryptionResultByEventId(resultAdapter.toJson(olm), eventId)
    }

    fun applyDecryptionError(eventId: String, errorCode: String?, errorReason: String?) =
            queries.updateDecryptionErrorByEventId(errorCode, errorReason, eventId)

    fun updatePruned(id: Long, content: String?, unsignedData: String?) = queries.updatePrunedById(content, unsignedData, id)

    fun updateContentOnly(id: Long, content: String?) = queries.updateContentOnlyById(content, id)

    fun updateUnsignedData(id: Long, unsignedData: String?) = queries.updateUnsignedDataById(unsignedData, id)

    fun deleteByEventIdInRoom(roomId: String, eventId: String) = queries.deleteByEventIdInRoom(roomId, eventId)

    fun deleteById(id: Long) = queries.deleteById(id)

    fun deleteByRoom(roomId: String) = queries.deleteByRoom(roomId)

    private fun String?.toUnsignedData(): UnsignedData? =
            this?.takeIf { it.isNotBlank() }?.let { runCatching { unsignedAdapter.fromJson(it) }.getOrNull() }

    companion object {
        private val resultAdapter = org.matrix.android.sdk.internal.di.MoshiProvider.providesMoshi()
                .adapter(OlmDecryptionResult::class.java)
        private val unsignedAdapter = org.matrix.android.sdk.internal.di.MoshiProvider.providesMoshi()
                .adapter(UnsignedData::class.java)
    }
}

/** A generated `event` row → unmanaged [EventEntity]. thread_summary_latest_message is resolved lazily by callers when needed. */
internal fun EventRow.toEntity(): EventEntity = EventEntity(
        eventId = event_id,
        roomId = room_id,
        type = type,
        content = content,
        prevContent = prev_content,
        isUseless = is_useless != 0L,
        stateKey = state_key,
        originServerTs = origin_server_ts,
        sender = sender,
        sendStateDetails = send_state_details,
        age = age,
        unsignedData = unsigned_data,
        redacts = redacts,
        decryptionResultJson = decryption_result_json,
        ageLocalTs = age_local_ts,
        isRootThread = is_root_thread != 0L,
        rootThreadEventId = root_thread_event_id,
        numberOfThreads = number_of_threads.toInt(),
        threadSummaryLatestMessage = null,
        isVerificationStateDirty = is_verification_state_dirty?.let { it != 0L },
).also {
    it.sendState = SendState.valueOf(send_state_str)
    it.threadNotificationState = ThreadNotificationState.valueOf(thread_notification_state_str)
    it.decryptionErrorCode = decryption_error_code
    it.decryptionErrorReason = decryption_error_reason
}

private fun Boolean.toLong(): Long = if (this) 1L else 0L
