/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.store.db.sql

import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.crypto.OutgoingKeyRequest
import org.matrix.android.sdk.api.session.crypto.OutgoingRoomKeyRequestState
import org.matrix.android.sdk.api.session.crypto.model.AuditTrail
import org.matrix.android.sdk.api.session.crypto.model.ForwardInfo
import org.matrix.android.sdk.api.session.crypto.model.IncomingKeyRequestInfo
import org.matrix.android.sdk.api.session.crypto.model.RoomKeyRequestBody
import org.matrix.android.sdk.api.session.crypto.model.TrailType
import org.matrix.android.sdk.api.session.crypto.model.UnknownInfo
import org.matrix.android.sdk.api.session.crypto.model.WithheldInfo
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.content.WithHeldCode
import org.matrix.android.sdk.internal.crypto.util.RequestIdHelper
import org.matrix.android.sdk.internal.crypto.store.db.model.AuditTrailEntity
import org.matrix.android.sdk.internal.crypto.store.db.model.AuditTrailMapper
import org.matrix.android.sdk.internal.crypto.store.db.model.KeyRequestReplyEntity
import org.matrix.android.sdk.internal.crypto.store.db.model.OutgoingKeyRequestEntity
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.matrix.android.sdk.internal.util.time.Clock

/**
 * SQL layer for outgoing room-key requests (+ their replies) and the gossiping audit trail.
 * Reuses [OutgoingKeyRequestEntity.toOutgoingKeyRequest] and [AuditTrailMapper] via unmanaged entities.
 */
