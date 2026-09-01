/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Looper
import android.text.Spanned
import android.util.LruCache
import android.widget.TextView
import io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan
import ru.noties.jlatexmath.JLatexMathDrawable
import timber.log.Timber

/**
 * JLatexMathPlugin renders a formula on a background executor and shows its LaTeX source until the
 * render lands, so the formula pops in and reflows the line, again on every rebind (a rebuilt item
 * carries fresh spans). Formulas are rendered here instead, once each, and given to the spans before
 * Markwon can schedule them. They are rendered white so one bitmap serves every text color, tinted.
 */
object LatexRenderCache {

    private const val MAX_BYTES = 3 * 1024 * 1024
    private const val MAX_PIXELS = 500_000
    private const val MAX_UNRENDERABLE = 256

    // Mirrors the JLatexMathPlugin theme configured in EventHtmlRenderer.
    private const val TEXT_SIZE = 44F
    private const val INLINE_PADDING_HORIZONTAL = 8
    private const val INLINE_PADDING_VERTICAL = 24

    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.rowBytes * value.height
    }

    // JLatexMath keeps static parser state and callers render from several threads, so build one
    // formula at a time.
    private val renderLock = Any()

    private val unrenderable = HashSet<String>()

    private fun key(latex: String, isBlock: Boolean) = "${if (isBlock) 'b' else 'i'} $latex"

    private fun spansOf(text: Spanned) = text.getSpans(0, text.length, JLatexAsyncDrawableSpan::class.java)

    // The block span is this class itself, inline formulas a package private subclass of it.
    private fun JLatexAsyncDrawableSpan.isBlock() = javaClass == JLatexAsyncDrawableSpan::class.java

    /** Give every formula its bitmap before Markwon can schedule a render for it. */
    fun applyTo(textView: TextView, text: Spanned) {
        val spans = spansOf(text)
        if (spans.isEmpty()) return
        val color = textView.currentTextColor
        spans.forEach { span ->
            val drawable = span.getDrawable()
            if (drawable.hasResult()) return@forEach
            val bitmap = render(drawable.destination, span.isBlock()) ?: return@forEach
            drawable.result = LatexBitmapDrawable(bitmap, color)
        }
    }

    /**
     * Render this text's formulas up front. The timeline formats messages off the main thread, so
     * the bind that follows only picks the bitmaps up.
     */
    fun prewarm(text: CharSequence) {
        if (Looper.myLooper() == Looper.getMainLooper()) return
        val spanned = text as? Spanned ?: return
        spansOf(spanned).forEach { render(it.getDrawable().destination, it.isBlock()) }
    }

    private fun render(latex: String, isBlock: Boolean): Bitmap? {
        val key = key(latex, isBlock)
        cache.get(key)?.let { return it }
        synchronized(renderLock) {
            cache.get(key)?.let { return it }
            if (key in unrenderable) return null
            val bitmap = try {
                rasterize(build(latex, isBlock))
            } catch (failure: Throwable) {
                // Malformed LaTeX throws out of the parser, and the span keeps showing its source.
                Timber.v(failure, "Fail to render latex $latex")
                null
            }
            if (bitmap == null) {
                if (unrenderable.size >= MAX_UNRENDERABLE) unrenderable.clear()
                unrenderable.add(key)
            } else {
                cache.put(key, bitmap)
            }
            return bitmap
        }
    }

    private fun build(latex: String, isBlock: Boolean): JLatexMathDrawable {
        val builder = JLatexMathDrawable.builder(latex)
                .textSize(TEXT_SIZE)
                .color(Color.WHITE)
        if (!isBlock) {
            builder.padding(
                    INLINE_PADDING_HORIZONTAL, INLINE_PADDING_VERTICAL,
                    INLINE_PADDING_HORIZONTAL, INLINE_PADDING_VERTICAL
            )
        }
        return builder.build()
    }

    private fun rasterize(drawable: JLatexMathDrawable): Bitmap? {
        val width = drawable.intrinsicWidth
        val height = drawable.intrinsicHeight
        if (width <= 0 || height <= 0 || width * height > MAX_PIXELS) return null
        return try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(bitmap))
            bitmap
        } catch (error: OutOfMemoryError) {
            null
        }
    }

    /**
     * Markwon's size resolvers give a formula the full message width when it is narrower than that,
     * and a proportionally scaled box when it is wider, so fit the bitmap into the bounds and center
     * it rather than stretching it. The tint paints the text color the bitmap was not rendered in.
     */
    private class LatexBitmapDrawable(private val bitmap: Bitmap, color: Int) : Drawable() {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        private val target = RectF()

        init {
            setBounds(0, 0, bitmap.width, bitmap.height)
        }

        override fun onBoundsChange(bounds: Rect) {
            val scale = minOf(bounds.width() / bitmap.width.toFloat(), bounds.height() / bitmap.height.toFloat())
            val width = bitmap.width * scale
            val height = bitmap.height * scale
            val left = bounds.left + (bounds.width() - width) / 2f
            val top = bounds.top + (bounds.height() - height) / 2f
            target.set(left, top, left + width, top + height)
        }

        override fun draw(canvas: Canvas) = canvas.drawBitmap(bitmap, null, target, paint)

        override fun getIntrinsicWidth() = bitmap.width

        override fun getIntrinsicHeight() = bitmap.height

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
