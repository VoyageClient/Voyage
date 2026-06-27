/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.internal.database.model.ReadReceiptEntity
import org.matrix.android.sdk.internal.database.model.ReadReceiptsSummaryEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.Read_receipt as ReadReceiptRow
import org.matrix.android.sdk.internal.database.sql.Read_receipts_summary as ReadReceiptsSummaryRow

/** SQL access for `read_receipts_summary` + `read_receipt`. A summary's receipts are joined by event_id. */
internal class ReadReceiptSqlStore(private val database: SessionSqlDatabase) {

    private val queries get() = database.readReceiptQueries

    fun getSummary(eventId: String): ReadReceiptsSummaryEntity? =
            queries.selectSummary(eventId).executeAsOneOrNull()?.toEntity()

    fun upsertSummary(eventId: String, roomId: String) = queries.upsertSummary(eventId, roomId)

    fun getReceipt(roomId: String, userId: String, threadId: String?): ReadReceiptEntity? =
            queries.selectReceiptForUserInRoom(roomId, userId, threadId).executeAsOneOrNull()?.toEntity()

    fun upsertReceipt(entity: ReadReceiptEntity) = queries.upsertReceipt(
            primary_key = entity.primaryKey,
            event_id = entity.eventId,
            room_id = entity.roomId,
            user_id = entity.userId,
            thread_id = entity.threadId,
            origin_server_ts = entity.originServerTs,
    )

    fun deleteReceiptsForEvent(eventId: String) = queries.deleteReceiptsForEvent(eventId)

    fun deleteSummary(eventId: String) = queries.deleteSummary(eventId)

    fun deleteByRoom(roomId: String) {
        queries.deleteSummariesByRoom(roomId)
        queries.deleteReceiptsByRoom(roomId)
    }

    private fun ReadReceiptsSummaryRow.toEntity(): ReadReceiptsSummaryEntity {
        val receipts = queries.selectReceiptsForEvent(event_id).executeAsList().map { it.toEntity() }
        return ReadReceiptsSummaryEntity(
                eventId = event_id,
                roomId = room_id,
                readReceipts = ArrayList<ReadReceiptEntity>().apply { addAll(receipts) },
        )
    }

    private fun ReadReceiptRow.toEntity(): ReadReceiptEntity = ReadReceiptEntity(
            primaryKey = primary_key,
            eventId = event_id,
            roomId = room_id,
            userId = user_id,
            threadId = thread_id,
            originServerTs = origin_server_ts,
    )
}
