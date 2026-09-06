/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.acl

import android.widget.Switch
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel

@EpoxyModelClass
abstract class RoomAclIpLiteralsItem : VectorEpoxyModel<RoomAclIpLiteralsItem.Holder>(R.layout.item_room_acl_ip_literals) {
    @EpoxyAttribute var checked: Boolean = true
    @EpoxyAttribute var editable: Boolean = false
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var onChanged: ((Boolean) -> Unit)? = null
    override fun bind(holder: Holder) {
        holder.toggle.setOnCheckedChangeListener(null)
        holder.toggle.isChecked = checked
        holder.toggle.isEnabled = editable
        holder.toggle.setOnCheckedChangeListener { _, value -> onChanged?.invoke(value) }
    }

    class Holder : VectorEpoxyHolder() {
        val toggle by bind<Switch>(R.id.roomAclIpLiterals)
    }
}
