/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.preference

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import im.vector.app.R
import im.vector.app.core.ui.colorpicker.ColorSwatches
import im.vector.app.core.ui.colorpicker.ProfileColorPickerDialogFragment

/** A settings row previewing a name color as a round swatch, with the hex as summary. */
class ProfileColorPreference : Preference {

    private var swatchView: ImageView? = null
    private var color: Int? = null
    private var renderedColor: Int? = null
    private var fadeDrawable: Drawable? = null
    private var fadeAnimator: ValueAnimator? = null

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(context, attrs, defStyle)

    init {
        widgetLayoutResource = R.layout.vector_settings_color_swatch
        isIconSpaceReserved = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        swatchView = holder.itemView.findViewById(R.id.settings_color_swatch)
        refreshSwatch(animate = false)
    }

    fun setColor(@ColorInt color: Int, hex: String, light: Boolean, origin: ProfileColorPickerDialogFragment.Origin) {
        this.color = color
        summary = ProfileColorPickerDialogFragment.describe(context, hex, light, origin)
        refreshSwatch()
    }

    private fun refreshSwatch(animate: Boolean = true) {
        val color = color ?: return
        val swatch = swatchView ?: return
        val previous = swatch.drawable
        if (!animate || previous == null) {
            clearFade()
            swatch.setImageDrawable(ColorSwatches.round(color))
            swatch.alpha = 1f
            renderedColor = color
            return
        }
        if (renderedColor == color) return
        val next = ColorSwatches.round(color)
        clearFade()
        val layered = LayerDrawable(arrayOf(next, previous))
        swatch.setImageDrawable(layered)
        lateinit var animator: ValueAnimator
        animator = ValueAnimator.ofInt(255, 0).apply {
            duration = COLOR_FADE_MS.toLong()
            addUpdateListener {
                previous.alpha = it.animatedValue as Int
                layered.invalidateSelf()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (fadeAnimator !== animator) return
                    clearFade()
                    previous.alpha = 255
                    if (swatch.drawable === layered) swatch.setImageDrawable(next)
                }
            })
        }
        renderedColor = color
        fadeDrawable = layered
        fadeAnimator = animator
        animator.start()
    }

    private fun clearFade() {
        val animator = fadeAnimator
        fadeAnimator = null
        fadeDrawable = null
        animator?.cancel()
    }

    private companion object {
        const val COLOR_FADE_MS = 220
    }
}
