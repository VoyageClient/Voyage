/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.search

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.animation.LinearInterpolator
import androidx.core.animation.doOnEnd
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.recyclerview.widget.RecyclerView
import im.vector.app.core.extensions.setBackgroundKeepingPadding
import im.vector.app.features.themes.ThemeUtils

/** Points at a settings row the way the timeline points at a jumped-to message (see BaseEventItem). */
object PreferenceHighlighter {

    private const val HIGHLIGHT_HOLD_MS = 700L
    private const val HIGHLIGHT_FADE_MS = 800L

    /** Scrolls to the first shown preference matching [predicate] and highlights its row. Returns false if there is none. */
    fun highlight(fragment: PreferenceFragmentCompat, predicate: (Preference) -> Boolean): Boolean {
        val preference = find(fragment.preferenceScreen ?: return false, predicate)?.takeIf { it.isShown } ?: return false
        fragment.scrollToPreference(preference)
        val list = fragment.listView ?: return true
        // Once the scroll has laid the row out.
        list.post { show(list, preference) }
        return true
    }

    private fun find(group: PreferenceGroup, predicate: (Preference) -> Boolean): Preference? {
        for (i in 0 until group.preferenceCount) {
            val preference = group.getPreference(i)
            if (predicate(preference)) return preference
            if (preference is PreferenceGroup) find(preference, predicate)?.let { return it }
        }
        return null
    }

    private fun show(list: RecyclerView, preference: Preference) {
        val position = (list.adapter as? PreferenceGroup.PreferencePositionCallback)?.getPreferenceAdapterPosition(preference) ?: return
        if (position == RecyclerView.NO_POSITION) return
        val holder = list.findViewHolderForAdapterPosition(position) ?: return
        val view = holder.itemView
        val original = view.background
        val highlight = HighlightDrawable(view.context)
        // Keep the row's own background (its tap ripple) under the highlight.
        val background = if (original != null) LayerDrawable(arrayOf(original, highlight)) else highlight
        view.setBackgroundKeepingPadding(background)
        // One animation over the hold and the fade, not a start delay: frames during the hold are what notice a recycled row.
        ValueAnimator.ofFloat(0f, (HIGHLIGHT_HOLD_MS + HIGHLIGHT_FADE_MS).toFloat()).apply {
            duration = HIGHLIGHT_HOLD_MS + HIGHLIGHT_FADE_MS
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                // Recycled for another preference: stop, and give the row its own background back.
                if (holder.bindingAdapterPosition != position) {
                    animator.cancel()
                } else {
                    val fade = ((animator.animatedValue as Float - HIGHLIGHT_HOLD_MS) / HIGHLIGHT_FADE_MS).coerceIn(0f, 1f)
                    highlight.alpha = (255 * (1f - fade)).toInt()
                }
            }
            doOnEnd { if (view.background === background) view.setBackgroundKeepingPadding(original) }
            start()
        }
    }

    /**
     * The timeline's jump highlight: a lighter row with a slim accent ribbon on its leading edge. Drawn as one
     * drawable so the whole thing fades together; fading two stacked layers would let the accent bleed through.
     */
    private class HighlightDrawable(context: Context) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ThemeUtils.getColor(context, com.google.android.material.R.attr.colorPrimary)
        }
        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_header_background)
        }
        private val ribbonAlpha = ribbonPaint.alpha
        private val bodyAlpha = bodyPaint.alpha
        private val path = Path()
        private val rect = RectF()
        private var currentAlpha = 255

        private fun dp(value: Float) = value * density

        override fun draw(canvas: Canvas) {
            val b = bounds
            val ribbonRadius = dp(2f)
            path.reset()
            rect.set(b.left + dp(2f), b.top.toFloat(), b.left + dp(6f), b.bottom.toFloat())
            path.addRoundRect(rect, floatArrayOf(ribbonRadius, ribbonRadius, 0f, 0f, 0f, 0f, ribbonRadius, ribbonRadius), Path.Direction.CW)
            canvas.drawPath(path, ribbonPaint)

            val bodyRadius = dp(4f)
            path.reset()
            rect.set(b.left + dp(6f), b.top.toFloat(), b.right - dp(2f), b.bottom.toFloat())
            path.addRoundRect(rect, floatArrayOf(0f, 0f, bodyRadius, bodyRadius, bodyRadius, bodyRadius, 0f, 0f), Path.Direction.CW)
            canvas.drawPath(path, bodyPaint)
        }

        override fun setAlpha(alpha: Int) {
            currentAlpha = alpha
            ribbonPaint.alpha = ribbonAlpha * alpha / 255
            bodyPaint.alpha = bodyAlpha * alpha / 255
            invalidateSelf()
        }

        override fun getAlpha() = currentAlpha

        override fun setColorFilter(colorFilter: ColorFilter?) {
            ribbonPaint.colorFilter = colorFilter
            bodyPaint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
