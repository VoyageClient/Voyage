/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync.handler.room

import dagger.Lazy
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.getRelationContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.internal.session.room.membership.SqlRoomMemberHelper
import org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilitiesService
import org.matrix.android.sdk.api.session.room.accountdata.RoomAccountDataTypes
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.session.room.model.tag.RoomTagContent
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.session.sync.model.InvitedRoomSync
import org.matrix.android.sdk.api.session.sync.model.KnockedRoomSync
import org.matrix.android.sdk.api.session.sync.model.LazyRoomSyncEphemeral
import org.matrix.android.sdk.api.session.sync.model.RoomSync
import org.matrix.android.sdk.api.session.sync.model.RoomSyncAccountData
import org.matrix.android.sdk.api.session.sync.model.RoomsSyncResponse
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.session.room.read.FullyReadContent
import org.matrix.android.sdk.internal.session.room.read.MarkedUnreadContent
import org.matrix.android.sdk.api.settings.LightweightSettingsStorage
import org.matrix.android.sdk.internal.crypto.algorithms.megolm.UnRequestedForwardManager
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.mapper.toEntity
import org.matrix.android.sdk.internal.database.model.EventInsertType
import org.matrix.android.sdk.internal.database.model.RoomEntity
import org.matrix.android.sdk.api.session.room.threads.model.ThreadSummaryUpdateType
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sql.store.ThreadSummarySqlHelper
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.StreamEventsManager
import org.matrix.android.sdk.internal.session.events.getFixedRoomMemberContent
import org.matrix.android.sdk.internal.session.room.membership.RoomChangeMembershipStateDataSource
import org.matrix.android.sdk.internal.session.room.membership.SqlRoomMemberEventHandler
import org.matrix.android.sdk.internal.session.room.summary.SqlRoomSummaryUpdater
import org.matrix.android.sdk.internal.session.room.timeline.PaginationDirection
import org.matrix.android.sdk.internal.session.room.timeline.TimelineInput
import org.matrix.android.sdk.internal.session.sync.ProgressReporter
import org.matrix.android.sdk.internal.session.sync.SyncResponsePostTreatmentAggregator
import org.matrix.android.sdk.internal.session.sync.mapWithProgress
import org.matrix.android.sdk.internal.util.time.Clock
import timber.log.Timber
import javax.inject.Inject

/**
 * SQLDelight write-path counterpart of [RoomSyncHandler]. Runs inside the session DB transaction.
 */
