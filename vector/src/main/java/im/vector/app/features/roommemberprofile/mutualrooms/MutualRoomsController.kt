/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roommemberprofile.mutualrooms

import com.airbnb.epoxy.TypedEpoxyController
import com.airbnb.mvrx.Success
import im.vector.app.core.epoxy.loadingItem
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.genericFooterItem
import im.vector.app.features.home.AvatarRenderer
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

class MutualRoomsController @Inject constructor(
        private val avatarRenderer: AvatarRenderer,
        private val stringProvider: StringProvider,
) : TypedEpoxyController<MutualRoomsViewState>() {

    var callback: Callback? = null

    interface Callback {
        fun onRoomClicked(roomId: String)
        fun onSpaceClicked(spaceId: String)
    }

    override fun buildModels(data: MutualRoomsViewState?) {
        val host = this
        val items = (data?.items as? Success)?.invoke()
        if (items == null) {
            loadingItem { id("loading") }
            return
        }
        if (items.isEmpty()) {
            genericFooterItem {
                id("empty")
                text(host.stringProvider.getString(CommonStrings.room_member_profile_no_mutual_rooms).toEpoxyCharSequence())
                centered(true)
            }
            return
        }
        items.forEach { item ->
            when (item) {
                is MutualRoomsListItem.SpaceHeader -> mutualRoomItem {
                    id("space_${item.item.id}")
                    avatarRenderer(host.avatarRenderer)
                    matrixItem(item.item)
                    header(true)
                    onClickListener { host.callback?.onSpaceClicked(item.item.id) }
                }
                is MutualRoomsListItem.Room -> mutualRoomItem {
                    id("room_${item.item.id}")
                    avatarRenderer(host.avatarRenderer)
                    matrixItem(item.item)
                    indented(item.indented)
                    onClickListener { host.callback?.onRoomClicked(item.item.id) }
                }
            }
        }
    }
}
