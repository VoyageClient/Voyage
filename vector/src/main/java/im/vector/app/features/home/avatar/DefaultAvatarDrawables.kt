/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.avatar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.settings.AvatarShape

/** Paints the avatar's shape; subclasses fill it with whatever the style draws. */
abstract class ShapedAvatarDrawable(private val shape: AvatarShape) : Drawable() {

    protected val boundsF = RectF()

    // Masks the way RoundedClipDrawable does, with an anti-aliased DST_OUT pass over the shape's
    // inverse inside a layer. clipPath on a curve does nothing on a hardware canvas below API 18.
    private val maskPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    }
    private val maskPath by lazy { Path() }
    private var maskedBounds: RectF? = null

    protected fun drawShape(canvas: Canvas, paint: Paint) {
        when (shape) {
            AvatarShape.CIRCLE -> canvas.drawOval(boundsF, paint)
            AvatarShape.ROUNDED -> {
                val radius = minOf(boundsF.width(), boundsF.height()) * AvatarRenderer.ROUNDED_CORNER_PERCENT
                canvas.drawRoundRect(boundsF, radius, radius, paint)
            }
            AvatarShape.SQUARE -> canvas.drawRect(boundsF, paint)
        }
    }

    /** Runs [draw] with anything outside the avatar's shape erased, for content that overflows it. */
    protected fun clippedToShape(canvas: Canvas, draw: () -> Unit) {
        if (shape == AvatarShape.SQUARE) {
            val saved = canvas.save()
            canvas.clipRect(boundsF)
            draw()
            canvas.restoreToCount(saved)
            return
        }
        @Suppress("DEPRECATION")
        val saved = canvas.saveLayer(boundsF, null, Canvas.ALL_SAVE_FLAG)
        draw()
        canvas.drawPath(maskPath(), maskPaint)
        canvas.restoreToCount(saved)
    }

    private fun maskPath(): Path {
        if (maskedBounds == boundsF) return maskPath
        maskPath.reset()
        // reset() drops the fill type, so set it after.
        maskPath.fillType = Path.FillType.INVERSE_WINDING
        if (shape == AvatarShape.CIRCLE) {
            maskPath.addOval(boundsF, Path.Direction.CW)
        } else {
            val radius = minOf(boundsF.width(), boundsF.height()) * AvatarRenderer.ROUNDED_CORNER_PERCENT
            maskPath.addRoundRect(boundsF, radius, radius, Path.Direction.CW)
        }
        maskedBounds = RectF(boundsF)
        return maskPath
    }

    final override fun draw(canvas: Canvas) {
        boundsF.set(bounds)
        onDraw(canvas)
    }

    protected abstract fun onDraw(canvas: Canvas)

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * A name can start with an emoji, making the "initial" an emoji cluster; TextDrawable renders it
 * via Paint.drawText, which has no glyph under Twemoji, so draw the sprite over the colored shape.
 */
class TwemojiLetterDrawable(
        private val sprite: Bitmap,
        color: Int,
        shape: AvatarShape,
) : ShapedAvatarDrawable(shape) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val spriteDst = RectF()

    override fun onDraw(canvas: Canvas) {
        drawShape(canvas, backgroundPaint)
        // Match TextDrawable's letter proportions (~half the shorter side).
        val size = minOf(boundsF.width(), boundsF.height()) * SPRITE_RATIO
        spriteDst.set(0f, 0f, size, size)
        spriteDst.offset(boundsF.centerX() - size / 2, boundsF.centerY() - size / 2)
        canvas.drawBitmap(sprite, null, spriteDst, spritePaint)
    }

    override fun setAlpha(alpha: Int) {
        backgroundPaint.alpha = alpha
        spritePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        backgroundPaint.colorFilter = colorFilter
        spritePaint.colorFilter = colorFilter
    }

    companion object {
        private const val SPRITE_RATIO = 0.55f
    }
}

