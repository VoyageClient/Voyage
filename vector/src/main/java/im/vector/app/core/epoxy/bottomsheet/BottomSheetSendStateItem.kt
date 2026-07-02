/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.core.epoxy.bottomsheet

import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.features.themes.ThemeUtils

/**
 * A send state for bottom sheet.
 */
@EpoxyModelClass
abstract class BottomSheetSendStateItem : VectorEpoxyModel<BottomSheetSendStateItem.Holder>(R.layout.item_bottom_sheet_message_status) {

    @EpoxyAttribute
    var showProgress: Boolean = false

    @EpoxyAttribute
    lateinit var text: String

    @EpoxyAttribute
    @DrawableRes
    var drawableStart: Int = 0

    @EpoxyAttribute
    var accentTint: Boolean = false

    override fun bind(holder: Holder) {
        super.bind(holder)
        val context = holder.view.context
        val color = ThemeUtils.getColor(
                context,
                if (accentTint) com.google.android.material.R.attr.colorPrimary else im.vector.lib.ui.styles.R.attr.vctr_content_secondary
        )
        holder.progress.isVisible = showProgress
        holder.progress.indeterminateDrawable?.let { DrawableCompat.setTint(DrawableCompat.wrap(it.mutate()), color) }
        val drawable = drawableStart.takeIf { it != 0 }
                ?.let { ContextCompat.getDrawable(context, it) }
                ?.let { if (accentTint) DrawableCompat.wrap(it.mutate()).apply { DrawableCompat.setTint(this, color) } else it }
        holder.text.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null)
        holder.text.setTextColor(color)
        holder.text.text = text
    }

    class Holder : VectorEpoxyHolder() {
        val progress by bind<ProgressBar>(R.id.messageStatusProgress)
        val text by bind<TextView>(R.id.messageStatusText)
    }
}
