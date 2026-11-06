/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.core.ui.list

import android.graphics.Typeface
import android.view.Gravity
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import com.google.android.material.button.MaterialButton
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.extensions.setBackgroundKeepingPadding
import im.vector.app.features.themes.ThemeUtils

/**
 * A generic button list item.
 */
@EpoxyModelClass
abstract class GenericButtonItem : VectorEpoxyModel<GenericButtonItem.Holder>(R.layout.item_generic_button) {

    @EpoxyAttribute
    var text: String? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var buttonClickAction: ClickListener? = null

    @EpoxyAttribute
    @ColorInt
    var textColor: Int? = null

    @EpoxyAttribute
    @DrawableRes
    var iconRes: Int? = null

    @EpoxyAttribute
    var gravity: Int = Gravity.CENTER

    @EpoxyAttribute
    var bold: Boolean = false

    @EpoxyAttribute
    var highlight: Boolean = true

    @EpoxyAttribute
    var fullRowClick: Boolean = false

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.button.text = text
        val textColor = textColor ?: ThemeUtils.getColor(holder.view.context, im.vector.lib.ui.styles.R.attr.vctr_content_primary)
        holder.button.setTextColor(textColor)
        if (iconRes != null) {
            holder.button.setIconResource(iconRes!!)
        } else {
            holder.button.icon = null
        }

        holder.button.gravity = gravity or Gravity.CENTER_VERTICAL
        val textStyle = if (bold) Typeface.BOLD else Typeface.NORMAL
        holder.button.setTypeface(null, textStyle)

        if (fullRowClick) {
            holder.button.rippleColor = null
            holder.button.onClick(null)
            holder.button.isClickable = false
            holder.button.isFocusable = false
            holder.view.setBackgroundKeepingPadding(
                    if (highlight) {
                        ThemeUtils.getAttribute(holder.view.context, androidx.appcompat.R.attr.selectableItemBackground)
                                ?.resourceId
                                ?.takeIf { it != 0 }
                                ?.let { AppCompatResources.getDrawable(holder.view.context, it) }
                    } else {
                        null
                    }
            )
            holder.view.onClick(buttonClickAction)
            holder.view.isFocusable = true
        } else {
            holder.button.rippleColor = if (highlight) {
                ContextCompat.getColorStateList(holder.view.context, com.google.android.material.R.color.mtrl_btn_text_btn_ripple_color)
            } else {
                null
            }
            holder.button.isFocusable = true
            holder.button.onClick(buttonClickAction)
            holder.view.setBackgroundKeepingPadding(null)
            holder.view.onClick(null)
            holder.view.isClickable = false
            holder.view.isFocusable = false
        }
    }

    class Holder : VectorEpoxyHolder() {
        val button by bind<MaterialButton>(R.id.itemGenericItemButton)
    }
}
