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
import org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilitiesService
import org.matrix.android.sdk.api.session.room.accountdata.RoomAccountDataTypes
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.session.room.model.tag.RoomTagContent
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.threads.model.ThreadSummaryUpdateType
import org.matrix.android.sdk.api.session.sync.InitialSyncStep
import org.matrix.android.sdk.api.session.sync.model.InvitedRoomSync
import org.matrix.android.sdk.api.session.sync.model.KnockedRoomSync
import org.matrix.android.sdk.api.session.sync.model.LazyRoomSyncEphemeral
import org.matrix.android.sdk.api.session.sync.model.RoomSync
import org.matrix.android.sdk.api.session.sync.model.RoomSyncAccountData
import org.matrix.android.sdk.api.session.sync.model.RoomSyncHeroProfile
import org.matrix.android.sdk.api.session.sync.model.RoomsSyncResponse
import org.matrix.android.sdk.api.settings.LightweightSettingsStorage
import org.matrix.android.sdk.api.util.MatrixPerf
import org.matrix.android.sdk.internal.crypto.algorithms.megolm.UnRequestedForwardManager
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.mapper.toEntity
import org.matrix.android.sdk.internal.database.model.EventInsertType
import org.matrix.android.sdk.internal.database.model.RoomEntity
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sql.store.ThreadSummarySqlHelper
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.StreamEventsManager
import org.matrix.android.sdk.internal.session.events.getFixedRoomMemberContent
import org.matrix.android.sdk.internal.session.room.accountdata.RoomStateOverrides
import org.matrix.android.sdk.internal.session.room.membership.RoomChangeMembershipStateDataSource
import org.matrix.android.sdk.internal.session.room.membership.RoomMemberEntityFactory
import org.matrix.android.sdk.internal.session.room.membership.SqlRoomMemberEventHandler
import org.matrix.android.sdk.internal.session.room.membership.SqlRoomMemberHelper
import org.matrix.android.sdk.internal.session.room.read.FullyReadContent
import org.matrix.android.sdk.internal.session.room.read.MarkedUnreadContent
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

    fun handle(
            stores: SessionStores,
            roomsSyncResponse: RoomsSyncResponse,
            isInitialSync: Boolean,
            aggregator: SyncResponsePostTreatmentAggregator,
            reporter: ProgressReporter? = null,
    ) {
        val insertType = if (isInitialSync) EventInsertType.INITIAL_SYNC else EventInsertType.INCREMENTAL_SYNC
        val ts = clock.epochMillis()
        // Reported per room rather than per batch: importing is the long pole of a first sync, and a bar
        // that only moves once it is over reads as a hang.
        roomsSyncResponse.join.mapWithProgress(reporter, InitialSyncStep.ImportingAccountJoinedRooms, 0.7f) {
            handleJoinedRoom(stores, it.key, it.value, insertType, ts, aggregator)
        }
        roomsSyncResponse.invite.mapWithProgress(reporter, InitialSyncStep.ImportingAccountInvitedRooms, 0.1f) {
            handleInvitedRoom(stores, it.key, it.value, insertType, ts, aggregator)
        }
        roomsSyncResponse.knock.forEach { handleKnockedRoom(stores, it.key, it.value, insertType, ts, aggregator) }
        roomsSyncResponse.leave.mapWithProgress(reporter, InitialSyncStep.ImportingAccountLeftRooms, 0.2f) {
            handleLeftRoom(stores, it.key, it.value, insertType, ts, aggregator)
        }
        if (!isInitialSync) readReceiptHandler.drainStoredInitSyncReceipts(stores, aggregator)
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
        val previousMembership = roomEntity.membership
        if (previousMembership != Membership.JOIN) aggregator.spaceHierarchyChanged = true
        // A kick/ban→(invite)→join round trip keeps the retained local history; only a plain
        // invite-accept clears and refetches like upstream.
        val rejoinAfterRemoval = previousMembership == Membership.LEAVE || previousMembership == Membership.BAN ||
                (previousMembership == Membership.INVITE && stores.roomSummary.get(roomId)?.isRemovedFromRoom == true)
        if (previousMembership == Membership.INVITE && !rejoinAfterRemoval) clearRoomTimeline(stores, roomId)
        if (rejoinAfterRemoval) {
            // A visibility boundary persisted while removed (403 → is_last_backward) no longer
            // applies; a legitimate room-start boundary just re-marks itself on the next paginate.
            stores.chunk.clearLastBackward(roomId)
            aggregator.rejoinedRoomsToReanchor.add(roomId)
        }
        roomEntity.membership = Membership.JOIN
        stores.room.upsert(roomEntity)

        // MSC4222: `state_after` is the state at the END of the timeline, so it has to be applied
        // after the timeline events rather than before them, or the timeline would overwrite it.
        val stateAfter = roomSync.stateAfter
        if (stateAfter == null) {
            applyStateEvents(stores, roomId, roomSync.state?.events, insertType, syncTs, isInitialSync, aggregator)
        } else {
            // The timeline resolves each sender's profile from the member state already in the DB, so
            // someone who joined during a gap would otherwise render as a bare MXID for this whole batch.
            // Membership is applied early for that reason; the full state still lands after the timeline,
            // and re-applying these is idempotent.
            applyStateEvents(
                    stores, roomId, stateAfter.events.orEmpty().filter { it.type == EventType.STATE_ROOM_MEMBER },
                    insertType, syncTs, isInitialSync, aggregator
            )
        }
        val timeline = roomSync.timeline
        val syncTimelineEvents = timeline?.events
        if (syncTimelineEvents?.isNotEmpty() == true) {
            val timelineEvents = if (rejoinAfterRemoval) {
                // An invite between the removal and this join never arrives as a timeline event
                // (consumed by the stripped invite section, or filtered by history visibility), so
                // splice it in — in timestamp order — or the sequence renders kick → join. Which
                // response section (if any) carries its full form varies, so fall back to the event
                // table, where some earlier path (state delta, lazy members) has usually stored it.
                val stateCandidates = (stateAfter?.events ?: roomSync.state?.events).orEmpty().filter {
                    it.type == EventType.STATE_ROOM_MEMBER && it.stateKey == userId && it.eventId != null
                }
                val myInvite = stateCandidates.firstOrNull { it.getFixedRoomMemberContent()?.membership == Membership.INVITE }
                        ?: run {
                            val stored = stores.event.getRecentStateOfKey(roomId, EventType.STATE_ROOM_MEMBER, userId, 6).map { it.asDomain() }
                            val newestRemovalTs = stored.firstOrNull {
                                val m = it.getFixedRoomMemberContent()?.membership
                                m == Membership.BAN || (m == Membership.LEAVE && it.senderId != userId)
                            }?.originServerTs ?: 0
                            stored.firstOrNull {
                                it.eventId != null &&
                                        it.getFixedRoomMemberContent()?.membership == Membership.INVITE &&
                                        (it.originServerTs ?: 0) >= newestRemovalTs &&
                                        stores.timelineEvent.getByRoomAndEventId(roomId, it.eventId.orEmpty()) == null
                            }
                        }
                if (myInvite != null && syncTimelineEvents.none { it.eventId == myInvite.eventId }) {
                    val inviteTs = myInvite.originServerTs ?: 0
                    val at = syncTimelineEvents.indexOfFirst { (it.originServerTs ?: Long.MAX_VALUE) > inviteTs }
                    if (at >= 0) {
                        syncTimelineEvents.toMutableList().apply { add(at, myInvite) }
                    } else {
                        syncTimelineEvents + myInvite
                    }
                } else {
                    syncTimelineEvents
                }
            } else {
                syncTimelineEvents
            }
            MatrixPerf.time("sync.room.timelineEvents n=${timelineEvents.size}") {
                handleTimelineEvents(
                        stores, roomId, timelineEvents, timeline.prevToken, timeline.limited, insertType, syncTs,
                        // Rejoining over a retained removed timeline: the old live chunk must not
                        // absorb the join batch — its token span would swallow the unfetched removal
                        // gap, and /context islands for gap events would then interleave against the
                        // wrong chunk. A fresh live chunk keeps spans honest; back-pagination fills
                        // the gap (where visibility allows) and links the two.
                        // Same reasoning for a room delivered for the first time on a sliding-sync
                        // connection: it brings only its newest few events, which need not join up with what
                        // is already stored, and a restarted connection re-delivers every room that way.
                        forceNewChunk = rejoinAfterRemoval || roomSync.isInitialDelivery,
                )
            }
        }
        if (stateAfter != null) {
            applyStateEvents(stores, roomId, stateAfter.events, insertType, syncTs, isInitialSync, aggregator)
        }
        val hasRoomMember = (stateAfter?.events ?: roomSync.state?.events).orEmpty()
                .plus(roomSync.timeline?.events.orEmpty())
                .any { it.type == EventType.STATE_ROOM_MEMBER }

        applyHeroProfiles(stores, roomId, roomSync.heroProfiles)

        roomChangeMembershipStateDataSource.setMembershipFromSync(roomId, Membership.JOIN)
        MatrixPerf.time("sync.room.summaryUpdate members=$hasRoomMember") {
            roomSummaryUpdater.update(
                    stores, roomId, Membership.JOIN, roomSync.summary, roomSync.unreadNotifications,
                    roomSync.unreadThreadNotifications, updateMembers = hasRoomMember, aggregator = aggregator,
            )
        }
        handleRoomAccountData(stores, roomId, roomSync.accountData)
    }

    /**
     * Gives a hero a member row when lazy loading did not send one, so a DM can be named and pictured after
     * the other person without waiting for the room to be opened. A real member event always wins: this only
     * fills in what is missing.
     */
    private fun applyHeroProfiles(stores: SessionStores, roomId: String, heroes: List<RoomSyncHeroProfile>) {
        heroes.forEach { hero ->
            if (hero.displayName == null && hero.avatarUrl == null) return@forEach
            val existing = stores.roomMember.getByRoomAndUser(roomId, hero.userId)
            if (existing != null && !existing.displayName.isNullOrBlank()) return@forEach
            val content = RoomMemberContent(
                    membership = Membership.JOIN,
                    displayName = hero.displayName,
                    avatarUrl = hero.avatarUrl,
            )
            stores.roomMember.upsert(
                    RoomMemberEntityFactory.create(roomId, hero.userId, content, stores.user.getPresence(hero.userId))
            )
        }
    }

    private fun applyStateEvents(
            stores: SessionStores, roomId: String, events: List<Event>?,
            insertType: EventInsertType, syncTs: Long, isInitialSync: Boolean,
            aggregator: SyncResponsePostTreatmentAggregator,
    ) {
        events?.forEach { event ->
            val eventId = event.eventId
            val stateKey = event.stateKey
            val type = event.type
            if (stateKey == null || type == null) return@forEach
            if (eventId == null) {
                // MSC4186 reports state that no longer applies as a bare {type, state_key} stub. Sync v2
                // never sends an event without an id, so this cannot fire on that path.
                stores.currentStateEvent.deleteOne(roomId, type, stateKey)
                return@forEach
            }
            val ageLocalTs = syncTs - (event.unsignedData?.age ?: 0)
            insertEventOrIgnore(stores, event.toEntity(roomId, SendState.SYNCED, ageLocalTs), insertType)
            stores.currentStateEvent.upsert(roomId, type, stateKey, eventId, eventId)
            roomMemberEventHandler.handle(stores, roomId, event, isInitialSync, aggregator)
        }
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
        // Touch event_annotations_summary so the timeline's annotation-change flow fires (it doesn't watch the
        // editions table) — see EventRelationsAggregationProcessor.handleReactionRedact.
        stores.annotations.upsertSummary(targetId, summary.roomId)
        stores.annotations.replaceEditions(targetId, summary.editSummary)
    }

    private fun handleRoomAccountData(stores: SessionStores, roomId: String, accountData: RoomSyncAccountData?) {
        accountData?.events?.forEach { event ->
            val type = event.type ?: return@forEach
            // Synapse synthesises m.tag from its tag table, then appends the raw m.tag room account data
            // row, so a stale `{}` row lands last and would wipe every tag on each initial sync.
            // Clearing tags is `{"tags":{}}`; a missing key carries no tag information at all.
            if (type == RoomAccountDataTypes.EVENT_TYPE_TAG && event.content?.containsKey("tags") != true) {
                Timber.w("Ignoring m.tag without a tags key for $roomId")
                return@forEach
            }
            stores.accountData.upsertRoomAccountData(roomId, type, ContentMapper.map(event.content))
            when (type) {
                RoomAccountDataTypes.EVENT_TYPE_TAG -> roomTagHandler.handle(stores, roomId, event.content.toModel<RoomTagContent>())
                RoomAccountDataTypes.EVENT_TYPE_FULLY_READ -> roomFullyReadHandler.handle(stores, roomId, event.content.toModel<FullyReadContent>())
                RoomAccountDataTypes.MARKED_UNREAD -> roomMarkedUnreadHandler.handle(stores, roomId, event.content.toModel<MarkedUnreadContent>())
                in RoomStateOverrides.ALL_TYPES -> roomSummaryUpdater.refreshDisplay(stores, roomId)
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
            val stateKey = event.stateKey
            val type = event.type
            if (stateKey == null || type == null) return@forEach
            val ageLocalTs = syncTs - (event.unsignedData?.age ?: 0)
            val entity = event.toEntity(roomId, SendState.SYNCED, ageLocalTs)
            insertEventOrIgnore(stores, entity, insertType)
            stores.currentStateEvent.upsert(roomId, type, stateKey, entity.eventId, entity.eventId)
            roomMemberEventHandler.handle(stores, roomId, event, isInitialSync)
        }
        val inviterEvent = roomSync.inviteState?.events?.lastOrNull { it.type == EventType.STATE_ROOM_MEMBER }
        roomChangeMembershipStateDataSource.setMembershipFromSync(roomId, Membership.INVITE)
        roomSummaryUpdater.update(stores, roomId, Membership.INVITE, updateMembers = true, inviterId = inviterEvent?.senderId, aggregator = aggregator)
        // Servers are meant to withhold invites from ignored users; hide the ones that slip through
        // anyway (a known sliding-sync gap) rather than showing the invite they were ignored to avoid.
        if (inviterEvent?.senderId != null && inviterEvent.senderId in stores.user.getIgnoredUserIds()) {
            stores.roomSummary.setHiddenFromUser(roomId, true)
        }
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
            val stateKey = event.stateKey
            val type = event.type
            if (stateKey == null || type == null) return@forEach
            val ageLocalTs = syncTs - (event.unsignedData?.age ?: 0)
            val entity = event.toEntity(roomId, SendState.SYNCED, ageLocalTs)
            insertEventOrIgnore(stores, entity, insertType)
            stores.currentStateEvent.upsert(roomId, type, stateKey, entity.eventId, entity.eventId)
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
        // MSC4222: state_after wins over the timeline, so it has to be applied last.
        val orderedStateEvents = roomSync.stateAfter
                ?.let { roomSync.timeline?.events.orEmpty() + it.events.orEmpty() }
                ?: (roomSync.state?.events.orEmpty() + roomSync.timeline?.events.orEmpty())
        val removalMembership = removalMembership(stores, roomId, orderedStateEvents)
        val removedFromRoom = removalMembership != null
        if (removedFromRoom) {
            // Kicked/banned rooms stay browsable up to the removal: keep the local timeline and
            // ingest this sync's final chunk (the last messages plus the kick/ban event itself).
            // Before the state loop, so state_after still wins over timeline state.
            val timeline = roomSync.timeline
            val timelineEvents = timeline?.events
            if (timelineEvents?.isNotEmpty() == true) {
                // A gappy final chunk normally replaces the timeline and the gap backfills from the
                // server on demand — but a ban revokes all history access, so replacing would
                // destroy cached history for good. Append instead; the gap's middle is lost either way.
                val limited = timeline.limited && removalMembership != Membership.BAN
                handleTimelineEvents(stores, roomId, timelineEvents, timeline.prevToken, limited, insertType, syncTs)
                // A ban's leave sync is stripped down to the ban itself, so a fuller batch delivered later
                // is *older* than the chunk already holds and appending it strands the ban at the top.
                stores.chunk.lastForward(roomId)?.id?.let { stores.timelineEvent.resequenceChunkByTimestamp(it) }
            }
        } else {
            clearRoomTimeline(stores, roomId)
        }
        orderedStateEvents.forEach { event ->
            val eventId = event.eventId
            val stateKey = event.stateKey
            val type = event.type
            if (eventId == null || stateKey == null || type == null) return@forEach
            val ageLocalTs = syncTs - (event.unsignedData?.age ?: 0)
            insertEventOrIgnore(stores, event.toEntity(roomId, SendState.SYNCED, ageLocalTs), insertType)
            stores.currentStateEvent.upsert(roomId, type, stateKey, eventId, eventId)
            if (type == EventType.STATE_ROOM_MEMBER) {
                roomMemberEventHandler.handle(stores, roomId, event, isInitialSync)
            }
        }
        val membership = stores.roomMember.getByRoomAndUser(roomId, userId)?.membership ?: Membership.LEAVE
        roomEntity.membership = membership
        stores.room.upsert(roomEntity)
        roomChangeMembershipStateDataSource.setMembershipFromSync(roomId, Membership.LEAVE)
        roomSummaryUpdater.update(
                stores, roomId, membership, roomSync.summary, roomSync.unreadNotifications,
                roomSync.unreadThreadNotifications, aggregator = aggregator, removedFromRoom = removedFromRoom,
        )
    }

    /**
     * BAN, or LEAVE written by someone else (kick) — i.e. removed as opposed to leaving
     * voluntarily. Null when not removed.
     */
    private fun removalMembership(stores: SessionStores, roomId: String, syncStateEvents: List<Event>): Membership? {
        val memberEvent = syncStateEvents.lastOrNull { it.type == EventType.STATE_ROOM_MEMBER && it.stateKey == userId }
                ?: stores.currentStateEvent.getOne(roomId, EventType.STATE_ROOM_MEMBER, userId)?.root?.asDomain()
                ?: return null
        return when (memberEvent.getFixedRoomMemberContent()?.membership) {
            Membership.BAN -> Membership.BAN
            Membership.LEAVE -> Membership.LEAVE.takeIf { memberEvent.senderId != null && memberEvent.senderId != userId }
            else -> null
        }
    }

    private fun handleTimelineEvents(
            stores: SessionStores, roomId: String, eventList: List<Event>,
            prevToken: String?, isLimited: Boolean, insertType: EventInsertType, syncTs: Long,
            forceNewChunk: Boolean = false,
    ) {
        val lastChunkId = stores.chunk.lastForward(roomId)?.id
        val chunkId = when {
            forceNewChunk && lastChunkId != null -> {
                stores.chunk.setLastForward(lastChunkId, false)
                stores.chunk.insert(roomId, prevToken, null, null, null, isLastForward = true, isLastBackward = false, null, false)
            }
            !isLimited && lastChunkId != null -> lastChunkId
            else -> {
                clearRoomTimeline(stores, roomId)
                stores.chunk.insert(roomId, prevToken, null, null, null, isLastForward = true, isLastBackward = false, null, false)
            }
        }
        val isLastForward = true
        val eventIds = ArrayList<String>(eventList.size)
        val roomMemberContentsByUser = HashMap<String, RoomMemberContent?>()
        val roomMemberEventIdsByUser = HashMap<String, String?>()
        val isInitialSync = insertType == EventInsertType.INITIAL_SYNC
        val rootThreadEventIds = LinkedHashSet<String>()

        for (rawEvent in eventList) {
            val ageLocalTs = syncTs - (rawEvent.unsignedData?.age ?: 0)
            val event = rawEvent.copyAll(roomId = roomId).also { it.ageLocalTs = ageLocalTs }
            val eventId = event.eventId
            val senderId = event.senderId
            val type = event.type
            if (eventId == null || senderId == null || type == null) continue
            eventIds.add(eventId)
            if (!isInitialSync) liveEventService.get().dispatchLiveEventReceived(event, roomId)

            val entity = event.toEntity(roomId, SendState.SYNCED, ageLocalTs)
            val eventDbId = insertEventOrIgnore(stores, entity, insertType)
            val stateKey = event.stateKey
            if (stateKey != null) {
                stores.currentStateEvent.upsert(roomId, type, stateKey, eventId, eventId)
                if (type == EventType.STATE_ROOM_MEMBER) {
                    roomMemberContentsByUser[stateKey] = event.getFixedRoomMemberContent()
                    roomMemberEventIdsByUser[stateKey] = eventId
                    roomMemberEventHandler.handle(stores, roomId, event, isInitialSync)
                }
            }
            if (!roomMemberContentsByUser.containsKey(senderId)) {
                val currentMemberEvent = stores.currentStateEvent.getOne(roomId, EventType.STATE_ROOM_MEMBER, senderId)?.root?.asDomain()
                roomMemberContentsByUser[senderId] = currentMemberEvent?.getFixedRoomMemberContent()
                roomMemberEventIdsByUser[senderId] = currentMemberEvent?.eventId
            }
            stores.timelineWriter.addTimelineEvent(
                    chunkId, roomId, eventDbId, entity, isLastForward, PaginationDirection.FORWARDS,
                    roomMemberContentsByUser = roomMemberContentsByUser,
                    roomMemberEventIdsByUser = roomMemberEventIdsByUser,
            )

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
                                    roomMemberEventIdsByUser = roomMemberEventIdsByUser,
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
            stores.timelineEvent.deleteSending(roomId, eventId)
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
        stores.event.getDbId(entity.roomId, entity.eventId)?.let { dbId ->
            // See TokenChunkEventPersistor.insertEventOrIgnore: re-enqueue re-delivered relation
            // events whose insert-queue entry is gone, or their edits/reactions never aggregate.
            if (entity.content?.contains("m.relates_to") == true && !stores.eventInsert.exists(entity.eventId)) {
                stores.eventInsert.insert(entity.eventId, entity.type, true, insertType)
            }
            return dbId
        }
        stores.eventInsert.insert(entity.eventId, entity.type, true, insertType)
        return stores.event.insert(entity)
    }

    private fun clearRoomTimeline(stores: SessionStores, roomId: String) {
        stores.chunk.getByRoom(roomId).forEach { stores.timelineEvent.deleteByChunk(it.id) }
        stores.chunk.deleteByRoom(roomId)
        Timber.v("Cleared timeline for $roomId")
    }
}
