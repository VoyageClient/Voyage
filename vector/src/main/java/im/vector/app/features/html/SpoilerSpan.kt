/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import android.animation.ValueAnimator
import android.graphics.BlurMaskFilter
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import androidx.core.animation.doOnEnd
import androidx.core.graphics.ColorUtils
import im.vector.app.core.resources.ColorProvider

/**
 * Mimics element-web's spoiler: the content stays fully rendered (mentions, links, formatting) but is
 * blurred and tinted while hidden, then smoothly fades into focus when tapped.
 *
 * [blurFraction] runs 1f (fully hidden) -> 0f (revealed). Glyphs are blurred with a [BlurMaskFilter],
 * which the platform ignores on a hardware layer, so the host view is switched to a software layer
 * while anything is still blurred (see TextView.applySpoilerRenderLayer).
 */
class SpoilerSpan(private val colorProvider: ColorProvider) : ClickableSpan() {

    var isRevealed = false
        private set

    var blurFraction = 1f
        private set

    private var animator: ValueAnimator? = null

    private val tintColor by lazy {
        colorProvider.getColorFromAttribute(im.vector.lib.ui.styles.R.attr.vctr_spoiler_background_color)
    }

    override fun onClick(widget: View) {
        isRevealed = !isRevealed
        val target = if (isRevealed) 0f else 1f
        animator?.cancel()
        widget.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        // Re-set the text so any contained mention re-measures (hidden = name width, revealed = chip width).
        (widget as? TextView)?.let { it.text = it.text }
        animator = ValueAnimator.ofFloat(blurFraction, target).apply {
            duration = REVEAL_DURATION_MS
            addUpdateListener {
                blurFraction = it.animatedValue as Float
                widget.invalidate()
            }
            doOnEnd { if (blurFraction == 0f) restoreLayerIfClear(widget) }
            start()
        }
    }

    override fun updateDrawState(tp: TextPaint) {
        if (blurFraction > 0f) {
            tp.maskFilter = BlurMaskFilter((tp.textSize * MAX_BLUR_RATIO * blurFraction).coerceAtLeast(0.1f), BlurMaskFilter.Blur.NORMAL)
            tp.color = ColorUtils.blendARGB(tp.color, tintColor, blurFraction)
        }
        // Once revealed, leave the paint untouched so mentions, links and any other inline
        // formatting inside the spoiler keep their own styling.
    }

    private fun restoreLayerIfClear(widget: View) {
        val text = (widget as? TextView)?.text as? Spanned ?: return
        val stillBlurred = text.getSpans(0, text.length, SpoilerSpan::class.java).any { it.blurFraction > 0f }
        if (!stillBlurred) widget.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    companion object {
        private const val REVEAL_DURATION_MS = 200L
        private const val MAX_BLUR_RATIO = 0.4f
    }
}
