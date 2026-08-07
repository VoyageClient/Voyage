/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.store.db.sql

import org.matrix.android.sdk.api.crypto.MXCRYPTO_ALGORITHM_MEGOLM
import org.matrix.android.sdk.api.session.crypto.model.CryptoRoomInfo
import org.matrix.android.sdk.api.session.events.model.content.EncryptionEventContent
import org.matrix.android.sdk.internal.crypto.store.db.mapper.CryptoRoomInfoMapper
import org.matrix.android.sdk.internal.crypto.store.db.model.CryptoRoomEntity

/**
 * SQL layer for per-room crypto state (algorithm, history-sharing, blacklist, rotation, flattened
 * outbound megolm session) plus withheld and shared megolm sessions. Reuses [CryptoRoomInfoMapper].
 */
internal class CryptoRoomSqlStore(private val database: CryptoSqlDatabase) {

    private val roomQueries get() = database.cryptoRoomQueries
    private val wsQueries get() = database.cryptoWithheldSharedQueries

    // ==================== Room crypto state ====================

    fun storeRoomAlgorithm(roomId: String, algorithm: String?) {
        database.transaction {
            roomQueries.roomInsertIgnore(roomId)
            roomQueries.roomUpdateAlgorithm(algorithm, roomId)
            if (algorithm == MXCRYPTO_ALGORITHM_MEGOLM) roomQueries.roomMarkEncryptedOnce(roomId)
        }
    }

    fun setAlgorithmInfo(roomId: String, encryption: EncryptionEventContent?) {
        database.transaction {
            roomQueries.roomInsertIgnore(roomId)
            roomQueries.roomUpdateAlgorithm(encryption?.algorithm, roomId)
            if (encryption?.algorithm == MXCRYPTO_ALGORITHM_MEGOLM) {
                roomQueries.roomMarkEncryptedOnce(roomId)
                roomQueries.roomUpdateRotation(encryption.rotationPeriodMs, encryption.rotationPeriodMsgs, roomId)
            }
        }
    }

    fun getRoomAlgorithm(roomId: String): String? = row(roomId)?.algorithm

    fun getRoomCryptoInfo(roomId: String): CryptoRoomInfo? = row(roomId)?.let { CryptoRoomInfoMapper.map(it.toEntity()) }

    fun roomWasOnceEncrypted(roomId: String): Boolean = row(roomId)?.was_encrypted_once == 1L

    fun shouldEncryptForInvitedMembers(roomId: String): Boolean = row(roomId)?.should_encrypt_for_invited_members == 1L

    fun getRoomShouldShareHistory(roomId: String): Boolean = row(roomId)?.should_share_history == 1L

    fun getBlockUnverifiedDevices(roomId: String): Boolean = row(roomId)?.blacklist_unverified_devices == 1L

    fun setShouldEncryptForInvitedMembers(roomId: String, value: Boolean) = updateRoom(roomId) { roomQueries.roomUpdateShouldEncrypt(value.toLong(), roomId) }

    fun setShouldShareHistory(roomId: String, value: Boolean) = updateRoom(roomId) { roomQueries.roomUpdateShouldShareHistory(value.toLong(), roomId) }

    fun blockUnverifiedDevicesInRoom(roomId: String, block: Boolean) = updateRoom(roomId) { roomQueries.roomUpdateBlacklist(block.toLong(), roomId) }

    fun getRoomsListBlacklistUnverifiedDevices(): List<String> = roomQueries.roomSelectBlacklistedRoomIds().executeAsList()

    // ==================== Outbound megolm session (blob handled by caller) ====================

    data class OutboundInfo(val serialized: String?, val creationTime: Long?, val shouldShareHistory: Boolean)

    fun getOutboundInfo(roomId: String): OutboundInfo? = row(roomId)
            ?.takeIf { it.outbound_serialized_data != null }
            ?.let { OutboundInfo(it.outbound_serialized_data, it.outbound_creation_time, it.outbound_should_share_history == 1L) }

    /** Only updates an existing room row, matching the legacy behaviour. */
    fun storeOutbound(roomId: String, serialized: String?, creationTime: Long?, shouldShareHistory: Boolean) {
        roomQueries.roomUpdateOutbound(serialized, creationTime, shouldShareHistory.toLong(), roomId)
    }

