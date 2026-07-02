/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.store.db.sql

/**
 * SQL layer for inbound megolm (room key) sessions. The pickled session + the InboundGroupSessionData
 * JSON are stored as opaque columns identical to the Realm bytes, so exported/imported keys round-trip
 * unchanged. (De)serialization of the native session is done by the caller.
 */
internal class MegolmInboundSqlStore(private val database: CryptoSqlDatabase) {

    private val queries get() = database.olmInboundGroupSessionQueries

    fun upsert(
            primaryKey: String,
            sessionId: String?,
            senderKey: String?,
            roomId: String?,
            inboundGroupSessionDataJson: String?,
            serializedOlmInboundGroupSession: String?,
            sharedHistory: Boolean,
            backedUp: Boolean,
    ) {
        queries.upsert(
                primaryKey,
                sessionId,
                senderKey,
                roomId,
                inboundGroupSessionDataJson,
                serializedOlmInboundGroupSession,
                sharedHistory.toLong(),
                backedUp.toLong(),
        )
    }

    fun get(primaryKey: String): Olm_inbound_group_session? =
            queries.selectByPrimaryKey(primaryKey).executeAsOneOrNull()

    fun getWithSharedHistory(primaryKey: String, sharedHistory: Boolean): Olm_inbound_group_session? =
            queries.selectByPrimaryKeyAndSharedHistory(primaryKey, sharedHistory.toLong()).executeAsOneOrNull()

    fun getAll(): List<Olm_inbound_group_session> = queries.selectAll().executeAsList()

    /** Just the primary keys ("sessionId|senderKey") — cheap, avoids unpickling every stored session. */
    fun getAllPrimaryKeys(): Set<String> = queries.selectAllPrimaryKeys().executeAsList().toHashSet()

    fun getByRoomId(roomId: String): List<Olm_inbound_group_session> =
            queries.selectByRoomId(roomId).executeAsList()

    fun getNotBackedUp(limit: Int): List<Olm_inbound_group_session> =
            queries.selectNotBackedUp(limit.toLong()).executeAsList()

    fun count(onlyBackedUp: Boolean): Int =
            (if (onlyBackedUp) queries.countBackedUp() else queries.countAll()).executeAsOne().toInt()

    fun delete(primaryKey: String) = queries.deleteByPrimaryKey(primaryKey)

    fun markAllNotBackedUp() = queries.markAllNotBackedUp()

    fun markBackedUp(primaryKey: String) = queries.markBackedUp(primaryKey)

    private fun Boolean.toLong(): Long = if (this) 1L else 0L
}
