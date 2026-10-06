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

package org.matrix.android.sdk.internal.session.room.uploads

import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.crypto.CryptoService
import org.matrix.android.sdk.api.session.crypto.model.OlmDecryptionResult
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.isEdition
import org.matrix.android.sdk.api.session.events.model.isSticker
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageGalleryContent
import org.matrix.android.sdk.api.session.room.model.message.MessageStickerContent
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.uploads.GetUploadsResult
import org.matrix.android.sdk.api.session.room.uploads.UploadEvent
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.filter.FilterFactory
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.session.room.timeline.PaginationDirection
import org.matrix.android.sdk.internal.session.search.index.EventIndexStore
import org.matrix.android.sdk.internal.session.search.index.EventIndexer
import org.matrix.android.sdk.internal.session.sync.SyncTokenStore
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

internal interface GetUploadsTask : Task<GetUploadsTask.Params, GetUploadsResult> {

    data class Params(
            val roomId: String,
            val isRoomEncrypted: Boolean,
            val numberOfEvents: Int,
            val since: String?
    )
}

internal class DefaultGetUploadsTask @Inject constructor(
        private val roomAPI: RoomAPI,
        private val tokenStore: SyncTokenStore,
        private val stores: SessionStores,
        private val eventIndexer: EventIndexer,
        private val indexStore: EventIndexStore,
        private val cryptoService: dagger.Lazy<CryptoService>,
        private val globalErrorReceiver: GlobalErrorReceiver,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
) : GetUploadsTask {

    private class Page(val events: List<Event>, val nextToken: String, val hasMore: Boolean)

    // Callers await this from viewModelScope, so without a hop the event and member queries below run
    // on the main thread — enough of them to drop a second of frames when opening the uploads list.
    override suspend fun execute(params: GetUploadsTask.Params): GetUploadsResult = withContext(coroutineDispatchers.io) {
        // The server can't see which encrypted events are attachments, so encrypted rooms page through
        // the local event index, which crawls and decrypts history on demand.
        val page = when {
            !params.isRoomEncrypted -> serverPage(params)
            eventIndexer.isEnabled() -> indexPage(params)
            else -> decryptingServerPage(params)
        }
        GetUploadsResult(
                uploadEvents = toUploadEvents(params.roomId, page.events),
                nextToken = page.nextToken,
                hasMore = page.hasMore,
        )
    }

    private suspend fun serverPage(params: GetUploadsTask.Params): Page {
        val filter = FilterFactory.createUploadsFilter(params.numberOfEvents).toJSONString()
        val chunk = executeRequest(globalErrorReceiver) {
            roomAPI.getRoomMessagesFrom(params.roomId, params.since ?: liveToken(), PaginationDirection.BACKWARDS.value, params.numberOfEvents, filter)
        }
        return Page(chunk.events, chunk.end.orEmpty(), chunk.hasMore())
    }

    // Sliding sync stops advancing the v2 next_batch, so a token left over from before the switch would
    // freeze this list at that point in time. `from` is optional, and omitting it starts from the most
    // recent event, which is what this wants.
    private suspend fun liveToken(): String? = tokenStore.getLastToken().takeIf { tokenStore.getSlidingSyncPos() == null }

    private suspend fun indexPage(params: GetUploadsTask.Params): Page {
        val roomId = params.roomId
        var cursor = IndexCursor.parse(params.since)
        val events = ArrayList<Event>(params.numberOfEvents)
        var floor = eventIndexer.completeSince(roomId)
        var backfills = 0
        var stuck = false
        while (events.size < params.numberOfEvents) {
            val wanted = params.numberOfEvents - events.size
            val rows = indexStore.uploads(roomId, cursor.ts, cursor.eventId, floor, wanted)
            rows.forEach { row ->
                cursor = IndexCursor(row.origin_server_ts, row.event_id)
                val event = tryOrNull { eventAdapter.fromJson(row.event_json) } ?: return@forEach
                events.add(event)
            }
            if (rows.size == wanted) continue
            // What the index vouches for has run out: crawl further back, unless history has too.
            if (floor == Long.MIN_VALUE || backfills >= MAX_BACKFILL_BATCHES) break
            backfills++
            try {
                eventIndexer.backfillRoomOrThrow(roomId)
            } catch (failure: Throwable) {
                if (events.isEmpty()) throw failure
                break
            }
            val newFloor = eventIndexer.completeSince(roomId)
            // A crawl that can't get any further would otherwise have the list ask again forever.
            if (newFloor == floor) {
                stuck = true
                break
            }
            floor = newFloor
        }
        val hasMore = events.size >= params.numberOfEvents || (floor != Long.MIN_VALUE && !stuck)
        return Page(events, cursor.encode(), hasMore)
    }

    /** The index is off, so nothing is kept: fetch history and decrypt it in memory, page by page. */
    private suspend fun decryptingServerPage(params: GetUploadsTask.Params): Page {
        val filter = FilterFactory.createEncryptedUploadsFilter(DECRYPTING_BATCH_SIZE).toJSONString()
        var from = params.since ?: liveToken()
        val events = ArrayList<Event>()
        var hasMore = true
        var requests = 0
        while (events.size < params.numberOfEvents && hasMore && requests < MAX_DECRYPTING_REQUESTS) {
            requests++
            val chunk = executeRequest(globalErrorReceiver) {
                roomAPI.getRoomMessagesFrom(params.roomId, from, PaginationDirection.BACKWARDS.value, DECRYPTING_BATCH_SIZE, filter)
            }
            chunk.events.forEach { event ->
                if (event.isEncrypted() && !decryptInPlace(event)) return@forEach
                events.add(event)
            }
            from = chunk.end
            hasMore = chunk.hasMore()
        }
        return Page(events, from.orEmpty(), hasMore)
    }

    private suspend fun decryptInPlace(event: Event): Boolean {
        val result = tryOrNull { cryptoService.get().decryptEvent(event, "") } ?: return false
        event.mxDecryptionResult = OlmDecryptionResult(
                payload = result.clearEvent,
                senderKey = result.senderCurve25519Key,
                keysClaimed = result.claimedEd25519Key?.let { mapOf("ed25519" to it) },
                forwardingCurve25519KeyChain = result.forwardingCurve25519KeyChain,
                verificationState = result.messageVerificationState
        )
        return true
    }

    private fun toUploadEvents(roomId: String, events: List<Event>): List<UploadEvent> {
        val cacheOfSenderInfos = mutableMapOf<String, SenderInfo>()

        // One scan of the member table, not one per sender: isUniqueDisplayName() counts matches over
        // every member, so calling it per sender re-read the whole room each time.
        val members = org.matrix.android.sdk.internal.session.room.membership.SqlRoomMemberHelper(stores, roomId)
                .queryRoomMembersEvent()
        val membersByUserId = members.associateBy { it.userId }
        val displayNameCounts = members.groupingBy { it.displayName }.eachCount()

        return events.flatMap { event ->
            val eventId = event.eventId ?: return@flatMap emptyList()
            // Redacted uploads come from the preserved-content store instead, and an edit carries its
            // target's attachment again.
            if (event.isRedacted() || event.isEdition()) return@flatMap emptyList()
            val senderId = event.senderId ?: return@flatMap emptyList()

            val senderInfo = cacheOfSenderInfos.getOrPut(senderId) {
                val member = membersByUserId[senderId]
                val displayName = member?.displayName
                SenderInfo(
                        userId = senderId,
                        displayName = displayName,
                        isUniqueDisplayName = displayName.isNullOrEmpty() || displayNameCounts[displayName] == 1,
                        avatarUrl = member?.avatarUrl
                )
            }

            val clearContent = event.getClearContent()
            val gallery = clearContent?.toModel<MessageContent>() as? MessageGalleryContent
            if (gallery != null) {
                gallery.galleryItems().mapIndexed { index, item ->
                    UploadEvent(
                            root = event,
                            eventId = eventId,
                            contentWithAttachmentContent = item,
                            senderInfo = senderInfo,
                            galleryItemIndex = index,
                    )
                }
            } else {
                val messageWithAttachmentContent = if (event.isSticker()) {
                    clearContent?.toModel<MessageStickerContent>()
                } else {
                    clearContent?.toModel<MessageContent>() as? MessageWithAttachmentContent
                } ?: return@flatMap emptyList()
                listOf(
                        UploadEvent(
                                root = event,
                                eventId = eventId,
                                contentWithAttachmentContent = messageWithAttachmentContent,
                                senderInfo = senderInfo
                        )
                )
            }
        }
    }

    /** Where an index page left off: the last row's (origin_server_ts, event_id), the query's sort key. */
    private data class IndexCursor(val ts: Long, val eventId: String) {
        fun encode() = "$ts|$eventId"

        companion object {
            private val START = IndexCursor(Long.MAX_VALUE, "")

            fun parse(token: String?): IndexCursor {
                val parts = token?.split('|', limit = 2) ?: return START
                return parts.getOrNull(0)?.toLongOrNull()?.let { IndexCursor(it, parts.getOrElse(1) { "" }) } ?: START
            }
        }
    }

    companion object {
        // 100 events each; bounds how long one page may spend crawling a room with sparse attachments.
        private const val MAX_BACKFILL_BATCHES = 10

        private const val DECRYPTING_BATCH_SIZE = 100
        private const val MAX_DECRYPTING_REQUESTS = 10

        private val eventAdapter = MoshiProvider.providesMoshi().adapter(Event::class.java)
    }
}
