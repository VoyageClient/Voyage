/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** Wraps its content but never grows past [maxHeightFraction] of the screen height. */
class MaxHeightFrameLayout @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var maxHeightFraction: Float = 1f
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Read per measure so a rotation picks up the new screen height.
        val cap = (resources.displayMetrics.heightPixels * maxHeightFraction).toInt()
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val capped = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST)
            MeasureSpec.EXACTLY -> MeasureSpec.makeMeasureSpec(minOf(size, cap), MeasureSpec.EXACTLY)
            else -> MeasureSpec.makeMeasureSpec(minOf(size, cap), MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, capped)
    }
}
