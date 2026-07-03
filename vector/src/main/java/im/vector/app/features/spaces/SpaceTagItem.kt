/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.spaces

import android.widget.TextView
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.features.home.room.detail.timeline.tools.withEmojis
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.platform.CheckableConstraintLayout

@EpoxyModelClass
abstract class SpaceTagItem : VectorEpoxyModel<SpaceTagItem.Holder>(R.layout.item_space_tag) {

    @EpoxyAttribute var name: String = ""
    @EpoxyAttribute var count: Int = 0
    @EpoxyAttribute var selected: Boolean = false
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var listener: ClickListener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.rootView.isChecked = selected
        holder.rootView.onClick(listener)
        holder.nameView.text = name.withEmojis()
        holder.countView.text = count.toString()
    }

    class Holder : VectorEpoxyHolder() {
        val rootView by bind<CheckableConstraintLayout>(R.id.tagItemLayout)
        val nameView by bind<TextView>(R.id.tagNameView)
        val countView by bind<TextView>(R.id.tagCountView)
    }
}
