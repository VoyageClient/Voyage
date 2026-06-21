/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.home.room.detail.timeline.item

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewStub
import android.widget.FrameLayout
import android.widget.RelativeLayout
import androidx.annotation.CallSuper
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.core.view.updateLayoutParams
import com.airbnb.epoxy.EpoxyAttribute
import im.vector.app.R
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.platform.CheckableView
import im.vector.app.features.themes.ThemeUtils

/**
 * Children must override getViewType().
 */
abstract class BaseEventItem<H : BaseEventItem.BaseHolder>(@LayoutRes layoutId: Int) : VectorEpoxyModel<H>(layoutId), ItemWithEvents {

    // To use for instance when opening a permalink with an eventId
    @EpoxyAttribute
    var highlighted: Boolean = false

    @EpoxyAttribute
    open var leftGuideline: Int = 0

    final override fun getViewType(): Int {
        // This makes sure we have a unique integer for the combination of layout and ViewStubId.
        val pairingResult = pairingFunction(layout.toLong(), getViewStubId().toLong())
        return (pairingResult - Int.MAX_VALUE).toInt()
    }

    abstract fun getViewStubId(): Int

    // Szudzik function
    private fun pairingFunction(a: Long, b: Long): Long {
        return if (a >= b) a * a + a + b else a + b * b
    }

    @CallSuper
    override fun bind(holder: H) {
        super.bind(holder)
        holder.leftGuideline.updateLayoutParams<RelativeLayout.LayoutParams> {
            this.marginStart = leftGuideline
        }
        // The highlighted_message_background selector references theme attributes (?colorPrimary,
        // ?vctr_header_background) which don't resolve inside a drawable on pre-21, so build the
        // highlight in code where ThemeUtils resolves them on every API.
        holder.checkableBackground.background = if (highlighted) {
            buildHighlightDrawable(holder.checkableBackground.context)
        } else {
            null
        }
    }

    private fun buildHighlightDrawable(context: Context): LayerDrawable {
        val density = context.resources.displayMetrics.density
        fun dp(value: Float) = (value * density).toInt()

        val accent = ThemeUtils.getColor(context, com.google.android.material.R.attr.colorPrimary)
        val background = ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_header_background)

        // Only a 4dp strip of this layer is visible (the rest is covered by the brighter layer), so
        // round the left corners by half that width. A larger radius would curve inward over the
        // strip's full height and read as an oval mask rather than a slim ribbon.
        val ribbonRadius = dp(2f).toFloat()
        val ribbon = GradientDrawable().apply {
            setColor(accent)
            cornerRadii = floatArrayOf(ribbonRadius, ribbonRadius, 0f, 0f, 0f, 0f, ribbonRadius, ribbonRadius)
        }
        val brighter = GradientDrawable().apply {
            setColor(background)
            cornerRadii = floatArrayOf(0f, 0f, dp(4f).toFloat(), dp(4f).toFloat(), dp(4f).toFloat(), dp(4f).toFloat(), 0f, 0f)
        }
        return LayerDrawable(arrayOf(ribbon, brighter)).apply {
            // Ribbon underneath inset to clear the brighter layer's rounded right corners; the brighter
            // layer on top leaves a 4dp accent strip on the left.
            setLayerInset(0, dp(2f), 0, dp(6f), 0)
            setLayerInset(1, dp(6f), 0, dp(2f), 0)
        }
    }

    abstract class BaseHolder(@IdRes val stubId: Int) : VectorEpoxyHolder() {
        val leftGuideline by bind<View>(R.id.messageStartGuideline)
        val contentContainer by bind<View>(R.id.viewStubContainer)
        val viewStubContainer by bind<FrameLayout>(R.id.viewStubContainer)
        val checkableBackground by bind<CheckableView>(R.id.messageSelectedBackground)

        override fun bindView(itemView: View) {
            super.bindView(itemView)
            inflateStub()
        }

        private fun inflateStub() {
            view.findViewById<ViewStub>(stubId).inflate()
        }
    }
}
