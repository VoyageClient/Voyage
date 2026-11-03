/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.core.ui.list

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.extensions.setTextOrHide
import im.vector.app.core.utils.copyToClipboard
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.core.utils.epoxy.charsequence.EpoxyCharSequence
import im.vector.lib.strings.CommonStrings

/**
 * A generic list item.
 * Displays an item with a title, and optional description.
 * Can display an accessory on the right, that can be an image or an indeterminate progress.
 * If provided with an action, will display a button at the bottom of the list item.
 */
@EpoxyModelClass
abstract class GenericWithValueItem : VectorEpoxyModel<GenericWithValueItem.Holder>(R.layout.item_generic_with_value) {

    @EpoxyAttribute
    var title: EpoxyCharSequence? = null

    @EpoxyAttribute
    var value: String? = null

    @EpoxyAttribute
    @ColorInt
    var valueColorInt: Int? = null

    @EpoxyAttribute
    @DrawableRes
    var titleIconResourceId: Int = -1

    @EpoxyAttribute
    @ColorInt
    var titleIconTintColor: Int? = null

    /** Long-press offers a Copy menu with this value. */
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var copyValue: (() -> String?)? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var itemClickAction: ClickListener? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var itemLongClickAction: View.OnLongClickListener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.titleText.setTextOrHide(title?.charSequence)

        if (titleIconResourceId != -1) {
            holder.titleIcon.setImageResource(titleIconResourceId)
            ImageViewCompat.setImageTintList(holder.titleIcon, titleIconTintColor?.let { ColorStateList.valueOf(it) })
            holder.titleIcon.isVisible = true
        } else {
            holder.titleIcon.isVisible = false
        }

        holder.valueText.setTextOrHide(value)

        if (valueColorInt != null) {
            holder.valueText.setTextColor(valueColorInt!!)
        } else {
            holder.valueText.setTextColor(ThemeUtils.getColor(holder.view.context, im.vector.lib.ui.styles.R.attr.vctr_content_primary))
        }

        holder.view.onClick(itemClickAction)
        holder.view.setOnLongClickListener(itemLongClickAction)
        val copy = copyValue
        holder.view.setOnCreateContextMenuListener(
                copy?.let {
                    View.OnCreateContextMenuListener { menu, v, _ ->
                        menu.add(CommonStrings.action_copy).setOnMenuItemClickListener {
                            copy()?.let { value -> copyToClipboard(v.context, value, false) }
                            true
                        }
                    }
                }
        )
        // Null listeners still leave the view (long-)clickable, rippling a dead row.
        holder.view.isClickable = itemClickAction != null
        holder.view.isLongClickable = itemLongClickAction != null || copy != null
    }

    class Holder : VectorEpoxyHolder() {
        val titleIcon by bind<ImageView>(R.id.itemGenericWithValueTitleIcon)
        val titleText by bind<TextView>(R.id.itemGenericWithValueLabelText)
        val valueText by bind<TextView>(R.id.itemGenericWithValueValueText)
    }
}
