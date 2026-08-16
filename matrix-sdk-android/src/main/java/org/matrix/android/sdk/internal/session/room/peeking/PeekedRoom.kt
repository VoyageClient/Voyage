/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.peeking

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.query.QueryStateEventValue
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.identity.ThreePid
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.accountdata.RoomAccountDataEvent
import org.matrix.android.sdk.api.session.room.accountdata.RoomAccountDataService
import org.matrix.android.sdk.api.session.room.alias.AliasService
import org.matrix.android.sdk.api.session.room.call.RoomCallService
import org.matrix.android.sdk.api.session.room.crypto.RoomCryptoService
import org.matrix.android.sdk.api.session.room.location.LocationSharingService
import org.matrix.android.sdk.api.session.room.location.UpdateLiveLocationShareResult
import org.matrix.android.sdk.api.session.room.members.MembershipService
import org.matrix.android.sdk.api.session.room.members.RoomMemberQueryParams
import org.matrix.android.sdk.api.session.room.model.EventAnnotationsSummary
import org.matrix.android.sdk.api.session.room.model.GuestAccess
import org.matrix.android.sdk.api.session.room.model.LocalRoomSummary
import org.matrix.android.sdk.api.session.room.model.PowerLevelsContent
import org.matrix.android.sdk.api.session.room.model.ReadReceipt
import org.matrix.android.sdk.api.session.room.model.RoomHistoryVisibility
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import org.matrix.android.sdk.api.session.room.model.RoomJoinRulesAllowEntry
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.create.getRoomCreateContentWithSender
import org.matrix.android.sdk.api.session.room.model.livelocation.LiveLocationShareAggregatedSummary
import org.matrix.android.sdk.api.session.room.model.message.PollType
import org.matrix.android.sdk.api.session.room.model.relation.MassRedactionFloor
import org.matrix.android.sdk.api.session.room.model.relation.MassRedactionRange
import org.matrix.android.sdk.api.session.room.model.relation.PagedEventIds
import org.matrix.android.sdk.api.session.room.model.relation.RelationDefaultContent
import org.matrix.android.sdk.api.session.room.model.relation.RelationService
import org.matrix.android.sdk.api.session.room.notification.RoomNotificationState
import org.matrix.android.sdk.api.session.room.notification.RoomPushRuleService
import org.matrix.android.sdk.api.session.room.poll.LoadedPollsStatus
import org.matrix.android.sdk.api.session.room.poll.PollHistoryService
import org.matrix.android.sdk.api.session.room.powerlevels.RoomPowerLevels
import org.matrix.android.sdk.api.session.room.read.ReadService
import org.matrix.android.sdk.api.session.room.reporting.ReportingService
import org.matrix.android.sdk.api.session.room.send.DraftService
import org.matrix.android.sdk.api.session.room.send.SendService
import org.matrix.android.sdk.api.session.room.send.UserDraft
import org.matrix.android.sdk.api.session.room.state.StateService
import org.matrix.android.sdk.api.session.room.tags.TagsService
import org.matrix.android.sdk.api.session.room.threads.FetchThreadsResult
import org.matrix.android.sdk.api.session.room.threads.ThreadFilter
import org.matrix.android.sdk.api.session.room.threads.ThreadsService
import org.matrix.android.sdk.api.session.room.threads.local.ThreadsLocalService
import org.matrix.android.sdk.api.session.room.threads.model.ThreadSummary
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.TimelineService
import org.matrix.android.sdk.api.session.room.timeline.TimelineSettings
import org.matrix.android.sdk.api.session.room.typing.TypingService
import org.matrix.android.sdk.api.session.room.uploads.GetUploadsResult
import org.matrix.android.sdk.api.session.room.uploads.UploadsService
import org.matrix.android.sdk.api.session.room.version.RoomVersionService
import org.matrix.android.sdk.api.session.space.Space
import org.matrix.android.sdk.api.util.Cancelable
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.api.util.NoOpCancellable
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOptional
import org.matrix.android.sdk.internal.query.matches

