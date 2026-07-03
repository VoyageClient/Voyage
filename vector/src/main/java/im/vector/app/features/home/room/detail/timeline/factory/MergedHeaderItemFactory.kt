/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.factory

import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.extensions.prevOrNull
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.helper.AvatarSizeProvider
import im.vector.app.features.home.room.detail.timeline.helper.MergedTimelineEventVisibilityStateChangedListener
import im.vector.app.features.home.room.detail.timeline.helper.TimelineEventVisibilityHelper
import im.vector.app.features.home.room.detail.timeline.helper.isRoomConfiguration
import im.vector.app.features.home.room.detail.timeline.helper.timelineMergeGroupType
import im.vector.app.features.home.room.detail.timeline.item.BasedMergedItem
import im.vector.app.features.home.room.detail.timeline.item.MergedRoomCreationItem
import im.vector.app.features.home.room.detail.timeline.item.MergedRoomCreationItem_
import im.vector.app.features.home.room.detail.timeline.item.MergedSimilarEventsItem
import im.vector.app.features.home.room.detail.timeline.item.MergedSimilarEventsItem_
import im.vector.app.features.home.room.detail.timeline.tools.createLinkMovementMethod
import im.vector.lib.strings.CommonPlurals
import org.matrix.android.sdk.api.crypto.MXCRYPTO_ALGORITHM_MEGOLM
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.content.EncryptionEventContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.getRoomPowerLevels
import org.matrix.android.sdk.api.session.room.model.create.RoomCreateContent
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import javax.inject.Inject

