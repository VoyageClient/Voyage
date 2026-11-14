/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.platform

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorInt
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.ColorUtils
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.shape.MaterialShapeDrawable

/** Finds the color of the bar at the top of a window, for painting the strip behind the status bar. */
internal object TopBarColor {

    /** The first shown toolbar whose top sits at most [maxTop] px below [root]'s top. */
    fun findTopToolbar(root: View, maxTop: Int): Toolbar? {
        val rootLocation = IntArray(2)
        root.getLocationInWindow(rootLocation)
        return find(root, rootLocation[1] + maxTop, IntArray(2))
    }

    private fun find(view: View, maxWindowTop: Int, location: IntArray): Toolbar? {
        if (!view.isShown) return null
        if (view is Toolbar) {
            view.getLocationInWindow(location)
            return view.takeIf { location[1] <= maxWindowTop }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) find(view.getChildAt(i), maxWindowTop, location)?.let { return it }
        }
        return null
    }

    /**
     * What shows behind [toolbar]: its background and those of its parents up to the first opaque one
     * (or [stopAt]), with a collapsing layout's content scrim on the way, composited over [base].
     * [stopAtBackground] stands in for [stopAt]'s own background, which the caller may have wrapped.
     */
    @ColorInt
    fun resolve(toolbar: View, stopAt: View, @ColorInt base: Int, stopAtBackground: Drawable? = stopAt.background): Int {
        val layers = mutableListOf<Int>()
        var view: View? = toolbar
        while (view != null) {
            if (view is CollapsingToolbarLayout) (view.contentScrim as? ColorDrawable)?.let { layers.add(it.effectiveColor()) }
            val background = (if (view === stopAt) stopAtBackground else view.background)?.solidColor()
            if (background != null) {
                layers.add(background)
                if (Color.alpha(background) == 255) break
            }
            if (view === stopAt) break
            view = view.parent as? View
        }
        var color = base
        for (layer in layers.asReversed()) color = ColorUtils.compositeColors(layer, color)
        return color
    }

    private fun Drawable.solidColor(): Int? = when (this) {
        is ColorDrawable -> effectiveColor()
        is MaterialShapeDrawable -> fillColor?.defaultColor?.let { ColorUtils.setAlphaComponent(it, Color.alpha(it) * alpha / 255) }
        else -> null
    }

    // ColorDrawable.getAlpha() folds a setAlpha() fade into the color's own alpha
    private fun ColorDrawable.effectiveColor(): Int = ColorUtils.setAlphaComponent(color, alpha)
}