/** The silhouettes drawn over a colored shape, in a normalized 0..1 box. */
enum class AvatarGlyph(val overflows: Boolean) {
    PERSON(overflows = true),
    EGG(overflows = false);

    fun path(): Path = Path().apply {
        when (this@AvatarGlyph) {
            PERSON -> {
                addCircle(0.5f, 0.40f, 0.19f, Path.Direction.CW)
                // Runs off the bottom edge; the shape mask crops it.
                addCircle(0.5f, 1.00f, 0.32f, Path.Direction.CW)
            }
            // Measured off Twitter's own egg: same proportions, widest point and curvature.
            EGG -> {
                moveTo(0.5f, 0.16f)
                cubicTo(0.62f, 0.16f, 0.7475f, 0.3475f, 0.7475f, 0.5475f)
                cubicTo(0.7475f, 0.7275f, 0.62f, 0.84f, 0.5f, 0.84f)
                cubicTo(0.38f, 0.84f, 0.2525f, 0.7275f, 0.2525f, 0.5475f)
                cubicTo(0.2525f, 0.3475f, 0.38f, 0.16f, 0.5f, 0.16f)
                close()
            }
        }
    }
}

/** A silhouette over a colored shape: the Generic person and the Twitter egg. */
class GlyphAvatarDrawable(
        shape: AvatarShape,
        backgroundColor: Int,
        glyph: AvatarGlyph,
        glyphColor: Int,
) : ShapedAvatarDrawable(shape) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundColor }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = glyphColor }
    private val overflows = glyph.overflows
    private val source = glyph.path()
    private val scaled = Path()
    private val matrix = Matrix()
    private val glyphBox = RectF()

    override fun onDraw(canvas: Canvas) {
        if (overflows) clippedToShape(canvas) { drawGlyph(canvas) } else drawGlyph(canvas)
    }

    // The background fills the bounds, but the silhouette keeps its proportions in the largest
    // square that fits, so a wider-than-tall preview cell doesn't stretch it.
    private fun drawGlyph(canvas: Canvas) {
        drawShape(canvas, backgroundPaint)
        val side = minOf(boundsF.width(), boundsF.height())
        glyphBox.set(0f, 0f, side, side)
        glyphBox.offset(boundsF.centerX() - side / 2, boundsF.centerY() - side / 2)
        matrix.setRectToRect(UNIT, glyphBox, Matrix.ScaleToFit.FILL)
        source.transform(matrix, scaled)
        canvas.drawPath(scaled, glyphPaint)
    }

    override fun setAlpha(alpha: Int) {
        backgroundPaint.alpha = alpha
        glyphPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        backgroundPaint.colorFilter = colorFilter
        glyphPaint.colorFilter = colorFilter
    }

    companion object {
        private val UNIT = RectF(0f, 0f, 1f, 1f)
    }
}

/** A glyph over a colored shape, centered on its ink bounds rather than on its baseline. */
class TextAvatarDrawable(
        shape: AvatarShape,
        backgroundColor: Int,
        private val text: String,
        textColor: Int,
) : ShapedAvatarDrawable(shape) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundColor }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        typeface = Typeface.DEFAULT_BOLD
    }
    private val inkBounds = Rect()

    override fun onDraw(canvas: Canvas) {
        drawShape(canvas, backgroundPaint)
        // Match TextDrawable's letter proportions (~half the shorter side).
        textPaint.textSize = minOf(boundsF.width(), boundsF.height()) / 2
        textPaint.getTextBounds(text, 0, text.length, inkBounds)
        canvas.drawText(
                text,
                boundsF.centerX() - (inkBounds.left + inkBounds.right) / 2f,
                boundsF.centerY() - (inkBounds.top + inkBounds.bottom) / 2f,
                textPaint,
        )
    }

    override fun setAlpha(alpha: Int) {
        backgroundPaint.alpha = alpha
        textPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        backgroundPaint.colorFilter = colorFilter
        textPaint.colorFilter = colorFilter
    }
}
