/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.attachmentviewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.text.TextPaint

/**
 * Double-tap seek indicator: a translucent ripple over the tapped half of the video, three play
 * arrows lighting up in sequence, and an accumulated "N seconds" label. Set as a view background;
 * it animates by invalidating itself until the one-shot run completes.
 */
class VideoForwardDrawable(private val context: Context) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = dp(12f)
        textAlign = Paint.Align.CENTER
    }

    private val playPath = Path().apply {
        moveTo(dp(10f), dp(7f))
        lineTo(dp(26f), dp(16f))
        lineTo(dp(10f), dp(25f))
        close()
    }

    /** The rect the video actually occupies on screen; the ripple is clipped and positioned to it. */
    var contentRect: Rect? = null

    private var leftSide = false
    private var animationProgress = 0f
    private var animating = false
    private var lastAnimationTime = 0L
    private var timeMs = 0L
    private var timeStr: String? = null

    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density

    fun isAnimating() = animating

    fun startAnimation(leftSide: Boolean) {
        if (this.leftSide != leftSide) {
            this.leftSide = leftSide
            timeMs = 0
            timeStr = null
        }
        animating = true
        animationProgress = 0f
        lastAnimationTime = System.currentTimeMillis()
        invalidateSelf()
    }

    /** The seconds label accumulates across rapid re-taps. */
    fun addTime(deltaMs: Long) {
        timeMs += deltaMs
        timeStr = context.getString(R.string.attachment_viewer_seek_seconds, (timeMs / 1000).toInt())
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (!animating) return
        val rect = contentRect?.takeIf { !it.isEmpty } ?: bounds
        var x = rect.left + (rect.width() - intrinsicWidth) / 2
        val y = rect.top + (rect.height() - intrinsicHeight) / 2
        x += if (leftSide) {
            -(rect.width() / 4 - dp(16f).toInt())
        } else {
            rect.width() / 4 + dp(16f).toInt()
        }

        val alphaRamp = if (animationProgress <= 0.7f) {
            (animationProgress / 0.3f).coerceAtMost(1f)
        } else {
            1f - (animationProgress - 0.7f) / 0.3f
        }

        canvas.save()
        canvas.clipRect(rect.left, rect.top, rect.right, rect.bottom)
        paint.alpha = (80 * alphaRamp).toInt()
        val circleRadius = Math.max(rect.width(), rect.height()) / 2f
        canvas.drawCircle(
                x + circleRadius / 2f * (if (leftSide) -1 else 1),
                y + dp(16f),
                circleRadius,
                paint
        )
        canvas.restore()

        timeStr?.let {
            textPaint.alpha = (255 * alphaRamp).toInt()
            canvas.drawText(it, x + intrinsicWidth * (if (leftSide) -1f else 1f), y + intrinsicHeight + dp(15f), textPaint)
        }

        canvas.save()
        if (leftSide) {
            canvas.rotate(180f, x.toFloat(), y + intrinsicHeight / 2f)
        }
        canvas.translate(x.toFloat(), y.toFloat())
        for (i in 0 until 3) {
            val progress = animationProgress - i * 0.2f
            if (progress in 0f..0.6f) {
                val a = if (progress < 0.4f) {
                    (255 * progress / 0.2f).toInt().coerceAtMost(255)
                } else {
                    (255 * (1f - (progress - 0.4f) / 0.2f)).toInt()
                }
                paint.alpha = a
                canvas.drawPath(playPath, paint)
            }
            canvas.translate(dp(18f), 0f)
        }
        canvas.restore()

        val now = System.currentTimeMillis()
        val dt = (now - lastAnimationTime).coerceAtMost(17)
        lastAnimationTime = now
        animationProgress += dt / 800f
        if (animationProgress >= 1f) {
            animationProgress = 0f
            animating = false
            timeMs = 0
            timeStr = null
        }
        invalidateSelf()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        textPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSPARENT

    override fun getIntrinsicWidth(): Int = dp(32f).toInt()

    override fun getIntrinsicHeight(): Int = dp(32f).toInt()
}