internal class SqlRoomSyncHandler @Inject constructor(
        private val readReceiptHandler: SqlReadReceiptHandler,
        private val roomSummaryUpdater: SqlRoomSummaryUpdater,
        private val roomMemberEventHandler: SqlRoomMemberEventHandler,
        private val roomChangeMembershipStateDataSource: RoomChangeMembershipStateDataSource,
        private val roomTagHandler: SqlRoomTagHandler,
        private val roomFullyReadHandler: SqlRoomFullyReadHandler,
        private val roomMarkedUnreadHandler: SqlRoomMarkedUnreadHandler,
        private val typingUsersTracker: org.matrix.android.sdk.internal.session.typing.DefaultTypingUsersTracker,
        @UserId private val userId: String,
        private val homeServerCapabilitiesService: HomeServerCapabilitiesService,
        private val threadSummaryHelper: ThreadSummarySqlHelper,
        private val lightweightSettingsStorage: LightweightSettingsStorage,
        private val timelineInput: TimelineInput,
        private val liveEventService: Lazy<StreamEventsManager>,
        private val clock: Clock,
        private val unRequestedForwardManager: UnRequestedForwardManager,
) {

    @Suppress("UNUSED_PARAMETER")
    fun handle(
            stores: SessionStores,
            roomsSyncResponse: RoomsSyncResponse,
            isInitialSync: Boolean,
            aggregator: SyncResponsePostTreatmentAggregator,
            reporter: ProgressReporter? = null,
    ) {
        val insertType = if (isInitialSync) EventInsertType.INITIAL_SYNC else EventInsertType.INCREMENTAL_SYNC
        val ts = clock.epochMillis()
        roomsSyncResponse.join.forEach { handleJoinedRoom(stores, it.key, it.value, insertType, ts, aggregator) }
        roomsSyncResponse.invite.forEach { handleInvitedRoom(stores, it.key, it.value, insertType, ts, aggregator) }
        roomsSyncResponse.knock.forEach { handleKnockedRoom(stores, it.key, it.value, insertType, ts, aggregator) }
        roomsSyncResponse.leave.forEach { handleLeftRoom(stores, it.key, it.value, insertType, ts, aggregator) }
    }

    private fun handleJoinedRoom(
            stores: SessionStores, roomId: String, roomSync: RoomSync,
            insertType: EventInsertType, syncTs: Long, aggregator: SyncResponsePostTreatmentAggregator,
    ) {
        val isInitialSync = insertType == EventInsertType.INITIAL_SYNC
        (roomSync.ephemeral as? LazyRoomSyncEphemeral.Parsed)?.roomSyncEphemeral?.events
                ?.takeIf { it.isNotEmpty() }
                ?.let { handleEphemeral(stores, roomId, it, isInitialSync, aggregator) }

        val roomEntity = stores.room.get(roomId) ?: RoomEntity(roomId = roomId)
        if (roomEntity.membership != Membership.JOIN) aggregator.spaceHierarchyChanged = true
        if (roomEntity.membership == Membership.INVITE) clearRoomTimeline(stores, roomId)
        roomEntity.membership = Membership.JOIN
        stores.room.upsert(roomEntity)

        roomSync.state?.events?.forEach { event ->
            if (event.eventId == null || event.stateKey == null || event.type == null) return@forEach
            val ageLocalTs = syncTs - (event.unsignedData?.age ?: 0)
            insertEventOrIgnore(stores, event.toEntity(roomId, SendState.SYNCED, ageLocalTs), insertType)
            stores.currentStateEvent.upsert(roomId, event.type, event.stateKey, event.eventId, event.eventId)
            roomMemberEventHandler.handle(stores, roomId, event, isInitialSync, aggregator)
        }
        if (roomSync.timeline?.events?.isNotEmpty() == true) {
            handleTimelineEvents(stores, roomId, roomSync.timeline.events, roomSync.timeline.prevToken, roomSync.timeline.limited, insertType, syncTs)
        }
        val hasRoomMember = (roomSync.state?.events.orEmpty() + roomSync.timeline?.events.orEmpty())
                .any { it.type == EventType.STATE_ROOM_MEMBER }

        roomChangeMembershipStateDataSource.setMembershipFromSync(roomId, Membership.JOIN)
        roomSummaryUpdater.update(
                stores, roomId, Membership.JOIN, roomSync.summary, roomSync.unreadNotifications,
                roomSync.unreadThreadNotifications, updateMembers = hasRoomMember, aggregator = aggregator,
        )
        // After the summary update so tag/read-marker/marked-unread flags it derives aren't clobbered.
        handleRoomAccountData(stores, roomId, roomSync.accountData)
    }

    // When the remote copy of a sent edit arrives, re-point its edit-summary edition from the local echo
    // (txId) to the real event id in this same transaction, so the edited message doesn't flicker
    // un-edited before the async aggregation processor catches up.
    private fun fixUpEditLocalEcho(stores: SessionStores, event: Event, txId: String) {
        if (event.getRelationContent()?.type != RelationType.REPLACE) return
        val realEventId = event.eventId ?: return
        val targetId = event.getRelationContent()?.eventId ?: return
        val summary = stores.annotations.get(targetId) ?: return
        val edition = summary.editSummary?.editions?.firstOrNull { it.eventId == txId } ?: return
        edition.eventId = realEventId
        edition.isLocalEcho = false
        stores.annotations.replaceEditions(targetId, summary.editSummary)
    }

    private fun handleRoomAccountData(stores: SessionStores, roomId: String, accountData: RoomSyncAccountData?) {
        accountData?.events?.forEach { event ->
            val type = event.type ?: return@forEach
            stores.accountData.upsertRoomAccountData(roomId, type, ContentMapper.map(event.content))
            when (type) {
                RoomAccountDataTypes.EVENT_TYPE_TAG -> roomTagHandler.handle(stores, roomId, event.content.toModel<RoomTagContent>())
                RoomAccountDataTypes.EVENT_TYPE_FULLY_READ -> roomFullyReadHandler.handle(stores, roomId, event.content.toModel<FullyReadContent>())
                RoomAccountDataTypes.MARKED_UNREAD -> roomMarkedUnreadHandler.handle(stores, roomId, event.content.toModel<MarkedUnreadContent>())
            }
        }
    }

    private fun handleInvitedRoom(
            stores: SessionStores, roomId: String, roomSync: InvitedRoomSync,
            insertType: EventInsertType, syncTs: Long, aggregator: SyncResponsePostTreatmentAggregator,
    ) {
        val isInitialSync = insertType == EventInsertType.INITIAL_SYNC
        val roomEntity = stores.room.get(roomId) ?: RoomEntity(roomId = roomId)
        aggregator.spaceHierarchyChanged = true
        roomEntity.membership = Membership.INVITE
        stores.room.upsert(roomEntity)
        roomSync.inviteState?.events?.forEach { event ->
            if (event.stateKey == null || event.type == null) return@forEach
            val ageLocalTs = syncTs - (event.unsignedData?.age ?: 0)
            val entity = event.toEntity(roomId, SendState.SYNCED, ageLocalTs)
            insertEventOrIgnore(stores, entity, insertType)
            stores.currentStateEvent.upsert(roomId, event.type, event.stateKey, entity.eventId, entity.eventId)
            roomMemberEventHandler.handle(stores, roomId, event, isInitialSync)
        }
        val inviterEvent = roomSync.inviteState?.events?.lastOrNull { it.type == EventType.STATE_ROOM_MEMBER }
        roomChangeMembershipStateDataSource.setMembershipFromSync(roomId, Membership.INVITE)
        roomSummaryUpdater.update(stores, roomId, Membership.INVITE, updateMembers = true, inviterId = inviterEvent?.senderId, aggregator = aggregator)
        unRequestedForwardManager.onInviteReceived(roomId, inviterEvent?.senderId.orEmpty(), clock.epochMillis())
    }

    private fun handleKnockedRoom(
            stores: SessionStores, roomId: String, roomSync: KnockedRoomSync,
            insertType: EventInsertType, syncTs: Long, aggregator: SyncResponsePostTreatmentAggregator,
    ) {
        val isInitialSync = insertType == EventInsertType.INITIAL_SYNC
        val roomEntity = stores.room.get(roomId) ?: RoomEntity(roomId = roomId)
        aggregator.spaceHierarchyChanged = true
        roomEntity.membership = Membership.KNOCK
        stores.room.upsert(roomEntity)
        roomSync.knockState?.events?.forEach { event ->
            if (event.stateKey == null || event.type == null) return@forEach
            val ageLocalTs = syncTs - (event.unsignedData?.age ?: 0)
            val entity = event.toEntity(roomId, SendState.SYNCED, ageLocalTs)
            insertEventOrIgnore(stores, entity, insertType)
            stores.currentStateEvent.upsert(roomId, event.type, event.stateKey, entity.eventId, entity.eventId)
            roomMemberEventHandler.handle(stores, roomId, event, isInitialSync)
        }
        roomChangeMembershipStateDataSource.setMembershipFromSync(roomId, Membership.KNOCK)
        roomSummaryUpdater.update(stores, roomId, Membership.KNOCK, updateMembers = true, aggregator = aggregator)
    }

    private fun handleLeftRoom(
            stores: SessionStores, roomId: String, roomSync: RoomSync,
            insertType: EventInsertType, syncTs: Long, aggregator: SyncResponsePostTreatmentAggregator,
    ) {
        val isInitialSync = insertType == EventInsertType.INITIAL_SYNC
        val roomEntity = stores.room.get(roomId) ?: RoomEntity(roomId = roomId)
        aggregator.spaceHierarchyChanged = true
        (roomSync.state?.events.orEmpty() + roomSync.timeline?.events.orEmpty()).forEach { event ->
            if (event.eventId == null || event.stateKey == null || event.type == null) return@forEach
            val ageLocalTs = syncTs - (event.unsignedData?.age ?: 0)
            insertEventOrIgnore(stores, event.toEntity(roomId, SendState.SYNCED, ageLocalTs), insertType)
            stores.currentStateEvent.upsert(roomId, event.type, event.stateKey, event.eventId, event.eventId)
            if (event.type == EventType.STATE_ROOM_MEMBER) {
                roomMemberEventHandler.handle(stores, roomId, event, isInitialSync)
            }
        }
        val membership = stores.roomMember.getByRoomAndUser(roomId, userId)?.membership ?: Membership.LEAVE
        roomEntity.membership = membership
        stores.room.upsert(roomEntity)
        clearRoomTimeline(stores, roomId)
        roomChangeMembershipStateDataSource.setMembershipFromSync(roomId, Membership.LEAVE)
        roomSummaryUpdater.update(
                stores, roomId, membership, roomSync.summary, roomSync.unreadNotifications,
                roomSync.unreadThreadNotifications, aggregator = aggregator,
        )
    }

    private fun handleTimelineEvents(
            stores: SessionStores, roomId: String, eventList: List<Event>,
            prevToken: String?, isLimited: Boolean, insertType: EventInsertType, syncTs: Long,
    ) {
        val lastChunkId = stores.chunk.lastForward(roomId)?.id
        val chunkId = if (!isLimited && lastChunkId != null) {
            lastChunkId
        } else {
            clearRoomTimeline(stores, roomId)
            stores.chunk.insert(roomId, prevToken, null, null, null, isLastForward = true, isLastBackward = false, null, false)
        }
        val isLastForward = true
        val eventIds = ArrayList<String>(eventList.size)
        val roomMemberContentsByUser = HashMap<String, RoomMemberContent?>()
        val isInitialSync = insertType == EventInsertType.INITIAL_SYNC
        val rootThreadEventIds = LinkedHashSet<String>()

        for (rawEvent in eventList) {
            val ageLocalTs = syncTs - (rawEvent.unsignedData?.age ?: 0)
            val event = rawEvent.copyAll(roomId = roomId).also { it.ageLocalTs = ageLocalTs }
            if (event.eventId == null || event.senderId == null || event.type == null) continue
            eventIds.add(event.eventId)
            if (!isInitialSync) liveEventService.get().dispatchLiveEventReceived(event, roomId)

            val entity = event.toEntity(roomId, SendState.SYNCED, ageLocalTs)
            val eventDbId = insertEventOrIgnore(stores, entity, insertType)
            if (event.stateKey != null) {
                stores.currentStateEvent.upsert(roomId, event.type, event.stateKey, event.eventId, event.eventId)
                if (event.type == EventType.STATE_ROOM_MEMBER) {
                    roomMemberContentsByUser[event.stateKey] = event.getFixedRoomMemberContent()
                    roomMemberEventHandler.handle(stores, roomId, event, isInitialSync)
                }
            }
            roomMemberContentsByUser.getOrPut(event.senderId) {
                stores.currentStateEvent.getOne(roomId, EventType.STATE_ROOM_MEMBER, event.senderId)
                        ?.root?.asDomain()?.getFixedRoomMemberContent()
            }
            stores.timelineWriter.addTimelineEvent(chunkId, roomId, eventDbId, entity, isLastForward, PaginationDirection.FORWARDS, roomMemberContentsByUser = roomMemberContentsByUser)

            if (lightweightSettingsStorage.areThreadMessagesEnabled()) {
                entity.rootThreadEventId?.let { rootId ->
                    rootThreadEventIds.add(rootId)
                    // If the user has this thread open (its forward thread chunk exists), append the reply live.
                    stores.chunk.lastForwardThread(roomId, rootId)?.id?.let { threadChunkId ->
                        if (stores.timelineEvent.getInChunkByEventId(threadChunkId, entity.eventId) == null) {
                            stores.timelineWriter.addTimelineEvent(
                                    chunkId = threadChunkId, roomId = roomId, eventDbId = eventDbId, event = entity,
                                    isLastForward = true, direction = PaginationDirection.FORWARDS, ownedByThreadChunk = true,
                                    roomMemberContentsByUser = roomMemberContentsByUser,
                            )
                        }
                    }
                    // Incrementally update the MSC3440 thread summary so the thread list updates live.
                    if (homeServerCapabilitiesService.getHomeServerCapabilities().canUseThreading) {
                        threadSummaryHelper.createOrUpdate(
                                type = ThreadSummaryUpdateType.ADD,
                                stores = stores,
                                roomId = roomId,
                                threadEventEntity = entity,
                                roomMemberContentsByUser = roomMemberContentsByUser,
                                currentTimeMillis = clock.epochMillis(),
                        )
                    }
                }
            }

            // Remove local echo if this is the remote copy.
            event.unsignedData?.transactionId?.let { txId ->
                stores.timelineEvent.deleteSending(roomId, txId)
                fixUpEditLocalEcho(stores, event, txId)
            }
            stores.timelineEvent.deleteSending(roomId, event.eventId)
        }
        // Mark root events of any thread we received replies for with their reply count + latest reply, so
        // the thread badge, thread list, and inline latest-message preview pick them up.
        if (lightweightSettingsStorage.areThreadMessagesEnabled()) {
            rootThreadEventIds.forEach { rootId ->
                val count = stores.event.countThreadReplies(roomId, rootId)
                if (count > 0) {
                    stores.event.getDbId(roomId, rootId)?.let { rootDbId ->
                        val latestTimelineId = stores.timelineEvent.latestThreadReplyId(roomId, rootId)
                        stores.event.markEventAsRoot(rootDbId, count, latestTimelineId)
                    }
                }
            }
        }
        timelineInput.onNewTimelineEvents(roomId = roomId, eventIds = eventIds)
    }

    private fun handleEphemeral(
            stores: SessionStores, roomId: String, ephemeralEvents: List<Event>,
            isInitialSync: Boolean, aggregator: SyncResponsePostTreatmentAggregator,
    ) {
        // m.typing carries the room's full current typing list; if this ephemeral batch has no typing
        // event, no one is typing (matches the legacy handler which reset typing on every ephemeral pass).
        var typingUserIds: List<String> = emptyList()
        for (event in ephemeralEvents) {
            when (event.type) {
                EventType.RECEIPT -> {
                    @Suppress("UNCHECKED_CAST")
                    (event.content as? ReadReceiptContent)?.let {
                        readReceiptHandler.handle(stores, roomId, it, isInitialSync, aggregator)
                    }
                }
                EventType.TYPING -> {
                    typingUserIds = event.content.toModel<org.matrix.android.sdk.internal.session.room.typing.TypingEventContent>()
                            ?.typingUserIds.orEmpty()
                }
            }
        }
        setTypingUsers(stores, roomId, typingUserIds)
    }

    private fun setTypingUsers(stores: SessionStores, roomId: String, typingUserIds: List<String>) {
        val excluded = stores.user.getIgnoredUserIds().toSet() + userId
        val memberHelper = SqlRoomMemberHelper(stores, roomId)
        val senderInfo = typingUserIds.filter { it !in excluded }.map { typingUser ->
            val member = memberHelper.getLastRoomMember(typingUser)
            SenderInfo(
                    userId = typingUser,
                    displayName = member?.displayName,
                    isUniqueDisplayName = memberHelper.isUniqueDisplayName(member?.displayName),
                    avatarUrl = member?.avatarUrl,
            )
        }
        typingUsersTracker.setTypingUsersFromRoom(roomId, senderInfo)
    }

    private fun insertEventOrIgnore(stores: SessionStores, entity: org.matrix.android.sdk.internal.database.model.EventEntity, insertType: EventInsertType): Long {
        stores.event.getDbId(entity.roomId, entity.eventId)?.let { return it }
        stores.eventInsert.insert(entity.eventId, entity.type, true, insertType)
        return stores.event.insert(entity)
    }

    private fun clearRoomTimeline(stores: SessionStores, roomId: String) {
        stores.chunk.getByRoom(roomId).forEach { stores.timelineEvent.deleteByChunk(it.id) }
        stores.chunk.deleteByRoom(roomId)
        Timber.v("Cleared timeline for $roomId")
    }
}
