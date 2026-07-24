/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.home.room.detail.timeline.item

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
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
import androidx.core.view.doOnPreDraw
import androidx.core.view.updateLayoutParams
import com.airbnb.epoxy.EpoxyAttribute
import im.vector.app.R
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.platform.CheckableView
import im.vector.app.features.themes.ThemeUtils
import im.vector.app.core.extensions.backgroundCompat
import im.vector.app.core.extensions.marginStartCompat

/**
 * Children must override getViewType().
 */
abstract class BaseEventItem<H : BaseEventItem.BaseHolder>(@LayoutRes layoutId: Int) : VectorEpoxyModel<H>(layoutId), ItemWithEvents {

    // To use for instance when opening a permalink with an eventId
    @EpoxyAttribute
    var highlighted: Boolean = false

    // Bumped by the view model on every jump, so re-jumping to the same event rebinds and
    // replays the flash (a bare boolean can't change twice). See TimelineEventController.
    @EpoxyAttribute
    var highlightNonce: Long = 0

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
            this.marginStartCompat = leftGuideline
        }
        val eventId = getEventIds().firstOrNull()
        val flashKey = if (highlighted) "$eventId:$highlightNonce" else null
        if (highlighted) {
            if (flashKey == holder.lastFlashKey) {
                // The same flash session rebinding (live rooms rebind often): leave it be, whether
                // still fading or already finished — cancelling would cut it to a single frame.
                return
            }
            holder.lastFlashKey = flashKey
            // A new flash supersedes a fading one from full strength; drop the old animation's
            // listeners first so its cleanup can't clear the new flash's background.
            holder.highlightAnimator?.removeAllListeners()
            holder.highlightAnimator?.cancel()
            holder.highlightAnimator = null
            holder.checkableBackground.alpha = 1f
            val drawable = buildHighlightDrawable(holder.checkableBackground.context)
            holder.checkableBackground.backgroundCompat = drawable
            // Start the fade only once the row is actually drawn: after a jump the model is often
            // bound during the landing churn (or off-screen prefetch), so a fade started at bind
            // time can finish before the user ever sees the row.
            holder.checkableBackground.doOnPreDraw {
                if (holder.lastFlashKey != flashKey || holder.checkableBackground.background !== drawable) return@doOnPreDraw
                if (holder.highlightAnimator != null) return@doOnPreDraw
                // Fade the dedicated background view, not the drawable: LayerDrawable alpha applies to
                // every layer, so mid-fade the full-width accent layer under the cover layer would
                // bleed through and paint the whole row accent.
                holder.highlightAnimator = ObjectAnimator.ofFloat(holder.checkableBackground, View.ALPHA, 1f, 0f).apply {
                    duration = HIGHLIGHT_FADE_MS
                    startDelay = HIGHLIGHT_HOLD_MS
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (holder.checkableBackground.background === drawable) {
                                holder.checkableBackground.backgroundCompat = null
                                holder.checkableBackground.alpha = 1f
                            }
                        }
                    })
                    start()
                }
            }
        } else {
            holder.lastFlashKey = null
            if (holder.highlightAnimator?.isRunning == true) {
                // Highlight state moved on (cleared, or another event's turn); let the fade play
                // out — its end listener clears the background.
            } else {
                holder.highlightAnimator = null
                holder.checkableBackground.backgroundCompat = null
                holder.checkableBackground.alpha = 1f
            }
        }
    }

    @CallSuper
    override fun unbind(holder: H) {
        holder.highlightAnimator?.removeAllListeners()
        holder.highlightAnimator?.cancel()
        holder.highlightAnimator = null
        // The recycled view must come back clean; lastFlashKey stays, so re-binding the same
        // still-highlighted event doesn't replay a flash that already played.
        holder.checkableBackground.backgroundCompat = null
        holder.checkableBackground.alpha = 1f
        super.unbind(holder)
    }

    // Built in code rather than as a drawable resource: the highlighted_message_background
    // selector's theme attributes (?colorPrimary, ?vctr_header_background) don't resolve inside a
    // drawable on pre-21, ThemeUtils does on every API.
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

    companion object {
        private const val HIGHLIGHT_FADE_MS = 800L

        // Keep the highlight at full strength briefly before fading, so the flash reads as a
        // deliberate "here it is" even when the landing still shuffles the surrounding rows.
        private const val HIGHLIGHT_HOLD_MS = 700L
    }

    abstract class BaseHolder(@IdRes val stubId: Int) : VectorEpoxyHolder() {
        var highlightAnimator: Animator? = null
        var lastFlashKey: String? = null
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
