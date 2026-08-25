/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.list.actions

import android.widget.ImageView
import android.widget.TextView
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick

/**
 * A custom section row in the move-to-section sheet: tap to move the room, with inline
 * rename/delete management actions.
 */
@EpoxyModelClass
abstract class BottomSheetSectionItem : VectorEpoxyModel<BottomSheetSectionItem.Holder>(R.layout.item_bottom_sheet_section) {

    @EpoxyAttribute
    var name: String? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var clickListener: ClickListener? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var renameListener: ClickListener? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var deleteListener: ClickListener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.title.text = name
        holder.view.onClick(clickListener)
        holder.rename.onClick(renameListener)
        holder.delete.onClick(deleteListener)
    }

    class Holder : VectorEpoxyHolder() {
        val title by bind<TextView>(R.id.sectionTitle)
        val rename by bind<ImageView>(R.id.sectionRename)
        val delete by bind<ImageView>(R.id.sectionDelete)
    }
}