    fun clearOutbound(roomId: String) = roomQueries.roomClearOutbound(roomId)

    // ==================== Withheld sessions ====================

    fun addWithHeld(roomId: String, sessionId: String, senderKey: String?, codeString: String?, reason: String?) {
        database.transaction {
            val existing = wsQueries.withheldSelectByRoomSession(roomId, sessionId, MXCRYPTO_ALGORITHM_MEGOLM).executeAsOneOrNull()
            if (existing != null) {
                wsQueries.withheldUpdate(senderKey, codeString, reason, roomId, sessionId, MXCRYPTO_ALGORITHM_MEGOLM)
            } else {
                wsQueries.withheldInsert(roomId, MXCRYPTO_ALGORITHM_MEGOLM, sessionId, senderKey, codeString, reason)
            }
        }
    }

    fun getWithHeld(roomId: String, sessionId: String): Withheld_session? =
            wsQueries.withheldSelectByRoomSession(roomId, sessionId, MXCRYPTO_ALGORITHM_MEGOLM).executeAsOneOrNull()

    fun getWithHeldInRoom(roomId: String): List<Withheld_session> =
            wsQueries.withheldSelectByRoom(roomId, MXCRYPTO_ALGORITHM_MEGOLM).executeAsList()

    // ==================== Shared sessions ====================

    fun markedSessionAsShared(roomId: String?, sessionId: String, userId: String, deviceId: String, deviceIdentityKey: String, chainIndex: Int) {
        // Replace rather than append: the table has no unique constraint, so re-sharing a session to
        // the same device would otherwise accumulate a row per share. The earliest index is kept —
        // a device that received the session at index 5 can still decrypt from 5 after a re-share.
        wsQueries.transaction {
            val existing = wsQueries
                    .sharedSelectExact(roomId, sessionId, MXCRYPTO_ALGORITHM_MEGOLM, userId, deviceId, deviceIdentityKey)
                    .executeAsOneOrNull()
                    ?.chain_index
            val earliest = minOf(chainIndex.toLong(), existing ?: Long.MAX_VALUE)
            wsQueries.sharedDeleteExact(roomId, sessionId, MXCRYPTO_ALGORITHM_MEGOLM, userId, deviceId, deviceIdentityKey)
            wsQueries.sharedInsert(roomId, MXCRYPTO_ALGORITHM_MEGOLM, sessionId, userId, deviceId, deviceIdentityKey, earliest)
        }
    }

    fun getSharedSession(roomId: String?, sessionId: String, userId: String, deviceId: String, deviceIdentityKey: String?): Shared_session? =
            wsQueries.sharedSelectExact(roomId, sessionId, MXCRYPTO_ALGORITHM_MEGOLM, userId, deviceId, deviceIdentityKey).executeAsOneOrNull()

    fun getSharedSessions(roomId: String?, sessionId: String): List<Shared_session> =
            wsQueries.sharedSelectByRoomSession(roomId, sessionId, MXCRYPTO_ALGORITHM_MEGOLM).executeAsList()

    // ==================== Helpers ====================

    private fun row(roomId: String): Crypto_room? = roomQueries.roomSelectById(roomId).executeAsOneOrNull()

    private inline fun updateRoom(roomId: String, crossinline block: () -> Unit) {
        database.transaction {
            roomQueries.roomInsertIgnore(roomId)
            block()
        }
    }

    private fun Crypto_room.toEntity(): CryptoRoomEntity = CryptoRoomEntity(
            roomId = room_id,
            algorithm = algorithm,
            shouldEncryptForInvitedMembers = should_encrypt_for_invited_members?.let { it != 0L },
            blacklistUnverifiedDevices = blacklist_unverified_devices == 1L,
            shouldShareHistory = should_share_history == 1L,
            wasEncryptedOnce = was_encrypted_once?.let { it != 0L },
            rotationPeriodMs = rotation_period_ms,
            rotationPeriodMsgs = rotation_period_msgs,
    )

    private fun Boolean.toLong(): Long = if (this) 1L else 0L
}
