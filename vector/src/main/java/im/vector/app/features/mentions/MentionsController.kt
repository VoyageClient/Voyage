/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.mentions

import com.airbnb.epoxy.EpoxyAsyncUtil
import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.R
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.genericFooterItem
import im.vector.app.core.ui.list.genericLoaderItem
import im.vector.app.features.home.AvatarRenderer
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.util.toDisplayMatrixItem
import org.matrix.android.sdk.api.util.toMatrixItem
import javax.inject.Inject

class MentionsController @Inject constructor(
        private val avatarRenderer: AvatarRenderer,
        private val stringProvider: StringProvider,
) : TypedEpoxyController<MentionsViewState>(
        // A few hundred mentions is a few hundred models; building and diffing that many on the main
        // thread costs more than a frame.
        EpoxyAsyncUtil.getAsyncBackgroundHandler(),
        EpoxyAsyncUtil.getAsyncBackgroundHandler(),
) {

    interface Callback {
        fun onMentionClicked(roomId: String, eventId: String)
        fun onLoadMore()
    }

    var callback: Callback? = null

    override fun buildModels(data: MentionsViewState?) {
        data ?: return
        val host = this
        val items = data.mentions() ?: return
        if (items.isEmpty()) {
            val emptyText = if (data.searchQuery.isBlank()) CommonStrings.mentions_empty else CommonStrings.mentions_no_results
            genericFooterItem {
                id("empty")
                text(host.stringProvider.getString(emptyText).toEpoxyCharSequence())
            }
            return
        }
        items.forEach { item ->
            val event = item.event
            mentionItem {
                id(event.eventId)
                avatarRenderer(host.avatarRenderer)
                senderItem(event.senderInfo.toMatrixItem())
                roomItem(item.roomSummary.toDisplayMatrixItem())
                senderName(item.senderName)
                roomName(item.roomName)
                body(item.body.toEpoxyCharSequence())
                formattedDate(item.formattedDate)
                itemClickListener { host.callback?.onMentionClicked(item.roomSummary.roomId, event.eventId) }
            }
        }
        if (data.hasMore) {
            // Binding the footer is what asks for the next page: it only comes into view at the end.
            genericLoaderItem {
                id("load_more")
                // The shared loader item is a small spinner; match the one the first load shows.
                layout(R.layout.item_mentions_load_more)
                onBind { _, _, _ -> host.callback?.onLoadMore() }
            }
        }
    }
}
