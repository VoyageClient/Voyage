/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync.handler.room

import org.matrix.android.sdk.api.session.room.read.ReadService
import org.matrix.android.sdk.internal.database.model.ReadReceiptEntity
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.sync.RoomSyncEphemeralTemporaryStore
import org.matrix.android.sdk.internal.session.sync.SyncResponsePostTreatmentAggregator
import timber.log.Timber
import javax.inject.Inject

private val RECEIPT_KEYS = listOf("m.read", "m.read.private")
internal typealias ReadReceiptContent = Map<String, Map<String, Map<String, Map<String, Any>>>>

private const val TIMESTAMP_KEY = "ts"
private const val THREAD_ID_KEY = "thread_id"

/** SQLDelight write-path counterpart of the former Realm ReadReceiptHandler. Runs inside the session DB transaction. */
@SessionScope
internal class SqlReadReceiptHandler @Inject constructor(
        private val roomSyncEphemeralTemporaryStore: RoomSyncEphemeralTemporaryStore,
) {

    @Volatile private var normalizedLegacyReceipts = false

    companion object {
        fun createContent(
                userId: String,
                eventId: String,
                threadId: String?,
                currentTimeMillis: Long
        ): ReadReceiptContent {
            val userReadReceipt = mutableMapOf<String, Any>(
                    TIMESTAMP_KEY to currentTimeMillis.toDouble(),
            )
            threadId?.let { userReadReceipt.put(THREAD_ID_KEY, it) }
            return mapOf(eventId to mapOf("m.read" to mapOf(userId to userReadReceipt)))
        }
    }

    fun handle(
            stores: SessionStores,
            roomId: String,
            content: ReadReceiptContent?,
            isInitialSync: Boolean,
            aggregator: SyncResponsePostTreatmentAggregator?,
    ) {
        content ?: return
        normalizeLegacyReceipts(stores)
        try {
            val ignoredUserIds = stores.user.getIgnoredUserIds().toSet()
            if (isInitialSync) {
                initialSyncStrategy(stores, roomId, content, ignoredUserIds)
            } else {
                incrementalSyncStrategy(stores, roomId, content, ignoredUserIds, aggregator)
            }
        } catch (exception: Exception) {
            Timber.e("Fail to handle read receipt for room $roomId")
        }
    }

    private fun normalizeLegacyReceipts(stores: SessionStores) {
        if (normalizedLegacyReceipts) return
        try {
            stores.readReceipt.normalizeUnthreadedReceipts()
            normalizedLegacyReceipts = true
        } catch (exception: Exception) {
            Timber.e(exception, "Fail to normalize legacy read receipts")
        }
    }

    private fun initialSyncStrategy(stores: SessionStores, roomId: String, content: ReadReceiptContent, ignoredUserIds: Set<String>) {
        for ((eventId, receiptDict) in content) {
            val handledUserIds = HashSet<String>()
            val receipts = ArrayList<ReadReceiptEntity>()
            for (receiptKey in RECEIPT_KEYS) {
                val userIdsDict = receiptDict[receiptKey] ?: continue
                for ((userId, paramsDict) in userIdsDict) {
                    if (userId in ignoredUserIds) continue
                    // m.read wins over m.read.private for the same user/event (iterated first).
                    if (!handledUserIds.add(userId)) continue
                    val ts = paramsDict[TIMESTAMP_KEY] as? Double ?: 0.0
                    val threadId = paramsDict[THREAD_ID_KEY] as? String ?: ReadService.THREAD_ID_MAIN
                    receipts.add(receiptEntity(roomId, eventId, userId, threadId, ts))
                }
            }
            if (receipts.isNotEmpty()) {
                stores.readReceipt.upsertSummary(eventId, roomId)
                receipts.forEach { receipt ->
                    // Shares a key with the receipt synthesized when a user sends an event, so an
                    // unguarded write could move that user's receipt backwards in time.
                    val existing = stores.readReceipt.getReceipt(receipt.roomId, receipt.userId, receipt.threadId)
                    if (existing == null || receipt.originServerTs > existing.originServerTs) {
                        stores.readReceipt.upsertReceipt(receipt)
                    }
                }
            }
        }
    }

    private fun incrementalSyncStrategy(
            stores: SessionStores,
            roomId: String,
            content: ReadReceiptContent,
            ignoredUserIds: Set<String>,
            aggregator: SyncResponsePostTreatmentAggregator?,
    ) {
        getContentFromInitSync(roomId)?.let {
            Timber.d("INIT_SYNC Insert during incremental sync RR for room $roomId")
            doIncrementalSyncStrategy(stores, roomId, it, ignoredUserIds)
            aggregator?.ephemeralFilesToDelete?.add(roomId)
        }
        doIncrementalSyncStrategy(stores, roomId, content, ignoredUserIds)
    }

    private fun doIncrementalSyncStrategy(stores: SessionStores, roomId: String, content: ReadReceiptContent, ignoredUserIds: Set<String>) {
        for ((eventId, receiptDict) in content) {
            if (RECEIPT_KEYS.none { receiptDict.containsKey(it) }) continue
            stores.readReceipt.upsertSummary(eventId, roomId)
            for (receiptKey in RECEIPT_KEYS) {
                val userIdsDict = receiptDict[receiptKey] ?: continue
                for ((userId, paramsDict) in userIdsDict) {
                    if (userId in ignoredUserIds) continue
                    val ts = paramsDict[TIMESTAMP_KEY] as? Double ?: 0.0
                    val threadId = paramsDict[THREAD_ID_KEY] as? String ?: ReadService.THREAD_ID_MAIN
                    val existing = stores.readReceipt.getReceipt(roomId, userId, threadId)
                    if (existing == null || ts > existing.originServerTs) {
                        // upsertReceipt replaces the row at the same primary key, moving it to the new event/ts.
                        stores.readReceipt.upsertReceipt(receiptEntity(roomId, eventId, userId, threadId, ts))
                    }
                }
            }
        }
    }

    private fun receiptEntity(roomId: String, eventId: String, userId: String, threadId: String, ts: Double) =
            ReadReceiptEntity(
                    primaryKey = "${roomId}_${userId}_$threadId",
                    eventId = eventId,
                    roomId = roomId,
                    userId = userId,
                    threadId = threadId,
                    originServerTs = ts,
            )

    fun getContentFromInitSync(roomId: String): ReadReceiptContent? {
        val dataFromFile = roomSyncEphemeralTemporaryStore.read(roomId) ?: return null
        @Suppress("UNCHECKED_CAST")
        val content = dataFromFile.events
                ?.firstOrNull { it.type == org.matrix.android.sdk.api.session.events.model.EventType.RECEIPT }
                ?.content as? ReadReceiptContent
        if (content == null) {
            roomSyncEphemeralTemporaryStore.delete(roomId)
        }
        return content
    }

    fun onContentFromInitSyncHandled(roomId: String) {
        roomSyncEphemeralTemporaryStore.delete(roomId)
    }
}
