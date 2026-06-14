/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.list.actions

import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.R
import im.vector.app.core.epoxy.bottomSheetDividerItem
import im.vector.app.core.epoxy.bottomsheet.bottomSheetActionItem
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.genericHeaderItem
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

class RoomTagController @Inject constructor(
        private val stringProvider: StringProvider,
) : TypedEpoxyController<RoomTagViewState>() {

    var listener: Listener? = null

    override fun buildModels(state: RoomTagViewState) {
        val host = this

        if (state.roomTags.isNotEmpty()) {
            genericHeaderItem {
                id("on_this_room")
                text(host.stringProvider.getString(CommonStrings.room_tag_on_this_room))
            }
            state.roomTags.forEach { tag ->
                bottomSheetActionItem {
                    id("current_${tag.name}")
                    iconRes(R.drawable.ic_close_24dp)
                    text(tag.displayName)
                    listener { host.listener?.onRemoveTag(tag.name) }
                }
            }
            bottomSheetDividerItem {
                id("tags_separator")
            }
        }

        genericHeaderItem {
            id("add_tag")
            text(host.stringProvider.getString(CommonStrings.room_tag_add_existing))
        }
        state.availableTags.forEach { tag ->
            bottomSheetActionItem {
                id("available_${tag.name}")
                iconRes(R.drawable.ic_tag_24)
                text(tag.displayName)
                listener { host.listener?.onAddTag(tag.name) }
            }
        }

        bottomSheetActionItem {
            id("create_tag")
            iconRes(R.drawable.ic_plus_circle)
            text(host.stringProvider.getString(CommonStrings.room_tag_create_new))
            listener { host.listener?.onCreateNewTag() }
        }
    }

    interface Listener {
        fun onAddTag(tag: String)
        fun onRemoveTag(tag: String)
        fun onCreateNewTag()
    }
}
