/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.gradients

import android.graphics.Color
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.ui.colorpicker.ColorSwatches
import im.vector.app.core.ui.colorpicker.ProfileColorPickerDialogFragment
import im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings

@EpoxyModelClass
abstract class GradientColorItem : VectorEpoxyModel<GradientColorItem.Holder>(R.layout.item_gradient_color) {

    @EpoxyAttribute lateinit var color: EditableColor
    @EpoxyAttribute var position: Int = 0
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var onClickListener: ClickListener? = null
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var onDeleteClick: ClickListener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        val context = holder.view.context
        val hex = MatrixItemColorProvider.toHex(color.color)
        holder.swatch.setImageDrawable(ColorSwatches.round(color.color or Color.BLACK))
        holder.title.text = context.getString(CommonStrings.gradient_color, position)
        holder.subtitle.text = ProfileColorPickerDialogFragment.describe(
                context, hex, ThemeUtils.isLightTheme(context), ProfileColorPickerDialogFragment.Origin.OURS
        )
        holder.row.onClick(onClickListener)
        holder.delete.onClick(onDeleteClick)
    }

    class Holder : VectorEpoxyHolder() {
        val row by bind<View>(R.id.gradientColorRow)
        val swatch by bind<ImageView>(R.id.gradientColorSwatch)
        val title by bind<TextView>(R.id.gradientColorTitle)
        val subtitle by bind<TextView>(R.id.gradientColorSubtitle)
        val delete by bind<ImageButton>(R.id.gradientColorDelete)
    }
}
