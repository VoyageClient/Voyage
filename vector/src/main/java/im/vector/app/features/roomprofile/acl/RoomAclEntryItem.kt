/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.acl

import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ImageButton
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.ImageViewCompat
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings

@EpoxyModelClass
abstract class RoomAclEntryItem : VectorEpoxyModel<RoomAclEntryItem.Holder>(R.layout.item_room_acl_entry) {
    @EpoxyAttribute lateinit var entry: RoomAclEntry
    @EpoxyAttribute var editable: Boolean = false
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var onChanged: ((String, Boolean) -> Unit)? = null
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var onTypeChanged: (() -> Unit)? = null
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var onDelete: (() -> Unit)? = null
    override fun bind(holder: Holder) {
        holder.server.removeTextChangedListener(holder.watcher)
        if (holder.server.text.toString() != entry.server) holder.server.setText(entry.server)
        val typeLabel = if (entry.allowed) CommonStrings.room_acl_allow else CommonStrings.room_acl_deny
        holder.type.contentDescription = holder.type.context.getString(typeLabel)
        holder.type.setImageDrawable(AppCompatResources.getDrawable(holder.type.context, if (entry.allowed) R.drawable.ic_check_white_24dp else R.drawable.ic_close_24dp))
        ImageViewCompat.setImageTintList(holder.type, android.content.res.ColorStateList.valueOf(ThemeUtils.getColor(holder.type.context, if (entry.allowed) com.google.android.material.R.attr.colorSecondary else com.google.android.material.R.attr.colorError)))
        holder.server.isEnabled = editable
        holder.type.isEnabled = editable
        holder.type.isClickable = editable
        holder.delete.isEnabled = editable
        holder.watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                onChanged?.invoke(holder.server.text.toString(), entry.allowed)
            }
        }
        holder.server.addTextChangedListener(holder.watcher)
        holder.type.setOnClickListener { onTypeChanged?.invoke() }
        holder.delete.setOnClickListener { onDelete?.invoke() }
    }
    override fun unbind(holder: Holder) {
        holder.server.removeTextChangedListener(holder.watcher)
        holder.watcher = null
    }

    class Holder : VectorEpoxyHolder() {
        val server by bind<EditText>(R.id.roomAclServer)
        val type by bind<ImageButton>(R.id.roomAclType)
        val delete by bind<ImageButton>(R.id.roomAclDelete)
        var watcher: TextWatcher? = null
    }
}
