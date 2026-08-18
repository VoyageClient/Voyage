/*
 * Copyright 2018-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.preference

import android.animation.Animator
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.animation.doOnEnd
import androidx.core.graphics.drawable.DrawableCompat
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import im.vector.app.features.html.bindEmoteImageSpans
import im.vector.app.features.themes.ThemeUtils
import org.matrix.android.sdk.api.extensions.orFalse
import timber.log.Timber

/**
 * create a Preference with a dedicated click/long click methods.
 * It also allow the title to be displayed on several lines
 */
open class VectorPreference : Preference {

    var mTypeface = Typeface.NORMAL

    /** Ellipsize the summary instead of letting free-form content (e.g. a biography) grow the row. */
    var singleLineSummary = false

    /**
     * Callback to be invoked when this Preference is long clicked.
     */
    var onPreferenceLongClickListener: OnPreferenceLongClickListener? = null

    /**
     * Interface definition for a callback to be invoked when a preference is
     * long clicked.
     */
    interface OnPreferenceLongClickListener {
        /**
         * Called when a Preference has been clicked.
         *
         * @param preference The Preference that was clicked.
         * @return True if the click was handled.
         */
        fun onPreferenceLongClick(preference: Preference): Boolean
    }

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(context, attrs, defStyle)

    init {
        // Set to false to remove the space when there is no icon
        isIconSpaceReserved = true
    }

    var isHighlighted = false
        set(value) {
            field = value
            notifyChanged()
        }

    var tintIcon = false
        set(value) {
            field = value
            notifyChanged()
        }

    var currentHighlightAnimator: Animator? = null

    // The themed ripple, not a flat transparent color: rows must keep their tap feedback when the
    // deep-link highlight isn't running (the preference layout's own background gets overwritten by
    // the highlight animation above).
    private fun restoreRippleBackground(itemView: android.view.View) {
        val ta = itemView.context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        itemView.background = ta.getDrawable(0)
        ta.recycle()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        val itemView = holder.itemView
        addClickListeners(itemView)

        // display the title in multi-line to avoid ellipsis.
        try {
            val title = holder.findViewById(android.R.id.title) as? TextView
            val summary = holder.findViewById(android.R.id.summary) as? TextView
            if (title != null) {
                title.isSingleLine = false
                title.setTypeface(null, mTypeface)
            }

            summary?.setTypeface(null, mTypeface)
            summary?.apply {
                isSingleLine = singleLineSummary
                maxLines = if (singleLineSummary) 1 else Int.MAX_VALUE
                ellipsize = if (singleLineSummary) android.text.TextUtils.TruncateAt.END else null
            }

            // cancel existing animation (find a way to resume if happens during anim?)
            currentHighlightAnimator?.cancel()
            if (isHighlighted) {
                val colorFrom = Color.TRANSPARENT
                val colorTo = ThemeUtils.getColor(itemView.context, com.google.android.material.R.attr.colorPrimary)
                currentHighlightAnimator = ValueAnimator.ofObject(ArgbEvaluator(), colorFrom, colorTo).apply {
                    duration = 250 // milliseconds
                    addUpdateListener { animator ->
                        itemView.setBackgroundColor(animator.animatedValue as Int)
                    }
                    doOnEnd {
                        currentHighlightAnimator = ValueAnimator.ofObject(ArgbEvaluator(), colorTo, colorFrom).apply {
                            duration = 250 // milliseconds
                            addUpdateListener { animator ->
                                itemView.setBackgroundColor(animator.animatedValue as Int)
                            }
                            doOnEnd {
                                isHighlighted = false
                                restoreRippleBackground(itemView)
                            }
                            start()
                        }
                    }
                    startDelay = 200
                    start()
                }
            } else {
                restoreRippleBackground(itemView)
            }
        } catch (e: Exception) {
            Timber.e(e, "onBindView")
        }

        super.onBindViewHolder(holder)

        // After super, which is what sets the title/summary text: a preference showing user-provided text
        // (a biography, a room name) can carry custom emoticons, which only load once bound to their view.
        (holder.findViewById(android.R.id.title) as? TextView)?.bindEmoteImageSpans()
        (holder.findViewById(android.R.id.summary) as? TextView)?.bindEmoteImageSpans()

        if (tintIcon) {
            // Tint after super, which is what actually sets the icon drawable on the ImageView. Tint the
            // drawable directly via DrawableCompat — pre-21, ImageViewCompat.setImageTintList is a no-op for
            // plain (non-AppCompat) ImageViews, which is what the preference row icon is, so it stays black.
            val icon = holder.findViewById(android.R.id.icon) as? ImageView
            icon?.drawable?.let { drawable ->
                val color = ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
                val tinted = DrawableCompat.wrap(drawable.mutate())
                DrawableCompat.setTint(tinted, color)
                icon.setImageDrawable(tinted)
            }
        }
    }

    /**
     * @param view
     */
    private fun addClickListeners(view: View) {
        view.setOnLongClickListener {
            onPreferenceLongClickListener?.onPreferenceLongClick(this@VectorPreference).orFalse()
        }

        view.setOnClickListener {
            // call only the click listener
            onPreferenceClickListener?.onPreferenceClick(this@VectorPreference)
        }
    }
}
