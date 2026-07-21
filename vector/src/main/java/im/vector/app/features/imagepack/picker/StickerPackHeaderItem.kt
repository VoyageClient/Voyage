/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.picker

import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay

@EpoxyModelClass
abstract class StickerPackHeaderItem : VectorEpoxyModel<StickerPackHeaderItem.Holder>(R.layout.item_sticker_pack_header) {

    @EpoxyAttribute var title: String? = null
    @EpoxyAttribute @DrawableRes var iconRes: Int? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.title.text = title?.prepareForDisplay()
        val icon = iconRes
        holder.icon.isVisible = icon != null
        if (icon != null) holder.icon.setImageResource(icon)
    }

    class Holder : VectorEpoxyHolder() {
        val icon by bind<ImageView>(R.id.stickerPackHeaderIcon)
        val title by bind<TextView>(R.id.stickerPackHeaderTitle)
    }
}
