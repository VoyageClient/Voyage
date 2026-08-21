/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ImageView that rounds its content itself, whatever decoded it: Glide's Bitmap-only RoundedCorners
 * transform can't shape animated drawables or placeholders, and baking a radius into the bitmap
 * scales it by however much the decode is resized into the view.
 *
 * The shape follows the drawn image, not the view box, so a view that ends up bigger than the picture
 * it was sized for doesn't round the empty margin and leave the picture square.
 */
class RoundedCornerImageView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val preLollipop = Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP

    // Canvas.clipPath() is not supported on a hardware-accelerated canvas before API 18 (it throws
    // UnsupportedOperationException). On those versions the view must render into a software layer so
    // draw() receives a software canvas where clipPath works.
    private val needsSoftwareLayerForClip = Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2

    private val radii = FloatArray(8)
    private val clipPath = Path()
    private val content = RectF()
    private var hasRadius = false

    // Set once this view owns the outline, so a host that installs its own (the timeline's media item
    // does) is not stripped of it by a later drawable swap.
    private var ownsOutline = false

    // The superclass constructor calls setImageDrawable / setScaleType from the attrs, before the fields
    // above exist.
    private var initialised = false

    init {
        initialised = true
    }

    /** Uniform radius, in px, for all four corners. */
    fun setCornerRadius(radius: Float) = setCornerRadii(radius, radius, radius, radius)

    fun setCornerRadii(topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float) {
        radii[0] = topLeft; radii[1] = topLeft
        radii[2] = topRight; radii[3] = topRight
        radii[4] = bottomRight; radii[5] = bottomRight
        radii[6] = bottomLeft; radii[7] = bottomLeft
        hasRadius = topLeft > 0f || topRight > 0f || bottomRight > 0f || bottomLeft > 0f
        updateSoftwareLayer()
        applyShape()
    }

    private fun updateSoftwareLayer() {
        if (!needsSoftwareLayerForClip) return
        val desired = if (hasRadius) LAYER_TYPE_SOFTWARE else LAYER_TYPE_NONE
        if (layerType != desired) setLayerType(desired, null)
    }

    /**
     * Where the picture is actually painted. FIT_CENTER (what the media renderer uses) leaves bars
     * when the view is not the shape the image was sized for; every other scale type this view is
     * used with paints the whole box.
     */
    private fun updateContentRect() {
        val availableWidth = (width - paddingLeft - paddingRight).toFloat()
        val availableHeight = (height - paddingTop - paddingBottom).toFloat()
        content.set(paddingLeft.toFloat(), paddingTop.toFloat(), paddingLeft + availableWidth, paddingTop + availableHeight)
        if (availableWidth <= 0f || availableHeight <= 0f) return
        val drawable: Drawable = drawable ?: return
        val intrinsicWidth = drawable.intrinsicWidth
        val intrinsicHeight = drawable.intrinsicHeight
        if (intrinsicWidth <= 0 || intrinsicHeight <= 0) return
        when (scaleType) {
            ScaleType.FIT_CENTER, ScaleType.FIT_START, ScaleType.FIT_END, ScaleType.CENTER_INSIDE -> Unit
            else -> return
        }
        var scale = min(availableWidth / intrinsicWidth, availableHeight / intrinsicHeight)
        // CENTER_INSIDE never enlarges; the FIT_* family does.
        if (scaleType == ScaleType.CENTER_INSIDE) scale = min(scale, 1f)
        val drawnWidth = intrinsicWidth * scale
        val drawnHeight = intrinsicHeight * scale
        val left = when (scaleType) {
            ScaleType.FIT_START -> content.left
            ScaleType.FIT_END -> content.right - drawnWidth
            else -> content.left + (availableWidth - drawnWidth) / 2f
        }
        val top = when (scaleType) {
            ScaleType.FIT_START -> content.top
            ScaleType.FIT_END -> content.bottom - drawnHeight
            else -> content.top + (availableHeight - drawnHeight) / 2f
        }
        content.set(left, top, left + drawnWidth, top + drawnHeight)
    }

    private fun applyShape() {
        if (!hasRadius && !ownsOutline && clipPath.isEmpty) return
        updateContentRect()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (hasRadius) {
                outlineProvider = shapeOutlineProvider
                clipToOutline = true
                ownsOutline = true
                invalidateOutline()
            } else if (ownsOutline) {
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipToOutline = false
                ownsOutline = false
            }
        } else {
            clipPath.reset()
            if (hasRadius && !content.isEmpty) {
                clipPath.addRoundRect(content, radii, Path.Direction.CW)
            }
        }
        invalidate()
    }

    // Lazily built: ViewOutlineProvider is API 21+, so instantiating it in the constructor would load a
    // missing class on Ice Cream Sandwich. Only the Lollipop+ branch of [applyShape] ever touches it.
    private val shapeOutlineProvider: ViewOutlineProvider by lazy {
        object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                // Outline clipping only takes a single radius, which is all any caller here asks for.
                outline.setRoundRect(
                        content.left.roundToInt(),
                        content.top.roundToInt(),
                        content.right.roundToInt(),
                        content.bottom.roundToInt(),
                        radii[0]
                )
            }
        }
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        if (initialised) applyShape()
    }

    override fun setScaleType(scaleType: ScaleType?) {
        super.setScaleType(scaleType)
        if (initialised) applyShape()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyShape()
    }

    override fun draw(canvas: Canvas) {
        if (preLollipop && hasRadius && !clipPath.isEmpty) {
            val save = canvas.save()
            canvas.clipPath(clipPath)
            super.draw(canvas)
            canvas.restoreToCount(save)
        } else {
            super.draw(canvas)
        }
    }
}
