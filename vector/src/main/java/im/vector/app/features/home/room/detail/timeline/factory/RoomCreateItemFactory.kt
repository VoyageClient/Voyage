/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.factory

import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.resources.UserPreferencesProvider
import im.vector.app.features.home.room.detail.timeline.item.RoomCreateItem_
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.strings.CommonStrings
import me.gujun.android.span.span
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.model.create.RoomCreateContent
import org.matrix.android.sdk.api.session.room.model.create.RoomPredecessors.toPredecessor
import org.matrix.android.sdk.api.session.room.model.create.findPredecessor
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import javax.inject.Inject

class RoomCreateItemFactory @Inject constructor(
        private val stringProvider: StringProvider,
        private val userPreferencesProvider: UserPreferencesProvider,
        private val session: Session,
        private val noticeItemFactory: NoticeItemFactory
) {

    fun create(params: TimelineItemFactoryParams): VectorEpoxyModel<*>? {
        val event = params.event
        val predecessorId = event.predecessorRoomId() ?: return defaultRendering(params)
        val roomLink = session.permalinkService().createRoomPermalink(predecessorId) ?: return null
        val text = span {
            +stringProvider.getString(CommonStrings.room_tombstone_continuation_description)
            +"\n"
            span(stringProvider.getString(CommonStrings.room_tombstone_predecessor_link)) {
                textDecorationLine = "underline"
                onClick = { params.callback?.onRoomCreateLinkClicked(roomLink) }
            }
        }
        return RoomCreateItem_()
                .text(text.toEpoxyCharSequence())
    }

    /**
     * The predecessor of a MSC3946 event is its own content; for the create event it is either the
     * `predecessor` field or a predecessor event added to the room later.
     */
    private fun TimelineEvent.predecessorRoomId(): String? {
        if (root.getClearType() in EventType.STATE_ROOM_PREDECESSOR.values) {
            return root.toPredecessor()?.roomId
        }
        val fromCreate = root.content.toModel<RoomCreateContent>()?.predecessor?.roomId
        return fromCreate ?: session.getRoom(roomId)?.stateService()?.findPredecessor()?.roomId
    }

    private fun defaultRendering(params: TimelineItemFactoryParams): VectorEpoxyModel<*>? {
        return if (userPreferencesProvider.shouldShowHiddenEvents()) {
            noticeItemFactory.create(params)
        } else {
            null
        }
    }
}
