/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.edit

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
import im.vector.app.core.glide.GlideApp

@EpoxyModelClass
abstract class ImagePackListItem : VectorEpoxyModel<ImagePackListItem.Holder>(R.layout.item_image_pack_list) {

    @EpoxyAttribute lateinit var title: String
    @EpoxyAttribute var subtitle: String? = null
    @EpoxyAttribute var resolvedAvatarUrl: String? = null
    @EpoxyAttribute var placeholderIconRes: Int = R.drawable.ic_attachment_sticker
    @EpoxyAttribute var showGlobalSwitch: Boolean = false
    @EpoxyAttribute var globalEnabled: Boolean = false
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var onClickListener: ClickListener? = null
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var onGlobalToggled: ((Boolean) -> Unit)? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.title.text = title
        holder.subtitle.text = subtitle
        holder.subtitle.isVisible = !subtitle.isNullOrEmpty()

        if (resolvedAvatarUrl != null) {
            // Real pack avatar: don't tint it (the layout tint is only for the monochrome placeholder).
            ImageViewCompat.setImageTintList(holder.avatar, null)
            GlideApp.with(holder.avatar).load(resolvedAvatarUrl).into(holder.avatar)
        } else {
            GlideApp.with(holder.avatar.context.applicationContext).clear(holder.avatar)
            ImageViewCompat.setImageTintList(
                    holder.avatar,
                    android.content.res.ColorStateList.valueOf(
                            im.vector.app.features.themes.ThemeUtils.getColor(holder.avatar.context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
                    )
            )
            holder.avatar.setImageResource(placeholderIconRes)
        }

        holder.globalSwitch.isVisible = showGlobalSwitch
        holder.globalSwitch.setOnCheckedChangeListener(null)
        holder.globalSwitch.isChecked = globalEnabled
        holder.globalSwitch.setOnCheckedChangeListener { _, checked -> onGlobalToggled?.invoke(checked) }

        holder.view.onClick(onClickListener)
    }

    override fun unbind(holder: Holder) {
        GlideApp.with(holder.avatar.context.applicationContext).clear(holder.avatar)
        holder.globalSwitch.setOnCheckedChangeListener(null)
        super.unbind(holder)
    }

    class Holder : VectorEpoxyHolder() {
        val avatar by bind<ImageView>(R.id.imagePackListAvatar)
        val title by bind<TextView>(R.id.imagePackListTitle)
        val subtitle by bind<TextView>(R.id.imagePackListSubtitle)
        val globalSwitch by bind<SwitchCompat>(R.id.imagePackListGlobalSwitch)
    }
}
