/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.factory

import android.os.Build
import im.vector.app.core.epoxy.TimelineEmptyItem
import im.vector.app.core.epoxy.TimelineEmptyItem_
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.resources.UserPreferencesProvider
import im.vector.app.features.home.room.detail.timeline.STATE_ROOM_VOICE_BROADCAST_INFO
import im.vector.app.features.home.room.detail.timeline.helper.TimelineEventVisibilityHelper
import im.vector.app.features.redaction.preservation.RedactedContentRestorer
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import timber.log.Timber
import javax.inject.Inject

class TimelineItemFactory @Inject constructor(
        private val messageItemFactory: MessageItemFactory,
        private val encryptedItemFactory: EncryptedItemFactory,
        private val noticeItemFactory: NoticeItemFactory,
        private val defaultItemFactory: DefaultItemFactory,
        private val encryptionItemFactory: EncryptionItemFactory,
        private val roomCreateItemFactory: RoomCreateItemFactory,
        private val widgetItemFactory: WidgetItemFactory,
        private val verificationConclusionItemFactory: VerificationItemFactory,
        private val timelineEventVisibilityHelper: TimelineEventVisibilityHelper,
        private val userPreferencesProvider: UserPreferencesProvider,
        private val redactedContentRestorer: RedactedContentRestorer,
) {

    /**
     * Reminder: nextEvent is older and prevEvent is newer.
     */
    fun create(rawParams: TimelineItemFactoryParams): VectorEpoxyModel<*> {
        val params = redactedContentRestorer.restore(rawParams)
        val event = params.event
        val computedModel = try {
            if (!timelineEventVisibilityHelper.shouldShowEvent(
                            timelineEvent = event,
                            highlightedEventId = params.highlightedEventId,
                            isFromThreadTimeline = params.isFromThreadTimeline(),
                            rootThreadEventId = params.rootThreadEventId,
                            forcedVisibleEventIds = params.forcedVisibleEventIds
                    )) {
                return buildEmptyItem(
                        event,
                        params.prevEvent,
                        params.highlightedEventId,
                        params.rootThreadEventId,
                        params.isFromThreadTimeline()
                )
            }

            // The per-type factories below assume content that redaction has already stripped.
            if (event.root.isRedacted()) {
                if (event.root.isStateEvent()) {
                    noticeItemFactory.create(params)
                } else {
                    messageItemFactory.create(params)
                }
            } else if (event.root.isStateEvent()) {
                // state event are not e2e
                when (event.root.type) {
                    EventType.STATE_ROOM_TOMBSTONE,
                    EventType.STATE_ROOM_NAME,
                    EventType.STATE_ROOM_TOPIC,
                    EventType.STATE_ROOM_AVATAR,
                    EventType.STATE_ROOM_MEMBER,
                    EventType.STATE_ROOM_THIRD_PARTY_INVITE,
                    EventType.STATE_ROOM_CANONICAL_ALIAS,
                    EventType.STATE_ROOM_JOIN_RULES,
                    EventType.STATE_ROOM_HISTORY_VISIBILITY,
                    EventType.STATE_ROOM_SERVER_ACL,
                    EventType.STATE_ROOM_GUEST_ACCESS,
                    EventType.STATE_ROOM_ALIASES,
                    EventType.STATE_SPACE_CHILD,
                    EventType.STATE_SPACE_PARENT,
                    EventType.STATE_ROOM_PINNED_EVENT,
                    EventType.STATE_ROOM_IMAGE_PACK,
                    EventType.STATE_ROOM_IMAGE_PACK_UNSTABLE,
                    EventType.STATE_ROOM_POWER_LEVELS -> {
                        noticeItemFactory.create(params)
                    }
                    in EventType.STATE_ROOM_BANNER.values -> noticeItemFactory.create(params)
                    EventType.STATE_ROOM_WIDGET_LEGACY,
                    EventType.STATE_ROOM_WIDGET -> widgetItemFactory.create(params)
                    EventType.STATE_ROOM_ENCRYPTION -> encryptionItemFactory.create(params)
                    // State room create
                    EventType.STATE_ROOM_CREATE -> roomCreateItemFactory.create(params)
                    in EventType.STATE_ROOM_BEACON_INFO.values -> locationItemFactory(params)
                    // Voice broadcast playback is removed; surface the state event as a plain notice.
                    STATE_ROOM_VOICE_BROADCAST_INFO -> noticeItemFactory.create(params)
                    // Unhandled state event types
                    else -> {
                        // Should only happen when shouldShowHiddenEvents() settings is ON
                        Timber.v("State event type ${event.root.type} not handled")
                        defaultItemFactory.create(params)
                    }
                }
            } else {
                when (event.root.getClearType()) {
                    // Message itemsX
                    EventType.STICKER,
                    in EventType.POLL_START.values,
                    in EventType.POLL_END.values -> messageItemFactory.create(params)
                    EventType.MESSAGE -> {
                        // KitKat has no maps (maplibre is API 21+); show location as a text notice.
                        if (isLocationMessage(event)) locationItemFactory(params) else messageItemFactory.create(params)
                    }
                    EventType.REDACTION,
                    EventType.KEY_VERIFICATION_ACCEPT,
                    EventType.KEY_VERIFICATION_START,
                    EventType.KEY_VERIFICATION_KEY,
                    EventType.KEY_VERIFICATION_READY,
                    EventType.KEY_VERIFICATION_MAC,
                    EventType.REACTION,
                    EventType.CALL_INVITE,
                    EventType.CALL_CANDIDATES,
                    EventType.CALL_ANSWER,
                    EventType.CALL_HANGUP,
                    EventType.CALL_REJECT,
                    EventType.CALL_NEGOTIATE,
                    EventType.CALL_SELECT_ANSWER,
                    EventType.CALL_REPLACES,
                    in EventType.POLL_RESPONSE.values -> noticeItemFactory.create(params)
                    in EventType.BEACON_LOCATION_DATA.values -> noticeItemFactory.create(params)
                    // Crypto
                    EventType.ENCRYPTED -> encryptedItemFactory.create(params)
                    EventType.KEY_VERIFICATION_CANCEL,
                    EventType.KEY_VERIFICATION_DONE -> {
                        verificationConclusionItemFactory.create(params)
                    }
                    // Unhandled event types
                    else -> {
                        // Should only happen when shouldShowHiddenEvents() settings is ON
                        Timber.v("Type ${event.root.getClearType()} not handled")
                        defaultItemFactory.create(params)
                    }
                }
            }
        } catch (throwable: Throwable) {
            Timber.e(throwable, "failed to create message item")
            defaultItemFactory.create(params, throwable)
        }
        if (computedModel != null) {
            return computedModel
        }
        // No factory produced a model. When the event is the navigation target (e.g. tapping
        // "In reply to" on a reaction / membership / otherwise unrendered event) it must still
        // appear and carry the selection highlight, so fall back to a default item showing the
        // event type instead of a zero-height empty item that would swallow the highlight. The
        // same fallback surfaces these events as debug items when "show hidden events" is on.
        val showHidden = userPreferencesProvider.shouldShowHiddenEvents() && !params.isFromThreadTimeline()
        return if (params.isHighlighted || showHidden) {
            defaultItemFactory.create(params)
        } else {
            buildEmptyItem(
                    event,
                    params.prevEvent,
                    params.highlightedEventId,
                    params.rootThreadEventId,
                    params.isFromThreadTimeline()
            )
        }
    }

    private fun isLocationMessage(event: TimelineEvent): Boolean {
        return event.root.getClearContent().toModel<MessageContent>()?.msgType == MessageType.MSGTYPE_LOCATION
    }

    // On KitKat there is no map renderer (maplibre needs API 21), so render location/live-location
    // as a plain text notice instead. On API 21+ keep the interactive/static map item.
    private fun locationItemFactory(params: TimelineItemFactoryParams): VectorEpoxyModel<*>? {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            noticeItemFactory.create(params)
        } else {
            messageItemFactory.create(params)
        }
    }

    private fun buildEmptyItem(
            timelineEvent: TimelineEvent,
            prevEvent: TimelineEvent?,
            highlightedEventId: String?,
            rootThreadEventId: String?,
            isFromThreadTimeline: Boolean
    ): TimelineEmptyItem {
        val isNotBlank = prevEvent == null || timelineEventVisibilityHelper.shouldShowEvent(
                timelineEvent = prevEvent,
                highlightedEventId = highlightedEventId,
                isFromThreadTimeline = isFromThreadTimeline,
                rootThreadEventId = rootThreadEventId
        )
        return TimelineEmptyItem_()
                .id(timelineEvent.localId)
                .eventId(timelineEvent.eventId)
                .notBlank(isNotBlank)
    }
}
