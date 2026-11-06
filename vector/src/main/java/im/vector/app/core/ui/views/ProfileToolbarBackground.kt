/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.animation.ValueAnimator
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.ColorUtils
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import im.vector.app.features.themes.ThemeUtils

/**
 * Gives a profile screen's pinned toolbar the app's usual toolbar color once the header has collapsed
 * under it.
 *
 * Only the toolbar is painted: the collapsing layout's content scrim spans the whole header, so a tinted
 * scrim would wash over the avatar and name while it fades out on the way back down.
 */
class ProfileToolbarBackground(
        private val toolbar: Toolbar,
        private val collapsingToolbarLayout: CollapsingToolbarLayout,
) : AppBarLayout.OnOffsetChangedListener {

    private val color = ThemeUtils.getColor(toolbar.context, im.vector.lib.ui.styles.R.attr.vctr_toolbar_background)
    private var collapsed = false
    private var alpha = 0
    private var animator: ValueAnimator? = null

    init {
        setAlpha(0)
    }

    override fun onOffsetChanged(appBarLayout: AppBarLayout, verticalOffset: Int) {
        // Same test CollapsingToolbarLayout uses to show its scrim
        val nowCollapsed = collapsingToolbarLayout.height + verticalOffset < collapsingToolbarLayout.scrimVisibleHeightTrigger
        if (nowCollapsed == collapsed) return
        collapsed = nowCollapsed
        // Fade in like the scrim, but clear at once on the way back so the header never shows through it
        apply(animate = collapsed)
    }

    fun release() {
        animator?.cancel()
        animator = null
    }

    private fun apply(animate: Boolean) {
        val target = if (collapsed) 255 else 0
        animator?.cancel()
        animator = null
        if (!animate) {
            setAlpha(target)
            return
        }
        animator = ValueAnimator.ofInt(alpha, target).apply {
            duration = collapsingToolbarLayout.scrimAnimationDuration
            addUpdateListener { setAlpha(it.animatedValue as Int) }
            start()
        }
    }

    private fun setAlpha(value: Int) {
        alpha = value
        toolbar.setBackgroundColor(ColorUtils.setAlphaComponent(color, value))
    }
}
