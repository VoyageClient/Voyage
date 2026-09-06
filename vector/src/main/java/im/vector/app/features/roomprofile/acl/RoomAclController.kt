/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.acl

import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.genericFooterItem
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

class RoomAclController @Inject constructor(private val stringProvider: StringProvider) : TypedEpoxyController<RoomAclViewState>() {
    interface Callback {
        fun onAdd(allowed: Boolean)
        fun onUpdate(entry: RoomAclEntry, server: String, allowed: Boolean)
        fun onTypeChanged(entry: RoomAclEntry)
        fun onRemove(entry: RoomAclEntry)
        fun onIpLiteralsChanged(value: Boolean)
        fun onExpandedToggle(allowed: Boolean)
    }
    var callback: Callback? = null
    private var draggedEntries: List<RoomAclEntry>? = null

    fun setDraggedEntries(entries: List<RoomAclEntry>) {
        draggedEntries = entries
    }

    fun currentOrderedEntries(): List<RoomAclEntry> =
            adapter.copyOfModels.filterIsInstance<RoomAclEntryItem_>().map { it.entry().copy() }

    fun dropTargetAllowed(fromPosition: Int, toPosition: Int, movedEntryId: Long): Boolean? {
        val models = adapter.copyOfModels
        val positions = if (fromPosition < toPosition) (toPosition + 1 until models.size) + (toPosition - 1 downTo 0)
        else (toPosition - 1 downTo 0) + (toPosition + 1 until models.size)
        return positions.asSequence()
                .mapNotNull { models.getOrNull(it) as? RoomAclEntryItem_ }
                .firstOrNull { it.entry().id != movedEntryId }
                ?.entry()
                ?.allowed
    }
    override fun buildModels(data: RoomAclViewState?) {
        data ?: return
        if (data.loading) return
        val draggedEntries = draggedEntries
        val sourceEntries = draggedEntries?.let { dragged ->
            val entriesById = data.entries.associateBy { it.id }
            dragged.map { entry ->
                (entriesById[entry.id] ?: entry).copy(allowed = entry.allowed)
            }
        } ?: data.entries
        if (draggedEntries?.map { it.id to it.allowed } == data.entries.map { it.id to it.allowed }) {
            this.draggedEntries = null
        }
        roomAclIpLiteralsItem {
            id("ip_literals")
            checked(data.allowIpLiterals)
            editable(data.canEdit)
            onChanged { this@RoomAclController.callback?.onIpLiteralsChanged(it) }
        }
        val entries = sourceEntries.filter { it.server.contains(data.query, true) || data.query.isBlank() }
        fun buildEntries(allowed: Boolean, title: Int, addTitle: Int, expanded: Boolean, id: String) {
            roomAclSectionItem {
                id("section_$id")
                title(this@RoomAclController.stringProvider.getString(title))
                expanded(expanded)
                addVisible(data.canEdit)
                addDescription(this@RoomAclController.stringProvider.getString(addTitle))
                expandDescription(this@RoomAclController.stringProvider.getString(if (expanded) CommonStrings.room_acl_collapse_servers else CommonStrings.room_acl_expand_servers))
                onAdd { this@RoomAclController.callback?.onAdd(allowed) }
                onExpandToggle { this@RoomAclController.callback?.onExpandedToggle(allowed) }
            }
            if (!expanded) return
            entries.filter { it.allowed == allowed }.forEach { entry ->
                roomAclEntryItem {
                    id(entry.id)
                    entry(entry)
                    editable(data.canEdit)
                    onChanged { server, allowed -> this@RoomAclController.callback?.onUpdate(entry, server, allowed) }
                    onTypeChanged { this@RoomAclController.callback?.onTypeChanged(entry) }
                    onDelete { this@RoomAclController.callback?.onRemove(entry) }
                }
            }
        }
        buildEntries(true, CommonStrings.room_acl_allowed_servers, CommonStrings.room_acl_add_allow, data.allowedExpanded, "allow")
        buildEntries(false, CommonStrings.room_acl_denied_servers, CommonStrings.room_acl_add_deny, data.deniedExpanded, "deny")
        if (entries.isEmpty() && data.query.isNotBlank()) {
            genericFooterItem {
                id("empty")
                text(this@RoomAclController.stringProvider.getString(CommonStrings.no_result_placeholder).toEpoxyCharSequence())
                centered(true)
            }
        }
    }
}
