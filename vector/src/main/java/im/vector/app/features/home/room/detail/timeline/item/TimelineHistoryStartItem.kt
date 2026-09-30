/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.extensions.backgroundCompat
import im.vector.app.features.themes.ThemeUtils

@EpoxyModelClass
abstract class TimelineHistoryStartItem : VectorEpoxyModel<TimelineHistoryStartItem.Holder>(R.layout.item_timeline_history_start) {

    /** 0 when the history visibility gives no specific reason. */
    @EpoxyAttribute @StringRes var reasonRes: Int = 0

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.reasonView.isVisible = reasonRes != 0
        if (reasonRes != 0) holder.reasonView.setText(reasonRes)
        // Built in code: a <shape> filled from a theme attribute can't be inflated below API 21.
        val context = holder.view.context
        holder.bubble.backgroundCompat = GradientDrawable().apply {
            cornerRadius = BUBBLE_CORNER_RADIUS_DP * context.resources.displayMetrics.density
            setColor(ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_system))
        }
    }

    class Holder : VectorEpoxyHolder() {
        val bubble by bind<View>(R.id.itemHistoryStartBubble)
        val reasonView by bind<TextView>(R.id.itemHistoryStartSubtitle)
    }

    companion object {
        private const val BUBBLE_CORNER_RADIUS_DP = 8
    }
}
