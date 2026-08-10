/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.peeking

import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.isSticker
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageStickerContent
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.session.room.peeking.LiveRoomPeekEvents
import org.matrix.android.sdk.api.session.room.peeking.LiveRoomPeekPage
import org.matrix.android.sdk.api.session.room.peeking.LiveRoomPeekSnapshot
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.uploads.GetUploadsResult
import org.matrix.android.sdk.api.session.room.uploads.UploadEvent
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.filter.FilterFactory
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.session.room.timeline.PaginationDirection
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

internal interface PeekRoomInitialSyncTask : Task<PeekRoomInitialSyncTask.Params, LiveRoomPeekSnapshot> {
    data class Params(
            val roomId: String,
            val limit: Int,
    )
}

internal class DefaultPeekRoomInitialSyncTask @Inject constructor(
        private val roomAPI: RoomAPI,
        private val globalErrorReceiver: GlobalErrorReceiver,
) : PeekRoomInitialSyncTask {

    override suspend fun execute(params: PeekRoomInitialSyncTask.Params): LiveRoomPeekSnapshot {
        val response = executeRequest(globalErrorReceiver) {
            roomAPI.roomInitialSync(params.roomId, params.limit)
        }
        return LiveRoomPeekSnapshot(
                membership = response.membership,
                stateEvents = response.state.orEmpty(),
                timelineEvents = response.messages?.chunk.orEmpty(),
                backwardToken = response.messages?.start,
        )
    }
}

internal interface PeekRoomMessagesTask : Task<PeekRoomMessagesTask.Params, LiveRoomPeekPage> {
    data class Params(
            val roomId: String,
            /** Null starts at the live edge (`from` is optional since Matrix v1.3). */
            val fromToken: String?,
            val limit: Int,
    )
}

internal class DefaultPeekRoomMessagesTask @Inject constructor(
        private val roomAPI: RoomAPI,
        private val globalErrorReceiver: GlobalErrorReceiver,
) : PeekRoomMessagesTask {

    override suspend fun execute(params: PeekRoomMessagesTask.Params): LiveRoomPeekPage {
        val response = executeRequest(globalErrorReceiver) {
            roomAPI.getRoomMessagesFrom(params.roomId, params.fromToken, "b", params.limit, """{"lazy_load_members":true}""")
        }
        return LiveRoomPeekPage(
                events = response.chunk.orEmpty(),
                stateEvents = response.stateEvents.orEmpty(),
                nextToken = response.end,
        )
    }
}

internal interface PeekRoomMembersTask : Task<PeekRoomMembersTask.Params, List<Event>> {
    data class Params(
            val roomId: String,
    )
}

internal class DefaultPeekRoomMembersTask @Inject constructor(
        private val roomAPI: RoomAPI,
        private val globalErrorReceiver: GlobalErrorReceiver,
) : PeekRoomMembersTask {

    override suspend fun execute(params: PeekRoomMembersTask.Params): List<Event> {
        return executeRequest(globalErrorReceiver) {
            roomAPI.getMembers(params.roomId, null, null, null)
        }.roomMemberEvents
    }
}

internal interface PeekRoomUploadsTask : Task<PeekRoomUploadsTask.Params, GetUploadsResult> {
    data class Params(
            val roomId: String,
            val numberOfEvents: Int,
            val since: String?,
    )
}

internal class DefaultPeekRoomUploadsTask @Inject constructor(
        private val roomAPI: RoomAPI,
        private val globalErrorReceiver: GlobalErrorReceiver,
) : PeekRoomUploadsTask {

    override suspend fun execute(params: PeekRoomUploadsTask.Params): GetUploadsResult {
        val filter = FilterFactory.createUploadsFilter(params.numberOfEvents).toJSONString()
        val chunk = executeRequest(globalErrorReceiver) {
            // Null `since` starts at the live edge (`from` is optional since Matrix v1.3).
            roomAPI.getRoomMessagesFrom(params.roomId, params.since, PaginationDirection.BACKWARDS.value, params.numberOfEvents, filter)
        }
        val memberContents = chunk.stateEvents.orEmpty()
                .filter { it.type == EventType.STATE_ROOM_MEMBER }
                .mapNotNull { ev -> ev.stateKey?.let { it to ev.content.toModel<RoomMemberContent>() } }
                .toMap()
        val displayNameCounts = memberContents.values.filterNotNull().groupingBy { it.displayName }.eachCount()
        val uploadEvents = chunk.events.mapNotNull { event ->
            val eventId = event.eventId ?: return@mapNotNull null
            val messageWithAttachmentContent = if (event.isSticker()) {
                event.getClearContent()?.toModel<MessageStickerContent>()
            } else {
                event.getClearContent()?.toModel<MessageContent>() as? MessageWithAttachmentContent
            } ?: return@mapNotNull null
            val senderId = event.senderId ?: return@mapNotNull null
            val member = memberContents[senderId]
            UploadEvent(
                    root = event,
                    eventId = eventId,
                    contentWithAttachmentContent = messageWithAttachmentContent,
                    senderInfo = SenderInfo(
                            userId = senderId,
                            displayName = member?.displayName,
                            isUniqueDisplayName = (displayNameCounts[member?.displayName] ?: 1) <= 1,
                            avatarUrl = member?.avatarUrl,
                    ),
            )
        }
        return GetUploadsResult(
                uploadEvents = uploadEvents,
                nextToken = chunk.end ?: "",
                hasMore = chunk.hasMore(),
        )
    }
}

internal interface PeekLiveEventsTask : Task<PeekLiveEventsTask.Params, LiveRoomPeekEvents> {
    data class Params(
            val roomId: String,
            val fromToken: String?,
            val timeoutMs: Long,
    )
}

internal class DefaultPeekLiveEventsTask @Inject constructor(
        private val roomAPI: RoomAPI,
        private val globalErrorReceiver: GlobalErrorReceiver,
) : PeekLiveEventsTask {

    override suspend fun execute(params: PeekLiveEventsTask.Params): LiveRoomPeekEvents {
        val response = executeRequest(globalErrorReceiver) {
            roomAPI.peekEvents(params.roomId, params.fromToken, params.timeoutMs)
        }
        return LiveRoomPeekEvents(
                events = response.chunk.orEmpty().filter { it.roomId == params.roomId },
                startToken = response.start,
                nextToken = response.end,
        )
    }
}
