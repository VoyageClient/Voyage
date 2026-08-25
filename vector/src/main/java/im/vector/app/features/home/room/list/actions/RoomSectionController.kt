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

class RoomSectionController @Inject constructor(
        private val stringProvider: StringProvider,
) : TypedEpoxyController<RoomSectionViewState>() {

    var listener: Listener? = null

    override fun buildModels(state: RoomSectionViewState) {
        val host = this
        val current = state.sections.firstOrNull { it.tag == state.currentSectionTag }

        if (current != null) {
            genericHeaderItem {
                id("current_section")
                text(host.stringProvider.getString(CommonStrings.room_section_current))
            }
            bottomSheetSectionItem {
                id("current_${current.tag}")
                name(current.name)
                renameListener { host.listener?.onRenameSection(current.tag, current.name) }
                deleteListener { host.listener?.onDeleteSection(current.tag) }
            }
            bottomSheetActionItem {
                id("remove_${current.tag}")
                iconRes(R.drawable.ic_close_24dp)
                text(host.stringProvider.getString(CommonStrings.room_section_remove_from))
                listener { host.listener?.onRemoveFromSection() }
            }
            bottomSheetDividerItem {
                id("current_separator")
            }
        }

        genericHeaderItem {
            id("move_to")
            text(host.stringProvider.getString(CommonStrings.room_section_move_to))
        }
        state.sections.filter { it.tag != state.currentSectionTag }.forEach { section ->
            bottomSheetSectionItem {
                id("section_${section.tag}")
                name(section.name)
                clickListener { host.listener?.onMoveToSection(section.tag) }
                renameListener { host.listener?.onRenameSection(section.tag, section.name) }
                deleteListener { host.listener?.onDeleteSection(section.tag) }
            }
        }

        bottomSheetActionItem {
            id("create_section")
            iconRes(R.drawable.ic_plus_circle)
            text(host.stringProvider.getString(CommonStrings.room_section_create_new))
            listener { host.listener?.onCreateNewSection() }
        }
    }

    interface Listener {
        fun onMoveToSection(tag: String)
        fun onRemoveFromSection()
        fun onCreateNewSection()
        fun onRenameSection(tag: String, currentName: String)
        fun onDeleteSection(tag: String)
    }
}
