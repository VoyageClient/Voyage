/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import im.vector.app.core.resources.UserPreferencesProvider
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
