/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.pinned

import android.view.View
import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.core.date.DateFormatKind
import im.vector.app.core.date.VectorDateFormatter
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.genericFooterItem
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.format.DisplayableEventFormatter
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.util.toMatrixItem
import javax.inject.Inject

class RoomPinnedMessagesController @Inject constructor(
        private val avatarRenderer: AvatarRenderer,
        private val dateFormatter: VectorDateFormatter,
        private val displayableEventFormatter: DisplayableEventFormatter,
        private val stringProvider: StringProvider,
) : TypedEpoxyController<RoomPinnedMessagesViewState>() {

    interface Callback {
        fun onMessageClicked(eventId: String)
        fun onUnpinClicked(anchor: View, eventId: String)
    }

    var callback: Callback? = null

    override fun buildModels(data: RoomPinnedMessagesViewState?) {
        data ?: return
        val events = data.pinnedEvents() ?: return
        val host = this
        if (events.isEmpty()) {
            genericFooterItem {
                id("empty")
                text(host.stringProvider.getString(CommonStrings.pinned_messages_empty).toEpoxyCharSequence())
            }
            return
        }
        events.forEach { event ->
            val eventId = event.eventId
            pinnedMessageItem {
                id(eventId)
                avatarRenderer(host.avatarRenderer)
                matrixItem(event.senderInfo.toMatrixItem())
                senderName(event.senderInfo.disambiguatedDisplayName)
                body(host.displayableEventFormatter.format(event, isDm = false, appendAuthor = false))
                redacted(event.root.isRedacted())
                formattedDate(host.dateFormatter.format(event.root.originServerTs, DateFormatKind.DEFAULT_DATE_AND_TIME))
                itemClickListener { host.callback?.onMessageClicked(eventId) }
                if (data.canEditPinnedEvents) {
                    overflowClickListener { view -> host.callback?.onUnpinClicked(view, eventId) }
                }
            }
        }
    }
}