private const val READ_ONLY_REASON = "Room is being previewed (read-only)"

private fun readOnly(): Nothing = throw UnsupportedOperationException(READ_ONLY_REASON)

/**
 * A read-only [Room] over a peeked world-readable room the user has not joined. Backed entirely
 * by the in-memory [PeekedRoomDataSource]; mutating operations throw, while services the timeline
 * UI calls casually (read receipts, typing, drafts…) silently no-op.
 */
internal class PeekedRoom(
        override val roomId: String,
        val viaServers: List<String>,
        private val myUserId: String,
        val dataSource: PeekedRoomDataSource,
        override val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val timelineFactory: () -> PeekTimeline,
        private val peekRoomMembersTask: PeekRoomMembersTask,
        private val peekRoomUploadsTask: PeekRoomUploadsTask,
) : Room {

    @Volatile private var timeline: PeekTimeline? = null

    private fun peekTimeline(): PeekTimeline {
        return synchronized(this) {
            released = false
            timeline?.takeIf { !it.isDisposed } ?: timelineFactory().also { timeline = it }
        }
    }

    @Volatile var released = false

    fun dispose() {
        released = true
        timeline?.dispose()
    }

    override fun getRoomSummaryFlow(): Flow<Optional<RoomSummary>> = dataSource.summaryFlow.map { it.toOptional() }

    override fun roomSummary(): RoomSummary? = dataSource.summaryFlow.value

    override fun getLocalRoomSummaryFlow(): Flow<Optional<LocalRoomSummary>> = flowOf(Optional.empty())

    override fun localRoomSummary(): LocalRoomSummary? = null

    override fun asSpace(): Space? = null

    private val timelineService = object : TimelineService {
        override fun createTimeline(eventId: String?, settings: TimelineSettings): Timeline = peekTimeline()

        override fun getTimelineEvent(eventId: String): TimelineEvent? = dataSource.getTimelineEvent(eventId)

        override suspend fun fetchEventIdForTimestamp(timestampMs: Long, forward: Boolean): String? = null

        override fun getTimelineEventFlow(eventId: String): Flow<Optional<TimelineEvent>> =
                dataSource.timelineFlow.map { list -> list.firstOrNull { it.eventId == eventId }.toOptional() }

        override fun getAttachmentMessages(): List<TimelineEvent> = emptyList()

        override fun getTimelineEventsRelatedTo(relationType: String, eventId: String): List<TimelineEvent> = emptyList()
    }

    private val stateService = object : StateService {
        override fun getStateEvent(eventType: String, stateKey: QueryStateEventValue): Event? =
                dataSource.getStateEvent(eventType, stateKey)

        override fun getStateEventFlow(eventType: String, stateKey: QueryStateEventValue): Flow<Optional<Event>> =
                getStateEventsFlow(setOf(eventType), stateKey).map { it.firstOrNull().toOptional() }

        override fun getStateEvents(eventTypes: Set<String>, stateKey: QueryStateEventValue): List<Event> =
                dataSource.getStateEvents(eventTypes, stateKey)

        override fun getStateEventsFlow(eventTypes: Set<String>, stateKey: QueryStateEventValue): Flow<List<Event>> =
                dataSource.stateEventsFlow.map { dataSource.filterStateEvents(it, eventTypes, stateKey) }

        override fun getRoomPowerLevels(): RoomPowerLevels = buildRoomPowerLevels(dataSource.stateEventsFlow.value)

        override fun getRoomPowerLevelsFlow(): Flow<RoomPowerLevels> =
                dataSource.stateEventsFlow.map { buildRoomPowerLevels(it) }

        private fun buildRoomPowerLevels(stateEvents: List<Event>): RoomPowerLevels {
            val powerLevelsEvent = dataSource.filterStateEvents(stateEvents, setOf(EventType.STATE_ROOM_POWER_LEVELS), QueryStringValue.IsEmpty)
                    .firstOrNull()
            val createEvent = dataSource.filterStateEvents(stateEvents, setOf(EventType.STATE_ROOM_CREATE), QueryStringValue.IsEmpty)
                    .firstOrNull()
            return RoomPowerLevels(
                    powerLevelsEvent?.content.toModel<PowerLevelsContent>(),
                    createEvent?.getRoomCreateContentWithSender(),
            )
        }

        override suspend fun sendStateEvent(eventType: String, stateKey: String, body: JsonDict): String = readOnly()
        override suspend fun updateTopic(topic: String, formattedTopic: String?) = readOnly()
        override suspend fun updateName(name: String) = readOnly()
        override suspend fun updateCanonicalAlias(alias: String?, altAliases: List<String>) = readOnly()
        override suspend fun updateHistoryReadability(readability: RoomHistoryVisibility) = readOnly()
        override suspend fun updateJoinRule(joinRules: RoomJoinRules?, guestAccess: GuestAccess?, allowList: List<RoomJoinRulesAllowEntry>?) = readOnly()
        override suspend fun updateAvatar(avatarUri: String, fileName: String) = readOnly()
        override suspend fun deleteAvatar() = readOnly()
        override suspend fun updateBanner(bannerUri: String, fileName: String) = readOnly()
        override suspend fun deleteBanner() = readOnly()
        override suspend fun updateMyRoomDisplayName(displayName: String?) = readOnly()
        override suspend fun updateMyRoomAvatar(avatarUri: String, fileName: String) = readOnly()
        override suspend fun resetMyRoomAvatar(avatarUrl: String?) = readOnly()
        override suspend fun updateMyRoomProfile(displayName: String?, avatarUrl: String?) = readOnly()
        override suspend fun setJoinRulePublic() = readOnly()
        override suspend fun setJoinRuleInviteOnly() = readOnly()
        override suspend fun setJoinRuleKnock() = readOnly()
        override suspend fun setJoinRuleRestricted(allowList: List<String>) = readOnly()
        override suspend fun setJoinRuleKnockRestricted(allowList: List<String>) = readOnly()
    }

    private val membershipService = object : MembershipService {
        override suspend fun loadRoomMembersIfNeeded() {
            val memberEvents = peekRoomMembersTask.execute(PeekRoomMembersTask.Params(roomId))
            dataSource.mutex.withLock {
                memberEvents.forEach { dataSource.applyStateEvent(it) }
                dataSource.publish()
            }
        }

        // A peeked room is one we are not in, so /state is refused; the peek already carries the state this
        // room can show, and its members are what there is left to fetch.
        override suspend fun loadFullRoomStateIfNeeded() = loadRoomMembersIfNeeded()

        override suspend fun areAllMembersLoaded(): Boolean = true

        override fun areAllMembersLoadedFlow(): Flow<Boolean> = flowOf(true)

        override fun getRoomMember(userId: String): RoomMemberSummary? = dataSource.getRoomMember(userId)

        override fun getRoomMembers(queryParams: RoomMemberQueryParams): List<RoomMemberSummary> =
                dataSource.membersFlow.value.filter { it.matches(queryParams) }

        override fun getRoomMembersFlow(queryParams: RoomMemberQueryParams): Flow<List<RoomMemberSummary>> =
                dataSource.membersFlow.map { members -> members.filter { it.matches(queryParams) } }

        override fun getNumberOfJoinedMembers(): Int = dataSource.summaryFlow.value.joinedMembersCount ?: 0

        private fun RoomMemberSummary.matches(queryParams: RoomMemberQueryParams): Boolean {
            if (queryParams.excludeSelf && userId == myUserId) return false
            if (membership !in queryParams.memberships) return false
            if (!queryParams.userId.matches(userId)) return false
            if (!queryParams.displayName.matches(displayName)) return false
            if (queryParams.displayNameOrUserId != QueryStringValue.NoCondition &&
                    !queryParams.displayNameOrUserId.matches(displayName) &&
                    !queryParams.displayNameOrUserId.matches(userId)) {
                return false
            }
            return true
        }

        override suspend fun invite(userId: String, reason: String?) = readOnly()
        override suspend fun invite3pid(threePid: ThreePid) = readOnly()
        override suspend fun ban(userId: String, reason: String?) = readOnly()
        override suspend fun unban(userId: String, reason: String?) = readOnly()
        override suspend fun kick(userId: String, reason: String?) = readOnly()
    }

    private val uploadsService = object : UploadsService {
        override suspend fun getUploads(numberOfEvents: Int, since: String?): GetUploadsResult {
            return peekRoomUploadsTask.execute(PeekRoomUploadsTask.Params(roomId, numberOfEvents, since)).also { result ->
                // The gallery's media viewer resolves dates / "show in timeline" through
                // getTimelineEvent; these events are usually outside the peeked timeline window.
                dataSource.cacheAuxEvents(result.uploadEvents.map { it.root })
            }
        }
    }

    private val readService = object : ReadService {
        override suspend fun markAsRead(params: ReadService.MarkAsReadParams, mainTimeLineOnly: Boolean, public: Boolean) = Unit
        override suspend fun setReadReceipt(eventId: String, threadId: String, public: Boolean) = Unit
        override suspend fun setReadMarker(fullyReadEventId: String) = Unit
        override suspend fun setMarkedUnread(markedUnread: Boolean) = Unit
        override fun isEventRead(eventId: String): Boolean = true
        override fun getReadMarkerFlow(): Flow<Optional<String>> = flowOf(Optional.empty())
        override fun getMyReadReceiptFlow(threadId: String?): Flow<Optional<String>> = flowOf(Optional.empty())
        override fun getUserReadReceipt(userId: String): String? = null
        override fun getEventReadReceiptsFlow(eventId: String): Flow<List<ReadReceipt>> = flowOf(emptyList())
    }

    private val typingService = object : TypingService {
        override fun userIsTyping() = Unit
        override fun userStopsTyping() = Unit
    }

    private val draftService = object : DraftService {
        override suspend fun saveDraft(draft: UserDraft) = Unit
        override suspend fun deleteDraft() = Unit
        override fun getDraft(): UserDraft? = null
        override fun getDraftFlow(): Flow<Optional<UserDraft>> = flowOf(Optional.empty())
    }

    private val tagsService = object : TagsService {
        override suspend fun addTag(tag: String, order: Double?) = Unit
        override suspend fun deleteTag(tag: String) = Unit
    }

    private val accountDataService = object : RoomAccountDataService {
        override fun getAccountDataEvent(type: String): RoomAccountDataEvent? = null
        override fun getAccountDataEventFlow(type: String): Flow<Optional<RoomAccountDataEvent>> = flowOf(Optional.empty())
        override fun getAccountDataEvents(types: Set<String>): List<RoomAccountDataEvent> = emptyList()
        override fun getAccountDataEventsFlow(types: Set<String>): Flow<List<RoomAccountDataEvent>> = flowOf(emptyList())
        override suspend fun updateAccountData(type: String, content: Content) = Unit
    }

    private val reportingService = object : ReportingService {
        override suspend fun reportContent(eventId: String, reason: String, score: Int?) = Unit
        override suspend fun reportRoom(reason: String) = Unit
    }

    private val roomCallService = object : RoomCallService {
        override fun canStartCall(): Boolean = false
    }

    private val roomPushRuleService = object : RoomPushRuleService {
        override fun getRoomNotificationStateFlow(): Flow<RoomNotificationState> = flowOf(RoomNotificationState.MENTIONS_ONLY)
        override suspend fun setRoomNotificationState(roomNotificationState: RoomNotificationState) = Unit
    }

    private val locationSharingService = object : LocationSharingService {
        override suspend fun sendStaticLocation(latitude: Double, longitude: Double, uncertainty: Double?, isUserLocation: Boolean): Cancelable =
                NoOpCancellable

        override suspend fun sendLiveLocation(beaconInfoEventId: String, latitude: Double, longitude: Double, uncertainty: Double?): Cancelable =
                NoOpCancellable

        override suspend fun startLiveLocationShare(timeoutMillis: Long): UpdateLiveLocationShareResult =
                UpdateLiveLocationShareResult.Failure(UnsupportedOperationException(READ_ONLY_REASON))

        override suspend fun stopLiveLocationShare(): UpdateLiveLocationShareResult =
                UpdateLiveLocationShareResult.Failure(UnsupportedOperationException(READ_ONLY_REASON))

        override suspend fun redactLiveLocationShare(beaconInfoEventId: String, reason: String?) = Unit

        override fun getRunningLiveLocationShareSummariesFlow(): Flow<List<LiveLocationShareAggregatedSummary>> = flowOf(emptyList())

        override fun getLiveLocationShareSummaryFlow(beaconInfoEventId: String): Flow<Optional<LiveLocationShareAggregatedSummary>> =
                flowOf(Optional.empty())
    }

    private val sendService = object : SendService {
        override fun sendEvent(eventType: String, content: Content?): Cancelable = readOnly()

        override fun sendTextMessage(text: CharSequence, msgType: String, autoMarkdown: Boolean, additionalContent: Content?): Cancelable = readOnly()

        override fun computeFormattedHtml(text: CharSequence, autoMarkdown: Boolean): String? = null

        override fun sendFormattedTextMessage(text: String, formattedText: String, msgType: String, additionalContent: Content?): Cancelable = readOnly()

        override fun sendQuotedTextMessage(
                quotedEvent: TimelineEvent,
                text: String,
                formattedText: String?,
                autoMarkdown: Boolean,
                rootThreadEventId: String?,
                additionalContent: Content?,
        ): Cancelable = readOnly()

        override fun sendMedia(
                attachment: ContentAttachmentData,
                compressBeforeSending: Boolean,
                roomIds: Set<String>,
                rootThreadEventId: String?,
                relatesTo: RelationDefaultContent?,
                additionalContent: Content?,
                replyToEvent: TimelineEvent?,
                captionText: CharSequence?,
                captionFormattedText: String?,
                autoMarkdown: Boolean,
        ): Cancelable = readOnly()

        override fun sendMedias(
                attachments: List<ContentAttachmentData>,
                compressBeforeSending: Boolean,
                roomIds: Set<String>,
                rootThreadEventId: String?,
                additionalContent: Content?,
                replyToEvent: TimelineEvent?,
                captionText: CharSequence?,
                captionFormattedText: String?,
                autoMarkdown: Boolean,
        ): Cancelable = readOnly()

        override fun sendGallery(
                attachments: List<ContentAttachmentData>,
                compressBeforeSending: Boolean,
                roomIds: Set<String>,
                rootThreadEventId: String?,
                additionalContent: Content?,
                replyToEvent: TimelineEvent?,
                captionText: CharSequence?,
                captionFormattedText: String?,
                autoMarkdown: Boolean,
        ): Cancelable = readOnly()

        override fun sendPoll(pollType: PollType, question: String, options: List<String>, additionalContent: Content?): Cancelable = readOnly()

        override fun voteToPoll(pollEventId: String, answerId: String, additionalContent: Content?): Cancelable = readOnly()

        override fun endPoll(pollEventId: String, additionalContent: Content?): Cancelable = readOnly()

        override fun redactEvent(event: Event, reason: String?, withRelTypes: List<String>?, additionalContent: Content?): Cancelable = readOnly()

        override fun resendTextMessage(localEcho: TimelineEvent): Cancelable = readOnly()

        override fun resendMediaMessage(localEcho: TimelineEvent): Cancelable = readOnly()

        override fun deleteFailedEcho(localEcho: TimelineEvent) = Unit

        override fun cancelSend(eventId: String) = Unit

        override fun resendAllFailedMessages() = Unit

        override fun cancelAllFailedMessages() = Unit
    }

    private val relationService = object : RelationService {
        override fun sendReaction(targetEventId: String, reaction: String): Cancelable = readOnly()

        override suspend fun undoReaction(targetEventId: String, reaction: String): Cancelable = readOnly()

        override fun editPoll(targetEvent: TimelineEvent, pollType: PollType, question: String, options: List<String>): Cancelable = readOnly()

        override fun editMediaCaption(targetEvent: TimelineEvent, newCaption: CharSequence, newFormattedCaption: String?): Cancelable = readOnly()

        override fun editTextMessage(
                targetEvent: TimelineEvent,
                msgType: String,
                newBodyText: CharSequence,
                newFormattedBodyText: CharSequence?,
                newBodyAutoMarkdown: Boolean,
                compatibilityBodyText: String,
        ): Cancelable = readOnly()

        override fun editReply(
                replyToEdit: TimelineEvent,
                originalTimelineEvent: TimelineEvent,
                newBodyText: CharSequence,
                newFormattedBodyText: String?,
                compatibilityBodyText: String,
        ): Cancelable = readOnly()

        // Only the latest edit is aggregated while peeking, so history is that edit + the original.
        override suspend fun fetchEditHistory(eventId: String): List<Event> = dataSource.editHistory(eventId)

        override suspend fun fetchReactions(eventId: String): List<Event> = emptyList()

        override suspend fun redactEventNoEcho(eventId: String, reason: String?) = readOnly()

        override suspend fun clearSendingRedactions() = Unit

        override fun getLocalEventIdsFromUser(userId: String, range: MassRedactionRange): List<String> = emptyList()

        override suspend fun fetchMoreEventIdsFromUser(
                userId: String,
                fromToken: String?,
                floor: MassRedactionFloor?,
                range: MassRedactionRange,
        ): PagedEventIds = PagedEventIds(eventIds = emptyList(), nextToken = null)

        override fun getKnownRedactionTargets(): Set<String> = emptySet()

        override suspend fun markRedactedLocally(eventIds: List<String>) = Unit

        override suspend fun getMassRedactionFloor(userId: String, notBeforeTs: Long?): MassRedactionFloor? = null

        override fun replyToMessage(
                eventReplied: TimelineEvent,
                replyText: CharSequence,
                replyFormattedText: CharSequence?,
                autoMarkdown: Boolean,
                showInThread: Boolean,
                rootThreadEventId: String?,
                msgType: String,
        ): Cancelable? = readOnly()

        override fun getEventAnnotationsSummary(eventId: String): EventAnnotationsSummary? =
                dataSource.getTimelineEvent(eventId)?.annotations

        override fun getEventAnnotationsSummaryFlow(eventId: String): Flow<Optional<EventAnnotationsSummary>> =
                dataSource.timelineFlow.map { list -> list.firstOrNull { it.eventId == eventId }?.annotations.toOptional() }

        override fun replyInThread(
                rootThreadEventId: String,
                replyInThreadText: CharSequence,
                msgType: String,
                autoMarkdown: Boolean,
                formattedText: String?,
                eventReplied: TimelineEvent?,
        ): Cancelable? = readOnly()
    }

    private val roomCryptoService = object : RoomCryptoService {
        override fun isEncrypted(): Boolean = false
        override fun encryptionAlgorithm(): String? = null
        override fun shouldEncryptForInvitedMembers(): Boolean = false
        override suspend fun enableEncryption(algorithm: String, force: Boolean) = readOnly()
        override suspend fun prepareToEncrypt() = Unit
    }

    private val roomVersionService = object : RoomVersionService {
        override fun getRoomVersion(): String = dataSource.roomCreateContent()?.roomVersion ?: "1"
        override suspend fun upgradeToVersion(version: String): String = readOnly()
        override fun getRecommendedVersion(): String = getRoomVersion()
        override fun userMayUpgradeRoom(userId: String): Boolean = false
        override fun isUsingUnstableRoomVersion(): Boolean = false
    }

    private val pollHistoryService = object : PollHistoryService {
        override val loadingPeriodInDays: Int = 0
        override fun dispose() = Unit
        override suspend fun loadMore(): LoadedPollsStatus = loadedPollsStatus()
        override suspend fun getLoadedPollsStatus(): LoadedPollsStatus = loadedPollsStatus()
        override suspend fun syncPolls() = Unit
        override fun getPollEventsFlow(): Flow<List<TimelineEvent>> = flowOf(emptyList())

        private fun loadedPollsStatus() = LoadedPollsStatus(canLoadMore = false, daysSynced = 0, hasCompletedASyncBackward = true)
    }

    private val threadsService = object : ThreadsService {
        override suspend fun fetchThreadList(nextBatchId: String?, limit: Int, filter: ThreadFilter): FetchThreadsResult =
                FetchThreadsResult.ReachedEnd

        override suspend fun getAllThreadSummaries(): List<ThreadSummary> = emptyList()

        override fun enhanceThreadWithEditions(threads: List<ThreadSummary>): List<ThreadSummary> = threads

        override suspend fun fetchThreadTimeline(rootThreadEventId: String, from: String, limit: Int) = Unit
    }

    private val threadsLocalService = object : ThreadsLocalService {
        override fun getAllThreadsFlow(): Flow<List<TimelineEvent>> = flowOf(emptyList())
        override fun getAllThreads(): List<TimelineEvent> = emptyList()
        override fun getMarkedThreadNotificationsFlow(): Flow<List<TimelineEvent>> = flowOf(emptyList())
        override fun getMarkedThreadNotifications(): List<TimelineEvent> = emptyList()
        override fun isUserParticipatingInThread(rootThreadEventId: String): Boolean = false
        override fun mapEventsWithEdition(threads: List<TimelineEvent>): List<TimelineEvent> = threads
        override suspend fun markThreadAsRead(rootThreadEventId: String) = Unit
    }

    private val aliasService = object : AliasService {
        override suspend fun getRoomAliases(): List<String> = dataSource.summaryFlow.value.aliases

        override suspend fun addAlias(aliasLocalPart: String) = readOnly()
    }

    override fun timelineService(): TimelineService = timelineService
    override fun threadsService(): ThreadsService = threadsService
    override fun threadsLocalService(): ThreadsLocalService = threadsLocalService
    override fun sendService(): SendService = sendService
    override fun draftService(): DraftService = draftService
    override fun readService(): ReadService = readService
    override fun typingService(): TypingService = typingService
    override fun aliasService(): AliasService = aliasService
    override fun tagsService(): TagsService = tagsService
    override fun membershipService(): MembershipService = membershipService
    override fun stateService(): StateService = stateService
    override fun uploadsService(): UploadsService = uploadsService
    override fun reportingService(): ReportingService = reportingService
    override fun roomCallService(): RoomCallService = roomCallService
    override fun relationService(): RelationService = relationService
    override fun roomCryptoService(): RoomCryptoService = roomCryptoService
    override fun roomPushRuleService(): RoomPushRuleService = roomPushRuleService
    override fun roomAccountDataService(): RoomAccountDataService = accountDataService
    override fun roomVersionService(): RoomVersionService = roomVersionService
    override fun locationSharingService(): LocationSharingService = locationSharingService
    override fun pollHistoryService(): PollHistoryService = pollHistoryService
}
