/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.gradients

import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick

@EpoxyModelClass
abstract class GradientListItem : VectorEpoxyModel<GradientListItem.Holder>(R.layout.item_image_pack_list) {

    @EpoxyAttribute lateinit var gradient: CustomGradient
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var onClickListener: ClickListener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        ImageViewCompat.setImageTintList(holder.avatar, null)
        holder.avatar.setImageDrawable(CustomGradient.drawable(gradient.colors, GradientDrawable.OVAL))
        holder.title.text = gradient.label
        holder.subtitle.isVisible = false
        holder.globalSwitch.isVisible = false
        holder.view.onClick(onClickListener)
    }

    class Holder : VectorEpoxyHolder() {
        val avatar by bind<ImageView>(R.id.imagePackListAvatar)
        val title by bind<TextView>(R.id.imagePackListTitle)
        val subtitle by bind<TextView>(R.id.imagePackListSubtitle)
        val globalSwitch by bind<SwitchCompat>(R.id.imagePackListGlobalSwitch)
    }
}
