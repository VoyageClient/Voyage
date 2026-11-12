/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.platform

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView

/**
 * Carries a dimming overlay (the waiting cover a screen shows while it works) out onto the system-bar strips,
 * which sit in the root's padding where the overlay can't reach.
 */
internal object SystemBarScrim {

    /** Every view under [root], shown or not, whose background is [color]. List items are never overlays. */
    fun findOverlays(root: View, @ColorInt color: Int): List<View> {
        val found = mutableListOf<View>()
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), color, found)
        return found
    }

    private fun find(view: View, @ColorInt color: Int, found: MutableList<View>) {
        if ((view.background as? ColorDrawable)?.color == color) found.add(view)
        if (view is ViewGroup && view !is RecyclerView && view !is AbsListView) {
            for (i in 0 until view.childCount) find(view.getChildAt(i), color, found)
        }
    }

    /** [overlay]'s background color, faded by its alpha. */
    @ColorInt
    fun color(overlay: View): Int {
        val color = (overlay.background as ColorDrawable).color
        return ColorUtils.setAlphaComponent(color, (Color.alpha(color) * overlay.alpha).toInt())
    }

    /**
     * The area of a [width] x [height] root the scrim covers: [overlay] stretched out to the root's edge on every
     * side where it reaches the [content] edge. False, with [out] emptied, when it reaches none.
     */
    fun region(overlay: Rect, content: Rect, width: Int, height: Int, out: Rect): Boolean {
        out.setEmpty()
        if (!Rect.intersects(overlay, content)) return false
        val left = if (overlay.left <= content.left) 0 else overlay.left
        val top = if (overlay.top <= content.top) 0 else overlay.top
        val right = if (overlay.right >= content.right) width else overlay.right
        val bottom = if (overlay.bottom >= content.bottom) height else overlay.bottom
        if (left >= content.left && top >= content.top && right <= content.right && bottom <= content.bottom) return false
        out.set(left, top, right, bottom)
        return true
    }
}

/**
 * Paints [color] over [region] except where it overlaps [content]: inside the content the overlay itself already
 * dims whatever shows through, so painting there too would dim it twice.
 */
internal class SystemBarScrimDrawable : Drawable() {

    val region = Rect()
    val content = Rect()

    @ColorInt
    var color: Int = Color.TRANSPARENT
        set(value) {
            if (field == value) return
            field = value
            paint.color = value
            invalidateSelf()
        }

    private val paint = Paint()

    override fun draw(canvas: Canvas) {
        if (Color.alpha(color) == 0 || region.isEmpty) return
        val top = minOf(content.top, region.bottom)
        val bottom = maxOf(content.bottom, region.top)
        drawRect(canvas, region.left, region.top, region.right, top)
        drawRect(canvas, region.left, bottom, region.right, region.bottom)
        drawRect(canvas, region.left, maxOf(top, region.top), minOf(content.left, region.right), minOf(bottom, region.bottom))
        drawRect(canvas, maxOf(content.right, region.left), maxOf(top, region.top), region.right, minOf(bottom, region.bottom))
    }

    private fun drawRect(canvas: Canvas, left: Int, top: Int, right: Int, bottom: Int) {
        if (left < right && top < bottom) canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
