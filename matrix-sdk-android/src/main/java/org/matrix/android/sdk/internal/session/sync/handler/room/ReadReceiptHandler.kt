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

package org.matrix.android.sdk.internal.session.sync.handler.room

import io.realm.Realm
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.internal.database.model.ReadReceiptEntity
import org.matrix.android.sdk.internal.database.model.ReadReceiptsSummaryEntity
import org.matrix.android.sdk.internal.database.query.createUnmanaged
import org.matrix.android.sdk.internal.database.query.getOrCreate
import org.matrix.android.sdk.internal.database.query.where
import org.matrix.android.sdk.internal.session.sync.RoomSyncEphemeralTemporaryStore
import org.matrix.android.sdk.internal.session.sync.SyncResponsePostTreatmentAggregator
import timber.log.Timber
import javax.inject.Inject

// the receipts dictionaries
// key   : $EventId
// value : dict key $UserId
//              value dict key ts
//                    dict value ts value
internal typealias ReadReceiptContent = Map<String, Map<String, Map<String, Map<String, Any>>>>

// "m.read.private" (MSC2285) is the user's own private read receipt. The server only delivers it to the
// owning user, so it's safe to treat it as a read receipt for unread computation (without it, privately-read
// rooms reappear as unread after the cache is rebuilt from the server).
private val RECEIPT_KEYS = listOf("m.read", "m.read.private")
private const val TIMESTAMP_KEY = "ts"
private const val THREAD_ID_KEY = "thread_id"

internal class ReadReceiptHandler @Inject constructor(
        private val roomSyncEphemeralTemporaryStore: RoomSyncEphemeralTemporaryStore
) {

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
            threadId?.let {
                userReadReceipt.put(THREAD_ID_KEY, threadId)
            }
            return mapOf(
                    eventId to mapOf(
                            "m.read" to mapOf(
                                    userId to userReadReceipt
                            )
                    )
            )
        }
    }

    fun handle(
            realm: Realm,
            roomId: String,
            content: ReadReceiptContent?,
            isInitialSync: Boolean,
            aggregator: SyncResponsePostTreatmentAggregator?
    ) {
        content ?: return

        try {
            handleReadReceiptContent(realm, roomId, content, isInitialSync, aggregator)
        } catch (exception: Exception) {
            Timber.e("Fail to handle read receipt for room $roomId")
        }
    }

    private fun handleReadReceiptContent(
            realm: Realm,
            roomId: String,
            content: ReadReceiptContent,
            isInitialSync: Boolean,
            aggregator: SyncResponsePostTreatmentAggregator?
    ) {
        if (isInitialSync) {
            initialSyncStrategy(realm, roomId, content)
        } else {
            incrementalSyncStrategy(realm, roomId, content, aggregator)
        }
    }

    private fun initialSyncStrategy(realm: Realm, roomId: String, content: ReadReceiptContent) {
        val readReceiptSummaries = ArrayList<ReadReceiptsSummaryEntity>()
        for ((eventId, receiptDict) in content) {
            val readReceiptsSummary = ReadReceiptsSummaryEntity(eventId = eventId, roomId = roomId)
            val handledUserIds = HashSet<String>()
            for (receiptKey in RECEIPT_KEYS) {
                val userIdsDict = receiptDict[receiptKey] ?: continue
                for ((userId, paramsDict) in userIdsDict) {
                    // m.read wins over m.read.private for the same user/event (it's iterated first).
                    if (!handledUserIds.add(userId)) continue
                    val ts = paramsDict[TIMESTAMP_KEY] as? Double ?: 0.0
                    val threadId = paramsDict[THREAD_ID_KEY] as String?
                    val receiptEntity = ReadReceiptEntity.createUnmanaged(roomId, eventId, userId, threadId, ts)
                    readReceiptsSummary.readReceipts.add(receiptEntity)
                }
            }
            if (readReceiptsSummary.readReceipts.isNotEmpty()) {
                readReceiptSummaries.add(readReceiptsSummary)
            }
        }
        realm.insertOrUpdate(readReceiptSummaries)
    }

    private fun incrementalSyncStrategy(
            realm: Realm,
            roomId: String,
            content: ReadReceiptContent,
            aggregator: SyncResponsePostTreatmentAggregator?
    ) {
        // First check if we have data from init sync to handle
        getContentFromInitSync(roomId)?.let {
            Timber.d("INIT_SYNC Insert during incremental sync RR for room $roomId")
            doIncrementalSyncStrategy(realm, roomId, it)
            aggregator?.ephemeralFilesToDelete?.add(roomId)
        }

        doIncrementalSyncStrategy(realm, roomId, content)
    }

    private fun doIncrementalSyncStrategy(realm: Realm, roomId: String, content: ReadReceiptContent) {
        for ((eventId, receiptDict) in content) {
            if (RECEIPT_KEYS.none { receiptDict.containsKey(it) }) continue
            val readReceiptsSummary = ReadReceiptsSummaryEntity.where(realm, eventId).findFirst()
                    ?: realm.createObject(ReadReceiptsSummaryEntity::class.java, eventId).apply {
                        this.roomId = roomId
                    }

            for (receiptKey in RECEIPT_KEYS) {
                val userIdsDict = receiptDict[receiptKey] ?: continue
                for ((userId, paramsDict) in userIdsDict) {
                    val ts = paramsDict[TIMESTAMP_KEY] as? Double ?: 0.0
                    val threadId = paramsDict[THREAD_ID_KEY] as String?
                    val receiptEntity = ReadReceiptEntity.getOrCreate(realm, roomId, userId, threadId)
                    // ensure new ts is superior to the previous one (keeps the latest of m.read / m.read.private)
                    if (ts > receiptEntity.originServerTs) {
                        ReadReceiptsSummaryEntity.where(realm, receiptEntity.eventId).findFirst()?.also {
                            it.readReceipts.remove(receiptEntity)
                        }
                        receiptEntity.eventId = eventId
                        receiptEntity.originServerTs = ts
                        readReceiptsSummary.readReceipts.add(receiptEntity)
                    }
                }
            }
        }
    }

    fun getContentFromInitSync(roomId: String): ReadReceiptContent? {
        val dataFromFile = roomSyncEphemeralTemporaryStore.read(roomId)

        dataFromFile ?: return null

        @Suppress("UNCHECKED_CAST")
        val content = dataFromFile
                .events
                ?.firstOrNull { it.type == EventType.RECEIPT }
                ?.content as? ReadReceiptContent

        if (content == null) {
            // We can delete the file now
            roomSyncEphemeralTemporaryStore.delete(roomId)
        }

        return content
    }

    fun onContentFromInitSyncHandled(roomId: String) {
        roomSyncEphemeralTemporaryStore.delete(roomId)
    }
}