internal class KeyRequestSqlStore(
        private val database: CryptoSqlDatabase,
        private val clock: Clock,
) {

    private val queries get() = database.cryptoKeyRequestQueries
    private val auditQueries get() = database.cryptoBackupAuditQueries
    private val moshi = MoshiProvider.providesMoshi()

    // ==================== Outgoing key requests ====================

    fun getOutgoingRoomKeyRequest(requestId: String): OutgoingKeyRequest? =
            queries.okrSelectByRequestId(requestId).executeAsOneOrNull()?.let { toModel(it) }

    fun getOutgoingRoomKeyRequest(requestBody: RoomKeyRequestBody): OutgoingKeyRequest? =
            queries.okrSelectByRoomAndSession(requestBody.roomId, requestBody.sessionId).executeAsList()
                    .map { toModel(it) }
                    .firstOrNull {
                        it.requestBody?.algorithm == requestBody.algorithm &&
                                it.requestBody?.roomId == requestBody.roomId &&
                                it.requestBody?.senderKey == requestBody.senderKey &&
                                it.requestBody?.sessionId == requestBody.sessionId
                    }

    fun getOutgoingRoomKeyRequest(roomId: String, sessionId: String, algorithm: String, senderKey: String): List<OutgoingKeyRequest> =
            queries.okrSelectByRoomAndSession(roomId, sessionId).executeAsList()
                    .map { toModel(it) }
                    .filter { it.requestBody?.algorithm == algorithm && it.requestBody?.senderKey == senderKey }

    fun getOutgoingRoomKeyRequests(): List<OutgoingKeyRequest> =
            queries.okrSelectAll().executeAsList().map { toModel(it) }

    fun getOutgoingRoomKeyRequests(inStates: Set<OutgoingRoomKeyRequestState>): List<OutgoingKeyRequest> =
            queries.okrSelectByStates(inStates.map { it.name }).executeAsList().map { toModel(it) }

    fun getOrAddOutgoingRoomKeyRequest(requestBody: RoomKeyRequestBody, recipients: Map<String, List<String>>, fromIndex: Int): OutgoingKeyRequest =
            database.transactionWithResult {
                val existing = queries.okrSelectByRoomAndSession(requestBody.roomId, requestBody.sessionId).executeAsList()
                        .map { toModel(it) }
                        .firstOrNull {
                            it.requestBody?.algorithm == requestBody.algorithm &&
                                    it.requestBody?.sessionId == requestBody.sessionId &&
                                    it.requestBody?.senderKey == requestBody.senderKey &&
                                    it.requestBody?.roomId == requestBody.roomId
                        }
                existing ?: run {
                    val entity = OutgoingKeyRequestEntity().apply {
                        requestId = RequestIdHelper.createUniqueRequestId()
                        setRecipients(recipients)
                        requestedIndex = fromIndex
                        requestState = OutgoingRoomKeyRequestState.UNSENT
                        setRequestBody(requestBody)
                        creationTimeStamp = clock.epochMillis()
                    }
                    queries.okrInsert(
                            entity.requestId,
                            entity.requestedIndex?.toLong(),
                            entity.recipientsData,
                            entity.requestedInfoStr,
                            entity.creationTimeStamp,
                            entity.roomId,
                            entity.megolmSessionId,
                            entity.requestState.name,
                    )
                    entity.toOutgoingKeyRequest()
                }
            }

    fun updateOutgoingRoomKeyRequestState(requestId: String, newState: OutgoingRoomKeyRequestState) {
        database.transaction {
            queries.okrUpdateState(newState.name, requestId)
            if (newState == OutgoingRoomKeyRequestState.UNSENT) {
                queries.krrDeleteByRequestId(requestId)
            }
        }
    }

    fun updateOutgoingRoomKeyRequiredIndex(requestId: String, newIndex: Int) {
        queries.okrUpdateRequiredIndex(newIndex.toLong(), requestId)
    }

    fun updateOutgoingRoomKeyReply(roomId: String, sessionId: String, algorithm: String, senderKey: String, fromDevice: String?, event: Event) {
        database.transaction {
            val match = queries.okrSelectByRoomAndSession(roomId, sessionId).executeAsList()
                    .firstOrNull { row ->
                        val model = toModel(row)
                        model.requestBody?.senderKey == senderKey && model.requestBody?.algorithm == algorithm
                    } ?: return@transaction
            val requestId = match.request_id ?: return@transaction
            val senderId = event.senderId ?: return@transaction
            queries.krrInsert(requestId, senderId, fromDevice, moshi.adapter(Event::class.java).toJson(event))
        }
    }

    fun deleteOutgoingRoomKeyRequest(requestId: String) {
        database.transaction {
            queries.krrDeleteByRequestId(requestId)
            queries.okrDeleteByRequestId(requestId)
        }
    }

    fun deleteOutgoingRoomKeyRequestInState(state: OutgoingRoomKeyRequestState) {
        database.transaction {
            queries.okrSelectRequestIdsByState(state.name).executeAsList().forEach { it.request_id?.let { id -> queries.krrDeleteByRequestId(id) } }
            queries.okrDeleteByState(state.name)
        }
    }

    fun tidyUp(prevWeekTs: Long, prevMonthTs: Long) {
        database.transaction {
            queries.okrSelectRequestIdsOlderThan(prevWeekTs).executeAsList().forEach { it.request_id?.let { id -> queries.krrDeleteByRequestId(id) } }
            queries.okrDeleteOlderThan(prevWeekTs)
            auditQueries.auditDeleteOlderThan(prevMonthTs)
        }
    }

    // ==================== Audit trail ====================

    fun saveIncomingKeyRequestAuditTrail(requestId: String, roomId: String, sessionId: String, senderKey: String, algorithm: String, fromUser: String, fromDevice: String) {
        val info = IncomingKeyRequestInfo(roomId = roomId, sessionId = sessionId, senderKey = senderKey, alg = algorithm, userId = fromUser, deviceId = fromDevice, requestId = requestId)
        insertAudit(TrailType.IncomingKeyRequest.name, moshi.adapter(IncomingKeyRequestInfo::class.java).toJson(info))
    }

    fun saveWithheldAuditTrail(roomId: String, sessionId: String, senderKey: String, algorithm: String, code: WithHeldCode, userId: String, deviceId: String) {
        val info = WithheldInfo(roomId = roomId, sessionId = sessionId, senderKey = senderKey, alg = algorithm, code = code, userId = userId, deviceId = deviceId)
        insertAudit(TrailType.OutgoingKeyWithheld.name, moshi.adapter(WithheldInfo::class.java).toJson(info))
    }

    fun saveForwardKeyAuditTrail(roomId: String, sessionId: String, senderKey: String, algorithm: String, userId: String, deviceId: String, chainIndex: Long?, incoming: Boolean) {
        val info = ForwardInfo(roomId = roomId, sessionId = sessionId, senderKey = senderKey, alg = algorithm, userId = userId, deviceId = deviceId, chainIndex = chainIndex)
        val type = if (incoming) TrailType.IncomingKeyForward.name else TrailType.OutgoingKeyForward.name
        insertAudit(type, moshi.adapter(ForwardInfo::class.java).toJson(info))
    }

    fun getGossipingEvents(): List<AuditTrail> =
            auditQueries.auditSelectAll().executeAsList().mapNotNull { AuditTrailMapper.map(it.toEntity()) }

    fun getOrderedAuditTrails(): List<AuditTrail> =
            auditQueries.auditSelectAllOrdered().executeAsList().map { AuditTrailMapper.map(it.toEntity()) ?: unknownTrail() }

    fun getOrderedAuditTrailsByType(type: TrailType): List<AuditTrail> =
            auditQueries.auditSelectByTypeOrdered(type.name).executeAsList().map { AuditTrailMapper.map(it.toEntity()) ?: unknownTrail() }

    private fun unknownTrail() = AuditTrail(clock.epochMillis(), TrailType.Unknown, UnknownInfo)

    private fun insertAudit(type: String, contentJson: String?) {
        auditQueries.auditInsert(clock.epochMillis(), type, contentJson)
    }

    // ==================== Converters ====================

    private fun toModel(row: Outgoing_key_request): OutgoingKeyRequest {
        val replies = row.request_id?.let { queries.krrSelectByRequestId(it).executeAsList() }.orEmpty()
        val entity = OutgoingKeyRequestEntity(
                requestId = row.request_id,
                requestedIndex = row.requested_index?.toInt(),
                recipientsData = row.recipients_data,
                requestedInfoStr = row.requested_info_str,
                creationTimeStamp = row.creation_time_stamp,
                roomId = row.room_id,
                megolmSessionId = row.megolm_session_id,
                replies = ArrayList<KeyRequestReplyEntity>().apply {
                    addAll(replies.map { KeyRequestReplyEntity(senderId = it.sender_id, fromDevice = it.from_device, eventJson = it.event_json) })
                },
        )
        entity.requestState = tryOrNull { OutgoingRoomKeyRequestState.valueOf(row.request_state_str) } ?: OutgoingRoomKeyRequestState.UNSENT
        return entity.toOutgoingKeyRequest()
    }

    private fun Audit_trail.toEntity(): AuditTrailEntity = AuditTrailEntity(
            ageLocalTs = age_local_ts,
            type = type,
            contentJson = content_json,
    )
}