class MergedHeaderItemFactory @Inject constructor(
        private val activeSessionHolder: ActiveSessionHolder,
        private val avatarRenderer: AvatarRenderer,
        private val avatarSizeProvider: AvatarSizeProvider,
        private val timelineEventVisibilityHelper: TimelineEventVisibilityHelper
) {

    private val mergeableEventTypes = listOf(
            EventType.STATE_ROOM_MEMBER,
            EventType.STATE_ROOM_SERVER_ACL,
            EventType.STATE_ROOM_IMAGE_PACK,
            EventType.STATE_ROOM_IMAGE_PACK_UNSTABLE,
    )
    private val collapsedEventIds = linkedSetOf<Long>()
    private val mergeItemCollapseStates = HashMap<Long, Boolean>()

    /** Bumped on every collapse toggle so cached merged-header models can detect staleness. */
    var collapseGeneration = 0
        private set

    /**
     * @param event the main timeline event
     * @param nextEvent is an older event than event
     * @param items all known items, sorted from newer event to oldest event
     * @param partialState partial state data
     * @param addDaySeparator true to add a day separator
     * @param currentPosition the current position
     * @param eventIdToHighlight if not null the event which has to be highlighted
     * @param callback callback for user event
     * @param requestModelBuild lambda to let the built Item request a model build when the collapse state is changed
     */
    fun create(
            event: TimelineEvent,
            nextEvent: TimelineEvent?,
            items: List<TimelineEvent>,
            partialState: TimelineEventController.PartialState,
            addDaySeparator: Boolean,
            currentPosition: Int,
            eventIdToHighlight: String?,
            nextDisplayableEvent: TimelineEvent?,
            callback: TimelineEventController.Callback?,
            requestModelBuild: () -> Unit
    ): BasedMergedItem<*>? {
        return when {
            isStartOfRoomCreationSummary(event, nextEvent) ->
                buildRoomCreationMergedSummary(currentPosition, items, partialState, event, eventIdToHighlight, requestModelBuild, callback)
            isStartOfSameTypeEventsSummary(event, nextEvent, partialState, addDaySeparator) ->
                buildSameTypeEventsMergedSummary(currentPosition, items, partialState, event, eventIdToHighlight, requestModelBuild, callback)
            isStartOfRedactedEventsSummary(event, nextDisplayableEvent, addDaySeparator) ->
                buildRedactedEventsMergedSummary(currentPosition, items, partialState, event, eventIdToHighlight, requestModelBuild, callback)
            isStartOfHiddenEventsSummary(event, nextEvent, partialState, addDaySeparator) ->
                buildHiddenEventsMergedSummary(currentPosition, items, partialState, event, eventIdToHighlight, requestModelBuild, callback)
            else -> null
        }
    }

    /**
     * True when [event] begins ANY kind of merged summary (room-creation, same-type membership/ACL/image-pack,
     * redacted, or hidden run). The controller uses this to exempt a merge anchor from the per-pass build
     * budget: deferring the anchor leaves its run rendered as individual (un-collapsed) events until a later
     * pass builds it — the compact<->expand flicker. [nextDisplayableEvent] is the controller's precomputed
     * next shown neighbour (keeps the redacted check O(1)).
     */
    fun startsMergedSummary(
            event: TimelineEvent,
            nextEvent: TimelineEvent?,
            nextDisplayableEvent: TimelineEvent?,
            partialState: TimelineEventController.PartialState,
            addDaySeparator: Boolean,
    ): Boolean {
        return isStartOfRoomCreationSummary(event, nextEvent) ||
                isStartOfSameTypeEventsSummary(event, nextEvent, partialState, addDaySeparator) ||
                isStartOfRedactedEventsSummary(event, nextDisplayableEvent, addDaySeparator) ||
                isStartOfHiddenEventsSummary(event, nextEvent, partialState, addDaySeparator)
    }

    /**
     * @param event the main timeline event
     * @param nextEvent is an older event than event
     * @param addDaySeparator true to add a day separator
     */
    private fun isStartOfHiddenEventsSummary(
            event: TimelineEvent,
            nextEvent: TimelineEvent?,
            partialState: TimelineEventController.PartialState,
            addDaySeparator: Boolean,
    ): Boolean {
        return isHiddenRunMember(event, partialState) &&
                (nextEvent == null || addDaySeparator || !isHiddenRunMember(nextEvent, partialState))
    }

    // A redacted event that is also "hidden" (e.g. a redacted m.room.redaction) is owned by the redacted
    // summary (checked first in create()), so it must NOT extend a hidden run — otherwise the boundary between
    // the two never forms and the hidden events before it are left with no anchor / header.
    private fun isHiddenRunMember(event: TimelineEvent, partialState: TimelineEventController.PartialState): Boolean {
        return !event.root.isRedacted() &&
                timelineEventVisibilityHelper.isHiddenEvent(event, partialState.rootThreadEventId, partialState.isFromThreadTimeline())
    }

    /**
     * @param event the main timeline event
     * @param nextEvent is an older event than event
     */
    private fun isStartOfRoomCreationSummary(
            event: TimelineEvent,
            nextEvent: TimelineEvent?,
    ): Boolean {
        // It's the first item before room.create
        // Collapse all room configuration events
        return nextEvent?.root?.getClearType() == EventType.STATE_ROOM_CREATE &&
                event.isRoomConfiguration(nextEvent.root.getClearContent()?.toModel<RoomCreateContent>()?.creator)
    }

    /**
     * @param event the main timeline event
     * @param nextEvent is an older event than event
     * @param addDaySeparator true to add a day separator
     */
    private fun isStartOfSameTypeEventsSummary(
            event: TimelineEvent,
            nextEvent: TimelineEvent?,
            partialState: TimelineEventController.PartialState,
            addDaySeparator: Boolean,
    ): Boolean {
        // Hidden/debug events (e.g. repeated knocks) must not start nor join a membership-changes merge;
        // they are compacted separately as a "hidden events" summary instead.
        if (timelineEventVisibilityHelper.isHiddenEvent(event, partialState.rootThreadEventId, partialState.isFromThreadTimeline())) {
            return false
        }
        return event.root.getClearType() in mergeableEventTypes &&
                (nextEvent?.root?.timelineMergeGroupType() != event.root.timelineMergeGroupType() || addDaySeparator)
    }

    /**
     * @param event the main timeline event
     * @param items all known items, sorted from newer event to oldest event
     * @param currentPosition the current position
     * @param partialState partial state data
     * @param addDaySeparator true to add a day separator
     */
    private fun isStartOfRedactedEventsSummary(
            event: TimelineEvent,
            nextDisplayableEvent: TimelineEvent?,
            addDaySeparator: Boolean,
    ): Boolean {
        // [nextDisplayableEvent] is precomputed once per pass by the controller (O(n) total); scanning for it
        // here per redacted event made passes O(n²) — catastrophic in a room full of redactions.
        if (!event.root.isRedacted()) return false
        // nextDisplayableEvent == null means the run reaches the bottom of what's loaded — treat that as a
        // boundary too, so the redactions collapse immediately instead of only after scrolling up loads an
        // older non-redacted event below them.
        return nextDisplayableEvent == null || nextDisplayableEvent.root.isRedacted() == false || addDaySeparator
    }

    private fun buildSameTypeEventsMergedSummary(
            currentPosition: Int,
            items: List<TimelineEvent>,
            partialState: TimelineEventController.PartialState,
            event: TimelineEvent,
            eventIdToHighlight: String?,
            requestModelBuild: () -> Unit,
            callback: TimelineEventController.Callback?
    ): MergedSimilarEventsItem_? {
        val mergedEvents = timelineEventVisibilityHelper.prevSameTypeEvents(
                items,
                currentPosition,
                MIN_NUMBER_OF_MERGED_EVENTS,
                eventIdToHighlight,
                partialState.rootThreadEventId,
                partialState.isFromThreadTimeline()
        )
        return buildSimilarEventsMergedSummary(mergedEvents, partialState, event, eventIdToHighlight, requestModelBuild, callback)
    }

    private fun buildRedactedEventsMergedSummary(
            currentPosition: Int,
            items: List<TimelineEvent>,
            partialState: TimelineEventController.PartialState,
            event: TimelineEvent,
            eventIdToHighlight: String?,
            requestModelBuild: () -> Unit,
            callback: TimelineEventController.Callback?
    ): MergedSimilarEventsItem_? {
        val mergedEvents = timelineEventVisibilityHelper.prevRedactedEvents(
                items,
                currentPosition,
                MIN_NUMBER_OF_MERGED_EVENTS,
                eventIdToHighlight,
                partialState.rootThreadEventId,
                partialState.isFromThreadTimeline()
        )
        return buildSimilarEventsMergedSummary(mergedEvents, partialState, event, eventIdToHighlight, requestModelBuild, callback)
    }

    private fun buildHiddenEventsMergedSummary(
            currentPosition: Int,
            items: List<TimelineEvent>,
            partialState: TimelineEventController.PartialState,
            event: TimelineEvent,
            eventIdToHighlight: String?,
            requestModelBuild: () -> Unit,
            callback: TimelineEventController.Callback?
    ): MergedSimilarEventsItem_? {
        val mergedEvents = timelineEventVisibilityHelper.prevHiddenEvents(
                items,
                currentPosition,
                MIN_NUMBER_OF_MERGED_EVENTS,
                partialState.rootThreadEventId,
                partialState.isFromThreadTimeline()
        )
        return buildSimilarEventsMergedSummary(
                mergedEvents, partialState, event, eventIdToHighlight, requestModelBuild, callback, CommonPlurals.merged_hidden_events
        )
    }

    private fun buildSimilarEventsMergedSummary(
            mergedEvents: List<TimelineEvent>,
            partialState: TimelineEventController.PartialState,
            event: TimelineEvent,
            eventIdToHighlight: String?,
            requestModelBuild: () -> Unit,
            callback: TimelineEventController.Callback?,
            forcedSummaryTitleResId: Int? = null
    ): MergedSimilarEventsItem_? {
        return if (mergedEvents.isEmpty()) {
            null
        } else {
            var highlighted = false
            val mergedData = ArrayList<BasedMergedItem.Data>(mergedEvents.size)
            mergedEvents.forEach { mergedEvent ->
                if (!highlighted && mergedEvent.root.eventId == eventIdToHighlight) {
                    highlighted = true
                }
                val data = BasedMergedItem.Data(
                        roomId = mergedEvent.root.roomId,
                        userId = mergedEvent.root.senderId ?: "",
                        avatarUrl = mergedEvent.senderInfo.avatarUrl,
                        memberName = mergedEvent.senderInfo.disambiguatedDisplayName,
                        localId = mergedEvent.localId,
                        eventId = mergedEvent.root.eventId ?: "",
                        isDirectRoom = partialState.isDirectRoom()
                )
                mergedData.add(data)
            }
            val mergedEventIds = mergedEvents.map { it.localId }.toSet()
            // We try to find if one of the item id were used as mergeItemCollapseStates key
            // => handle case where paginating from mergeable events and we get more
            val previousCollapseStateKey = mergedEventIds.intersect(mergeItemCollapseStates.keys).firstOrNull()
            val initialCollapseState = mergeItemCollapseStates.remove(previousCollapseStateKey)
                    ?: true
            val isCollapsed = mergeItemCollapseStates.getOrPut(event.localId) { initialCollapseState }
            if (isCollapsed) {
                collapsedEventIds.addAll(mergedEventIds)
            } else {
                collapsedEventIds.removeAll(mergedEventIds)
            }
            // Anchor the epoxy id on the NEWEST event of the run. The merge itself anchors on the oldest
            // event (whose older neighbour is non-redacted), which moves every time older events paginate in
            // — so keying the id on it made epoxy replace the whole item each fetch (the compact<->expand
            // flicker). The newest member is stable under backward pagination, so the item just rebinds.
            val stableAnchorId = mergedEvents.maxByOrNull { it.root.originServerTs ?: 0L }?.localId ?: event.localId
            val mergeId = "merged_$stableAnchorId"
            (forcedSummaryTitleResId ?: getSummaryTitleResId(event.root))?.let { summaryTitle ->
                val attributes = MergedSimilarEventsItem.Attributes(
                        summaryTitleResId = summaryTitle,
                        isCollapsed = isCollapsed,
                        mergeData = mergedData,
                        avatarRenderer = avatarRenderer,
                        onCollapsedStateChanged = {
                            mergeItemCollapseStates[event.localId] = it
                            collapseGeneration++
                            requestModelBuild()
                        }
                )
                MergedSimilarEventsItem_()
                        .id(mergeId)
                        .leftGuideline(avatarSizeProvider.leftGuideline)
                        .highlighted(isCollapsed && highlighted)
                        .attributes(attributes)
                        .also {
                            it.setOnVisibilityStateChanged(MergedTimelineEventVisibilityStateChangedListener(callback, mergedEvents))
                        }
            }
        }
    }

    private fun getSummaryTitleResId(event: Event): Int? {
        val type = event.getClearType()
        return when {
            type == EventType.STATE_ROOM_MEMBER -> CommonPlurals.membership_changes
            type == EventType.STATE_ROOM_SERVER_ACL -> CommonPlurals.notice_room_server_acl_changes
            type == EventType.STATE_ROOM_IMAGE_PACK ||
                    type == EventType.STATE_ROOM_IMAGE_PACK_UNSTABLE -> CommonPlurals.image_pack_changes
            event.isRedacted() -> CommonPlurals.room_redacted_messages
            else -> null
        }
    }

    private fun buildRoomCreationMergedSummary(
            currentPosition: Int,
            items: List<TimelineEvent>,
            partialState: TimelineEventController.PartialState,
            event: TimelineEvent,
            eventIdToHighlight: String?,
            requestModelBuild: () -> Unit,
            callback: TimelineEventController.Callback?
    ): MergedRoomCreationItem_? {
        var prevEvent = items.prevOrNull(currentPosition)
        var tmpPos = currentPosition - 1
        val mergedEvents = mutableListOf(event)
        var hasEncryption = false
        var encryptionAlgorithm: String? = null
        while (prevEvent != null && prevEvent.isRoomConfiguration(null)) {
            if (prevEvent.root.isStateEvent() && prevEvent.root.getClearType() == EventType.STATE_ROOM_ENCRYPTION) {
                hasEncryption = true
                encryptionAlgorithm = prevEvent.root.getClearContent()?.toModel<EncryptionEventContent>()?.algorithm
            }
            mergedEvents.add(prevEvent)
            tmpPos--
            prevEvent = items.getOrNull(tmpPos)
        }
        return if (mergedEvents.size > MIN_NUMBER_OF_MERGED_EVENTS) {
            var highlighted = false
            val mergedData = ArrayList<BasedMergedItem.Data>(mergedEvents.size)
            mergedEvents.reversed()
                    .forEach { mergedEvent ->
                        if (!highlighted && mergedEvent.root.eventId == eventIdToHighlight) {
                            highlighted = true
                        }
                        val data = BasedMergedItem.Data(
                                roomId = mergedEvent.root.roomId,
                                userId = mergedEvent.root.senderId ?: "",
                                avatarUrl = mergedEvent.senderInfo.avatarUrl,
                                memberName = mergedEvent.senderInfo.disambiguatedDisplayName,
                                localId = mergedEvent.localId,
                                eventId = mergedEvent.root.eventId ?: "",
                                isDirectRoom = partialState.isDirectRoom()
                        )
                        mergedData.add(data)
                    }
            val mergedEventIds = mergedEvents.map { it.localId }
            // We try to find if one of the item id were used as mergeItemCollapseStates key
            // => handle case where paginating from mergeable events and we get more
            val previousCollapseStateKey = mergedEventIds.intersect(mergeItemCollapseStates.keys).firstOrNull()
            val initialCollapseState = mergeItemCollapseStates.remove(previousCollapseStateKey)
                    ?: true
            val isCollapsed = mergeItemCollapseStates.getOrPut(event.localId) { initialCollapseState }
            if (isCollapsed) {
                collapsedEventIds.addAll(mergedEventIds)
            } else {
                collapsedEventIds.removeAll(mergedEventIds)
            }
            val mergeId = mergedEventIds.joinToString(separator = "_") { it.toString() }
            val roomPowerLevels = activeSessionHolder.getSafeActiveSession()?.getRoom(event.roomId)?.getRoomPowerLevels()
            val currentUserId = activeSessionHolder.getSafeActiveSession()?.myUserId ?: ""
            val attributes = MergedRoomCreationItem.Attributes(
                    isCollapsed = isCollapsed,
                    mergeData = mergedData,
                    avatarRenderer = avatarRenderer,
                    onCollapsedStateChanged = {
                        mergeItemCollapseStates[event.localId] = it
                        collapseGeneration++
                        requestModelBuild()
                    },
                    hasEncryptionEvent = hasEncryption,
                    isEncryptionAlgorithmSecure = encryptionAlgorithm == MXCRYPTO_ALGORITHM_MEGOLM,
                    callback = callback,
                    currentUserId = currentUserId,
                    roomSummary = partialState.roomSummary,
                    canInvite = roomPowerLevels?.isUserAbleToInvite(currentUserId) ?: false,
                    canChangeAvatar = roomPowerLevels?.isUserAllowedToSend(currentUserId, true, EventType.STATE_ROOM_AVATAR) ?: false,
                    canChangeTopic = roomPowerLevels?.isUserAllowedToSend(currentUserId, true, EventType.STATE_ROOM_TOPIC) ?: false,
                    canChangeName = roomPowerLevels?.isUserAllowedToSend(currentUserId, true, EventType.STATE_ROOM_NAME) ?: false
            )
            MergedRoomCreationItem_()
                    .id(mergeId)
                    .leftGuideline(avatarSizeProvider.leftGuideline)
                    .highlighted(isCollapsed && highlighted)
                    .attributes(attributes)
                    .movementMethod(createLinkMovementMethod(callback))
                    .also {
                        it.setOnVisibilityStateChanged(MergedTimelineEventVisibilityStateChangedListener(callback, mergedEvents))
                    }
        } else null
    }

    private fun TimelineEventController.PartialState.isDirectRoom(): Boolean {
        return roomSummary?.isDirect.orFalse()
    }

    fun isCollapsed(localId: Long): Boolean {
        return collapsedEventIds.contains(localId)
    }

    companion object {
        private const val MIN_NUMBER_OF_MERGED_EVENTS = 2
    }
}
