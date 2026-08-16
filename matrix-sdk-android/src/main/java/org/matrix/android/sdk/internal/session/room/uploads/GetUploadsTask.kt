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
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.LocalEcho
import org.matrix.android.sdk.api.session.events.model.isSticker
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageGalleryContent
import org.matrix.android.sdk.api.session.room.model.message.MessageStickerContent
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.uploads.GetUploadsResult
import org.matrix.android.sdk.api.session.room.uploads.UploadEvent
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.query.TimelineEventFilter
import org.matrix.android.sdk.internal.database.sql.store.globToSqlLike
import org.matrix.android.sdk.internal.database.sql.store.toEntity
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.filter.FilterFactory
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.session.room.timeline.PaginationDirection
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
        @SessionDatabase private val database: org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase,
        private val stores: org.matrix.android.sdk.internal.database.sql.store.SessionStores,
        private val globalErrorReceiver: GlobalErrorReceiver,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
) : GetUploadsTask {

    // Callers await this from viewModelScope, so without a hop the event and member queries below run
    // on the main thread — enough of them to drop a second of frames when opening the uploads list.
    override suspend fun execute(params: GetUploadsTask.Params): GetUploadsResult = withContext(coroutineDispatchers.io) {
        val result: GetUploadsResult
        val events: List<Event>

        if (params.isRoomEncrypted) {
            // Get a chunk of events from cache for e2e rooms

            result = GetUploadsResult(
                    uploadEvents = emptyList(),
                    nextToken = "",
                    hasMore = false
            )

            events = database.eventQueries
                    .selectEncryptedWithUrlInRoom(params.roomId, EventType.ENCRYPTED, TimelineEventFilter.DecryptedContent.URL.globToSqlLike())
                    .executeAsList()
                    .map { it.toEntity().asDomain() }
        } else {
            // Sliding sync stops advancing the v2 next_batch, so a token left over from before the switch
            // would freeze this list at that point in time. `from` is optional, and omitting it starts from
            // the most recent event, which is what this wants.
            val since = params.since ?: tokenStore.getLastToken().takeIf { tokenStore.getSlidingSyncPos() == null }

            val filter = FilterFactory.createUploadsFilter(params.numberOfEvents).toJSONString()
            val chunk = executeRequest(globalErrorReceiver) {
                roomAPI.getRoomMessagesFrom(params.roomId, since, PaginationDirection.BACKWARDS.value, params.numberOfEvents, filter)
            }

            result = GetUploadsResult(
                    uploadEvents = emptyList(),
                    nextToken = chunk.end ?: "",
                    hasMore = chunk.hasMore()
            )
            events = chunk.events
        }

        var uploadEvents = listOf<UploadEvent>()

        val cacheOfSenderInfos = mutableMapOf<String, SenderInfo>()

        // Get a snapshot of all room members
        run {
            // One scan of the member table, not one per sender: isUniqueDisplayName() counts matches over
            // every member, so calling it per sender re-read the whole room each time.
            val members = org.matrix.android.sdk.internal.session.room.membership.SqlRoomMemberHelper(stores, params.roomId)
                    .queryRoomMembersEvent()
            val membersByUserId = members.associateBy { it.userId }
            val displayNameCounts = members.groupingBy { it.displayName }.eachCount()

            uploadEvents = events.flatMap { event ->
                val eventId = event.eventId ?: return@flatMap emptyList()
                // A synced media send leaves its local echo row in the event table; both carry the same
                // attachment, so without this the gallery shows every self-sent upload twice.
                if (LocalEcho.isLocalEchoId(eventId)) return@flatMap emptyList()
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

        result.copy(uploadEvents = uploadEvents)
    }
}
