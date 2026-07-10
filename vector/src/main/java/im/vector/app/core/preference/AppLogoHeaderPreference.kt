/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.preference

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.Interpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import im.vector.app.R
import im.vector.app.features.settings.AppLogo
import im.vector.app.features.themes.ThemeUtils

/**
 * Non-interactive header showing the current app-logo glyph tinted with the accent colour, with the
 * app name below it drawn in the bundled serif font. Tapping the glyph spins it up and back down.
 */
class AppLogoHeaderPreference @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.vector_preference_app_logo_header
        isSelectable = false
        isPersistent = false
    }

    private var spinAnimator: ObjectAnimator? = null

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val itemContext = holder.itemView.context

        (holder.findViewById(R.id.appLogoHeaderImage) as? ImageView)?.apply {
            setImageResource(AppLogo.current(itemContext).logoRes)
            setColorFilter(ThemeUtils.getColor(itemContext, com.google.android.material.R.attr.colorAccent), PorterDuff.Mode.SRC_IN)
            setOnClickListener { spin(it) }
        }

        (holder.findViewById(R.id.appLogoHeaderText) as? TextView)?.let { text ->
            runCatching { Typeface.createFromAsset(itemContext.assets, "fonts/anthropic_serif.otf") }
                    .getOrNull()
                    ?.let { text.typeface = it }
        }
    }

    private fun spin(view: View) {
        if (spinAnimator?.isRunning == true) return
        // A whole number of turns, so the glyph comes to rest exactly where it started.
        spinAnimator = ObjectAnimator.ofFloat(view, View.ROTATION, 0f, SPIN_TURNS * 360f).apply {
            duration = SPIN_DURATION_MS
            interpolator = SpinInterpolator(rampUpEnd = 0.13f, rampDownStart = 0.75f)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.rotation = 0f
                }
            })
            start()
        }
    }

    /**
     * Position curve whose speed profile is a smoothed trapezoid: cubic ramp up over
     * [0, rampUpEnd] (starts barely moving, then the acceleration itself keeps growing),
     * full speed until [rampDownStart], then a mirrored cubic ramp down to a standstill.
     * The returned position is the normalized integral of that speed.
     */
    private class SpinInterpolator(private val rampUpEnd: Float, private val rampDownStart: Float) : Interpolator {
        private val rampUpArea = rampUpEnd / 4f
        private val cruiseArea = rampDownStart - rampUpEnd
        private val rampDownArea = (1f - rampDownStart) / 4f
        private val totalArea = rampUpArea + cruiseArea + rampDownArea

        override fun getInterpolation(input: Float): Float {
            val t = input.coerceIn(0f, 1f)
            val position = when {
                t < rampUpEnd -> {
                    val x = t / rampUpEnd
                    rampUpEnd * x * x * x * x / 4f
                }
                t < rampDownStart -> rampUpArea + (t - rampUpEnd)
                else -> {
                    val x = (1f - t) / (1f - rampDownStart)
                    rampUpArea + cruiseArea + (rampDownArea - (1f - rampDownStart) * x * x * x * x / 4f)
                }
            }
            return position / totalArea
        }
    }

    companion object {
        private const val SPIN_TURNS = 7
        private const val SPIN_DURATION_MS = 1000L
    }
}
