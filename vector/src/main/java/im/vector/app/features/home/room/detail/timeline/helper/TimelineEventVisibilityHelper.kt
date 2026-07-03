/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import im.vector.app.core.extensions.localDateTime
import im.vector.app.core.resources.UserPreferencesProvider
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.getRelationContent
import org.matrix.android.sdk.api.session.events.model.getRootThreadEventId
import org.matrix.android.sdk.api.session.events.model.isThread
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.session.room.model.localecho.RoomLocalEcho
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import javax.inject.Inject

class TimelineEventVisibilityHelper @Inject constructor(
        private val userPreferencesProvider: UserPreferencesProvider,
) {

    private interface PredicateToStopSearch {
        /**
         * Indicate whether a search on events should stop by comparing 2 given successive events.
         * @param oldEvent the oldest event between the 2 events to compare
         * @param newEvent the more recent event between the 2 events to compare
         */
        fun shouldStopSearch(oldEvent: Event, newEvent: Event): Boolean
    }

    /**
     * @param timelineEvents the events to search in, sorted from newer event to oldest event
     * @param index the index to start computing (inclusive)
     * @param minSize the minimum number of same type events to have sequentially, otherwise will return an empty list
     * @param eventIdToHighlight used to compute visibility
     * @param rootThreadEventId the root thread eventId
     * @param isFromThreadTimeline true if the timeline is a thread
     *
     * @return a list of timeline events which have sequentially the same type following the prev direction.
     */
    fun prevSameTypeEvents(
            timelineEvents: List<TimelineEvent>,
            index: Int,
            minSize: Int,
            eventIdToHighlight: String?,
            rootThreadEventId: String?,
            isFromThreadTimeline: Boolean
    ): List<TimelineEvent> {
        return collectPrevRun(
                timelineEvents, index, minSize, eventIdToHighlight, rootThreadEventId, isFromThreadTimeline,
                excludeHiddenEvents = true, skipNonDisplayable = false,
                predicateToStop = object : PredicateToStopSearch {
                    override fun shouldStopSearch(oldEvent: Event, newEvent: Event): Boolean {
                        return oldEvent.timelineMergeGroupType() != newEvent.timelineMergeGroupType()
                    }
                })
    }

    /**
     * Collect a merged run ending at [index] (the anchor / oldest event), walking toward newer events (index
     * decreasing) within the same day until [predicateToStop] fires against the anchor. O(run length) — no
     * whole-prefix subList/filter/reverse, so re-enriching many anchors stays O(total events), not O(n²).
     *
     * @param excludeHiddenEvents drop hidden events from the collected result (they still count for stops).
     * @param skipNonDisplayable when true, non-shown events are ignored entirely (not collected, don't break
     *   the run nor the day scan) — matches the redacted grouping which pre-filters to displayable events.
     */
    private fun collectPrevRun(
            timelineEvents: List<TimelineEvent>,
            index: Int,
            minSize: Int,
            eventIdToHighlight: String?,
            rootThreadEventId: String?,
            isFromThreadTimeline: Boolean,
            excludeHiddenEvents: Boolean,
            skipNonDisplayable: Boolean,
            predicateToStop: PredicateToStopSearch,
    ): List<TimelineEvent> {
        val anchor = timelineEvents.getOrNull(index) ?: return emptyList()
        val anchorDate = anchor.root.localDateTime().toLocalDate()
        val collected = ArrayList<TimelineEvent>()
        var i = index
        while (i >= 0) {
            val candidate = timelineEvents[i]
            val shown = shouldShowEvent(candidate, eventIdToHighlight, isFromThreadTimeline, rootThreadEventId) &&
                    !(excludeHiddenEvents && isHiddenEvent(candidate, rootThreadEventId, isFromThreadTimeline))
            if (skipNonDisplayable && !shown) { i--; continue }
            if (candidate.root.localDateTime().toLocalDate() != anchorDate) break
            if (predicateToStop.shouldStopSearch(anchor.root, candidate.root)) break
            if (shown) collected.add(candidate)
            i--
        }
        return if (collected.size < minSize) emptyList() else collected
    }

    /**
     * @param timelineEvents the events to search in, sorted from newer event to oldest event
     * @param index the index to start computing (inclusive)
     * @param minSize the minimum number of same type events to have sequentially, otherwise will return an empty list
     * @param eventIdToHighlight used to compute visibility
     * @param rootThreadEventId the root thread eventId
     * @param isFromThreadTimeline true if the timeline is a thread
     *
     * @return a list of timeline events which are all redacted following the prev direction.
     */
    fun prevRedactedEvents(
            timelineEvents: List<TimelineEvent>,
            index: Int,
            minSize: Int,
            eventIdToHighlight: String?,
            rootThreadEventId: String?,
            isFromThreadTimeline: Boolean
    ): List<TimelineEvent> {
        return collectPrevRun(
                timelineEvents, index, minSize, eventIdToHighlight, rootThreadEventId, isFromThreadTimeline,
                excludeHiddenEvents = false, skipNonDisplayable = true,
                predicateToStop = object : PredicateToStopSearch {
                    override fun shouldStopSearch(oldEvent: Event, newEvent: Event): Boolean {
                        return !newEvent.isRedacted()
                    }
                })
    }

    /**
     * Collect the consecutive run of "hidden" events ending at [index], following the prev (newer)
     * direction within the same day. Used to compact debug/hidden events when they are surfaced by
     * the "show hidden events" developer setting.
     *
     * @param timelineEvents the events to search in, sorted from newer event to oldest event
     * @param index the index to start computing (inclusive)
     * @param minSize the minimum number of hidden events to have sequentially, otherwise returns an empty list
     */
    fun prevHiddenEvents(
            timelineEvents: List<TimelineEvent>,
            index: Int,
            minSize: Int,
            rootThreadEventId: String?,
            isFromThreadTimeline: Boolean
    ): List<TimelineEvent> {
        val startEvent = timelineEvents.getOrNull(index) ?: return emptyList()
        val startDate = startEvent.root.localDateTime().toLocalDate()
        val result = mutableListOf<TimelineEvent>()
        var i = index
        while (i >= 0) {
            val candidate = timelineEvents[i]
            if (candidate.root.localDateTime().toLocalDate() != startDate) break
            // A redacted event is owned by the redacted summary, not the hidden one (see isHiddenRunMember);
            // stop the hidden run at it so the two groupings agree on the boundary.
            if (candidate.root.isRedacted() || !isHiddenEvent(candidate, rootThreadEventId, isFromThreadTimeline)) break
            result.add(candidate)
            i--
        }
        return if (result.size < minSize) emptyList() else result
    }

    /**
     * An event is "hidden" when it is only surfaced because of the "show hidden events" developer
     * setting, i.e. it would not be displayed at all with that setting off.
     */
    fun isHiddenEvent(
            timelineEvent: TimelineEvent,
            rootThreadEventId: String?,
            isFromThreadTimeline: Boolean
    ): Boolean {
        if (isFromThreadTimeline || !userPreferencesProvider.shouldShowHiddenEvents()) {
            return false
        }
        return !timelineEvent.isDisplayable() || timelineEvent.shouldBeHidden(rootThreadEventId, isFromThreadTimeline)
    }

    /**
     * @param timelineEvent the event to check for visibility
     * @param highlightedEventId can be checked to force visibility to true
     * @param isFromThreadTimeline true if the timeline is a thread
     * @param rootThreadEventId if this param is null it means we are in the original timeline
     * @return true if the event should be shown in the timeline.
     */
    fun shouldShowEvent(
            timelineEvent: TimelineEvent,
            highlightedEventId: String?,
            isFromThreadTimeline: Boolean,
            rootThreadEventId: String?,
            forcedVisibleEventIds: Set<String> = emptySet()
    ): Boolean {
        // A media "edit" that changed the media itself is rejected as an edit and shown as its own
        // message, so it must not be hidden by the replace-relation rule below.
        if (timelineEvent.eventId in forcedVisibleEventIds) {
            return true
        }
        // If show hidden events is true we should always display something
        if (userPreferencesProvider.shouldShowHiddenEvents() && !isFromThreadTimeline) {
            return true
        }
        // We always show highlighted event
        if (timelineEvent.eventId == highlightedEventId) {
            return true
        }
        if (!timelineEvent.isDisplayable()) {
            return false
        }

        // Check for special case where we should hide the event, like redacted, relation, memberships... according to user preferences.
        return !timelineEvent.shouldBeHidden(rootThreadEventId, isFromThreadTimeline)
    }

    private fun TimelineEvent.isDisplayable(): Boolean {
        return TimelineDisplayableEvents.DISPLAYABLE_TYPES.contains(root.getClearType())
    }

    private fun TimelineEvent.shouldBeHidden(rootThreadEventId: String?, isFromThreadTimeline: Boolean): Boolean {
        if (root.isRedacted() && !userPreferencesProvider.shouldShowRedactedMessages() && root.threadDetails?.isRootThread == false) {
            return true
        }

        // We should not display deleted thread messages within the normal timeline
        if (root.isRedacted() &&
                userPreferencesProvider.areThreadMessagesEnabled() &&
                !isFromThreadTimeline &&
                (root.isThread() || root.threadDetails?.isThread == true)) {
            return true
        }
        if (root.isRedacted() &&
                !userPreferencesProvider.shouldShowRedactedMessages() &&
                userPreferencesProvider.areThreadMessagesEnabled() &&
                isFromThreadTimeline &&
                root.isThread()) {
            return true
        }

        if (root.getRelationContent()?.type == RelationType.REPLACE) {
            return true
        }
        if (root.getClearType() == EventType.STATE_ROOM_MEMBER) {
            val diff = computeMembershipDiff()
            if ((diff.isJoin || diff.isPart) && !userPreferencesProvider.shouldShowJoinLeaves()) return true
            if ((diff.isAvatarChange || diff.isDisplaynameChange) && !userPreferencesProvider.shouldShowAvatarDisplayNameChanges()) return true
            // A repeated knock (re-request to join, no membership change) is a debug event: hidden by
            // default, only the first knock is a real membership transition that gets displayed.
            if (diff.isRepeatedKnock) return true
        }

        if (userPreferencesProvider.areThreadMessagesEnabled() && !isFromThreadTimeline && root.isThread()) {
            return true
        }

        // Hide fake events for local rooms
        if (RoomLocalEcho.isLocalEchoId(roomId) &&
                (root.getClearType() == EventType.STATE_ROOM_MEMBER ||
                        root.getClearType() == EventType.STATE_ROOM_HISTORY_VISIBILITY ||
                        root.getClearType() == EventType.STATE_ROOM_THIRD_PARTY_INVITE)) {
            return true
        }

        // Allow only the the threads within the rootThreadEventId along with the root event
        if (userPreferencesProvider.areThreadMessagesEnabled() && isFromThreadTimeline) {
            return if (root.getRootThreadEventId() == rootThreadEventId) {
                false
            } else root.eventId != rootThreadEventId
        }

        if (root.getClearType() in EventType.BEACON_LOCATION_DATA.values) {
            return !root.isRedacted()
        }

        return false
    }

    private fun TimelineEvent.computeMembershipDiff(): MembershipDiff {
        val content = root.getClearContent().toModel<RoomMemberContent>()
        val prevContent = root.resolvedPrevContent().toModel<RoomMemberContent>()

        val isMembershipChanged = content?.membership != prevContent?.membership
        val isJoin = isMembershipChanged && content?.membership == Membership.JOIN
        val isPart = isMembershipChanged && content?.membership == Membership.LEAVE && root.stateKey == root.senderId

        val isProfileChanged = !isMembershipChanged && content?.membership == Membership.JOIN
        val isDisplaynameChange = isProfileChanged && content?.displayName != prevContent?.displayName
        val isAvatarChange = isProfileChanged && content?.avatarUrl !== prevContent?.avatarUrl

        val isRepeatedKnock = !isMembershipChanged && content?.membership == Membership.KNOCK

        return MembershipDiff(
                isJoin = isJoin,
                isPart = isPart,
                isDisplaynameChange = isDisplaynameChange,
                isAvatarChange = isAvatarChange,
                isRepeatedKnock = isRepeatedKnock
        )
    }

    private data class MembershipDiff(
            val isJoin: Boolean,
            val isPart: Boolean,
            val isDisplaynameChange: Boolean,
            val isAvatarChange: Boolean,
            val isRepeatedKnock: Boolean
    )
}
