/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import im.vector.app.ActiveSessionDataSource
import im.vector.app.core.resources.UserPreferencesProvider
import im.vector.app.features.redaction.preservation.RedactedContentRestorer
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
        private val redactedContentRestorer: RedactedContentRestorer,
        private val activeSessionDataSource: ActiveSessionDataSource,
) {

    private val unknownThreadRootId = ""

    private fun TimelineEvent.relationTargetId(): String? = if (root.getClearType() == EventType.REDACTION) {
        root.redacts ?: root.content?.get("redacts") as? String
    } else {
        root.getRelationContent()?.eventId
    }

    private fun TimelineEvent.isNonThreadRelationToRoot(rootThreadEventId: String?): Boolean =
            rootThreadEventId != null && relationTargetId() == rootThreadEventId && !root.isThread()

    private fun TimelineEvent.threadRootId(rootThreadEventId: String?, isFromThreadTimeline: Boolean): String? {
        if (isFromThreadTimeline && isNonThreadRelationToRoot(rootThreadEventId)) return null
        if (isFromThreadTimeline && ownedByThreadChunk) return rootThreadEventId
        root.getRootThreadEventId()?.let { return it }
        var targetId = relationTargetId()
        val eventService = activeSessionDataSource.currentValue?.orNull()?.eventService() ?: return null
        val visited = HashSet<String>()
        while (targetId != null && visited.size < 64 && visited.add(targetId)) {
            val target = eventService.getEventFromCache(roomId, targetId)
            if (target == null) return null
            target.getRootThreadEventId()?.let { return it }
            if (target.threadDetails?.isRootThread == true) return null
            // Redaction can remove the relation while leaving the cached "is thread" flag intact.
            if (target.threadDetails?.isThread == true) return unknownThreadRootId
            val nextId = if (target.getClearType() == EventType.REDACTION) {
                target.redacts ?: target.content?.get("redacts") as? String
            } else {
                target.getRelationContent()?.eventId
            }
            targetId = nextId
        }
        return null
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
        if (timelineEvent.isHiddenByVisibilityRules()) return false
        if (timelineEvent.root.getClearType() == EventType.REACTION ||
                timelineEvent.root.getClearType() == EventType.STATE_ROOM_SERVER_ACL) {
            return false
        }
        if (isFromThreadTimeline || !userPreferencesProvider.shouldShowHiddenEvents(timelineEvent.roomId)) {
            return false
        }
        val threadRootId = timelineEvent.threadRootId(rootThreadEventId, isFromThreadTimeline)
        if (threadRootId != null && !userPreferencesProvider.shouldShowHiddenThreadEvents(timelineEvent.roomId)) return false
        return !timelineEvent.isDisplayable() || timelineEvent.shouldBeHidden(rootThreadEventId, isFromThreadTimeline, threadRootId)
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
        if (isFromThreadTimeline && timelineEvent.isNonThreadRelationToRoot(rootThreadEventId)) return false
        if (timelineEvent.isHiddenByVisibilityRules()) {
            val type = timelineEvent.root.getClearType()
            if (type == EventType.REACTION || type == EventType.STATE_ROOM_SERVER_ACL ||
                    (timelineEvent.eventId != highlightedEventId && timelineEvent.eventId !in forcedVisibleEventIds)) {
                return false
            }
        }
        val threadRootId = timelineEvent.threadRootId(rootThreadEventId, isFromThreadTimeline)
        if (isFromThreadTimeline && threadRootId != null && threadRootId != rootThreadEventId) return false
        if (!isFromThreadTimeline && threadRootId != null && !userPreferencesProvider.shouldShowHiddenThreadEvents(timelineEvent.roomId)) return false
        // A media "edit" that changed the media itself is rejected as an edit and shown as its own
        // message, so it must not be hidden by the replace-relation rule below.
        if (timelineEvent.eventId in forcedVisibleEventIds) {
            return true
        }
        // If show hidden events is true we should always display something
        if (userPreferencesProvider.shouldShowHiddenEvents(timelineEvent.roomId) &&
                (!isFromThreadTimeline || timelineEvent.ownedByThreadChunk)) {
            return true
        }
        // We always show highlighted event
        if (timelineEvent.eventId == highlightedEventId) {
            return true
        }
        if (!timelineEvent.isDisplayable()) {
            return false
        }

        return !timelineEvent.shouldBeHidden(rootThreadEventId, isFromThreadTimeline, threadRootId)
    }

    private fun TimelineEvent.isDisplayable(): Boolean {
        return (root.getClearType() == EventType.REACTION && userPreferencesProvider.shouldShowReactions(roomId)) ||
                TimelineDisplayableEvents.DISPLAYABLE_TYPES.contains(root.getClearType())
    }

    private fun TimelineEvent.shouldBeHidden(rootThreadEventId: String?, isFromThreadTimeline: Boolean, threadRootId: String?): Boolean {
        // A revealed message is showing real content, so the "hide deleted messages" rules below
        // must not apply to it — they would make revealing appear to do nothing at all. Unless what
        // it reveals is an edit or reaction: their content shows applied on the target message, so
        // the raw event hides like its unredacted self (visible only through show-hidden-events).
        if (redactedContentRestorer.isShowingRestoredContent(this)) {
            return redactedContentRestorer.isRevealedHiddenEvent(this)
        }

        // We should not display deleted thread messages within the normal timeline
        if (root.isRedacted() &&
                userPreferencesProvider.areThreadMessagesEnabled() &&
                !isFromThreadTimeline &&
                (root.isThread() || root.threadDetails?.isThread == true)) {
            return true
        }
        if (root.isRedacted() &&
                !userPreferencesProvider.shouldShowRedactedMessages(roomId) &&
                userPreferencesProvider.areThreadMessagesEnabled() &&
                isFromThreadTimeline &&
                root.isThread()) {
            return true
        }

        if (root.getRelationContent()?.type == RelationType.REPLACE) {
            return true
        }

        if (userPreferencesProvider.areThreadMessagesEnabled() && !isFromThreadTimeline && threadRootId != null) {
            return true
        }

        // Hide fake events for local rooms
        if (RoomLocalEcho.isLocalEchoId(roomId) &&
                (root.getClearType() == EventType.STATE_ROOM_MEMBER ||
                        root.getClearType() == EventType.STATE_ROOM_HISTORY_VISIBILITY ||
                        root.getClearType() == EventType.STATE_ROOM_THIRD_PARTY_INVITE)) {
            return true
        }

        // Allow only threads within the rootThreadEventId along with the root event
        if (userPreferencesProvider.areThreadMessagesEnabled() && isFromThreadTimeline) {
            return if (threadRootId == rootThreadEventId) {
                false
            } else root.eventId != rootThreadEventId
        }

        if (root.getClearType() in EventType.BEACON_LOCATION_DATA.values) {
            return !root.isRedacted()
        }

        return false
    }

    private fun TimelineEvent.isHiddenByVisibilityRules(): Boolean {
        if (root.isRedacted() && !redactedContentRestorer.isShowingRestoredContent(this) &&
                !userPreferencesProvider.shouldShowRedactedMessages(roomId) && root.threadDetails?.isRootThread == false) {
            return true
        }
        return when (root.getClearType()) {
            EventType.REACTION -> !userPreferencesProvider.shouldShowReactions(roomId)
            EventType.STATE_ROOM_SERVER_ACL -> !userPreferencesProvider.shouldShowAclEvents(roomId)
            EventType.STATE_ROOM_THIRD_PARTY_INVITE -> !userPreferencesProvider.shouldShowMembershipEvents(roomId)
            EventType.STATE_ROOM_MEMBER -> {
                if (isProfileChange()) {
                    !userPreferencesProvider.shouldShowAvatarDisplayNameChanges(roomId)
                } else {
                    !userPreferencesProvider.shouldShowMembershipEvents(roomId)
                }
            }
            in TimelineDisplayableEvents.ROOM_SETTINGS_TYPES -> !userPreferencesProvider.shouldShowRoomSettingsEvents(roomId)
            else -> false
        }
    }

    private fun TimelineEvent.isProfileChange(): Boolean {
        val content = root.getClearContent().toModel<RoomMemberContent>()
        val previous = root.resolvedPrevContent().toModel<RoomMemberContent>()
        return content != null && previous != null && content.membership == previous.membership &&
                content.membership != Membership.LEAVE &&
                (content.displayName != previous.displayName || content.avatarUrl != previous.avatarUrl ||
                        content.effectiveColorPreference() != previous.effectiveColorPreference())
    }
}
