/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Trace
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View

/**
 * We want to use a custom view for rendering an emoji.
 * With generic textview, the performance in the recycler view are very bad
 */
class EmojiDrawView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var mLayout: StaticLayout? = null
        set(value) {
            field = value
            invalidate()
        }

    var emoji: String? = null
        set(value) {
            field = value
            bitmap = value?.let { twemojiResolver?.invoke(it) }
        }

    private var bitmap: Bitmap? = null

    private val bitmapDest = Rect()

    override fun onDraw(canvas: Canvas) {
        val tracing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2
        if (tracing) Trace.beginSection("EmojiDrawView.onDraw")
        super.onDraw(canvas)
        val sprite = bitmap
        if (sprite != null) {
            // Match the inset the text glyph had in the cell rather than filling it edge to edge.
            val size = (minOf(width, height) * SPRITE_SCALE).toInt()
            val left = (width - size) / 2
            val top = (height - size) / 2
            bitmapDest.set(left, top, left + size, top + size)
            canvas.drawBitmap(sprite, null, bitmapDest, bitmapPaint)
            if (tracing) Trace.endSection()
            return
        }
        val layout = mLayout
        if (layout != null) {
            canvas.save()
            val layoutWidth = layout.width.toFloat()
            val layoutHeight = layout.height.toFloat()
            // Scale to fit the cell (down only) so wide multi-emoji / text reactions stay inside it.
            val scale = minOf(1f, width / layoutWidth, height / layoutHeight)
            canvas.translate(width / 2f, height / 2f)
            canvas.scale(scale, scale)
            canvas.translate(-layoutWidth / 2f, -layoutHeight / 2f)
            layout.draw(canvas)
            canvas.restore()
        }
        if (tracing) Trace.endSection()
    }

    companion object {
        val tPaint = TextPaint()

        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        private const val SPRITE_SCALE = 0.7f

        /** Resolves an emoji glyph to a Twemoji sprite, or null when Twemoji is off / unavailable. */
        var twemojiResolver: ((String) -> Bitmap?)? = null

        /** Spans emoji inside a string with Twemoji sprites (for multi-glyph reactions with no single sprite). */
        var twemojiSpanify: ((CharSequence) -> CharSequence)? = null

        var emojiSize = 40

        /** Whether the shared paint has been sized at least once (so a fallback config can skip if so). */
        var configured = false
            private set

        fun configureTextPaint(context: Context, typeface: Typeface?) {
            configured = true
            tPaint.isAntiAlias = true
            tPaint.textSize = 24 * context.resources.displayMetrics.density
            tPaint.color = Color.LTGRAY
            typeface?.let {
                tPaint.typeface = it
            }

            emojiSize = tPaint.measureText("😅").toInt()
        }
    }
}
