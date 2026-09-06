/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.acl

import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel

@EpoxyModelClass
abstract class RoomAclSectionItem : VectorEpoxyModel<RoomAclSectionItem.Holder>(R.layout.item_room_acl_section) {
    @EpoxyAttribute lateinit var title: String
    @EpoxyAttribute var expanded: Boolean = true
    @EpoxyAttribute var addVisible: Boolean = false
    @EpoxyAttribute lateinit var addDescription: String
    @EpoxyAttribute lateinit var expandDescription: String
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var onAdd: (() -> Unit)? = null
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var onExpandToggle: (() -> Unit)? = null

    override fun bind(holder: Holder) {
        holder.title.text = title
        holder.add.visibility = if (addVisible) View.VISIBLE else View.GONE
        holder.add.contentDescription = addDescription
        holder.expand.contentDescription = expandDescription
        holder.expand.setImageDrawable(AppCompatResources.getDrawable(holder.expand.context, if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more))
        holder.add.setOnClickListener { onAdd?.invoke() }
        holder.expand.setOnClickListener { onExpandToggle?.invoke() }
    }

    class Holder : VectorEpoxyHolder() {
        val title by bind<TextView>(R.id.roomAclSectionTitle)
        val add by bind<ImageButton>(R.id.roomAclSectionAdd)
        val expand by bind<ImageButton>(R.id.roomAclSectionExpand)
    }
}
