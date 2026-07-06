/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

@file:Suppress("DEPRECATION")

package im.vector.app.features.html

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.widget.TextView
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import im.vector.app.core.glide.GlideApp
import im.vector.app.core.ui.PerformanceMode
import org.matrix.android.sdk.api.session.room.send.MatrixEmoteSpan
import java.lang.ref.WeakReference
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Inline image span for an MSC2545 custom emoticon, used both in the composer and the timeline.
 * Implements [MatrixEmoteSpan] so the SDK serializes it to `<img data-mx-emoticon>` when sent.
 *
 * The span reserves a fixed square box sized from the surrounding text (≈ a normal emoji) from the very
 * first layout pass — independent of whether the image has loaded — and draws the image fit-centred
 * inside it. This keeps the line height stable so the message doesn't reflow (and the list doesn't
 * flicker) when the image arrives or the item is re-rendered.
 */
class EmoteImageSpan(
        override val shortcode: String,
        override val mxcUrl: String,
        override val body: String?,
        private val resolvedUrl: String?,
) : ReplacementSpan(), MatrixEmoteSpan {

    companion object {
        private const val PLACEHOLDER = "❓"
    }

    private var drawable: Drawable? = null
    private var tv: WeakReference<TextView>? = null

    // We draw the drawable ourselves (not via an ImageView), so we must relay its frame invalidations to
    // the TextView and drive scheduled frames — otherwise animated WebP/GIF emotes never advance (and some
    // don't render their first frame at all until started).
    private val drawableCallback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) {
            tv?.get()?.invalidate()
        }

        override fun scheduleDrawable(who: Drawable, what: Runnable, time: Long) {
            tv?.get()?.postDelayed(what, time - SystemClock.uptimeMillis())
        }

        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            tv?.get()?.removeCallbacks(what)
        }
    }

    private val target = object : SimpleTarget<Drawable>() {
        override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
            drawable = resource
            resource.callback = drawableCallback
            (resource as? Animatable)?.start()
            // Size is already reserved, so only a redraw is needed — no relayout, no flicker.
            tv?.get()?.invalidate()
        }
    }

    private val bitmapTarget = object : SimpleTarget<Bitmap>() {
        override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
            val textView = tv?.get() ?: return
            drawable = BitmapDrawable(textView.resources, resource)
            textView.invalidate()
        }
    }

    fun bind(textView: TextView) {
        tv = WeakReference(textView)
        if (PerformanceMode.enabled) {
            // An animated emote redraws its whole TextView every frame; decode a single static frame instead.
            GlideApp.with(textView).asBitmap().load(resolvedUrl).into(bitmapTarget)
        } else {
            GlideApp.with(textView).load(resolvedUrl).into(target)
        }
    }

    // Box the emote to the line height so it matches an emoji glyph (which fills ~the line) at any text size.
    private fun boxSize(paint: Paint): Int {
        return (paint.descent() - paint.ascent()).roundToInt().coerceAtLeast(1)
    }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val box = boxSize(paint)
        if (fm != null) {
            val paintFm = paint.fontMetricsInt
            val centerY = (paintFm.ascent + paintFm.descent) / 2
            fm.ascent = centerY - box / 2
            fm.top = fm.ascent
            fm.descent = centerY + box / 2
            fm.bottom = fm.descent
        }
        return box
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val dr = drawable ?: run {
            canvas.drawText(PLACEHOLDER, 0, PLACEHOLDER.length, x, y.toFloat(), paint)
            return
        }
        val box = boxSize(paint)
        // Fit-centre within the reserved square, preserving aspect ratio.
        val intrinsicW = dr.intrinsicWidth.takeIf { it > 0 } ?: box
        val intrinsicH = dr.intrinsicHeight.takeIf { it > 0 } ?: box
        val scale = min(box.toFloat() / intrinsicW, box.toFloat() / intrinsicH)
        val w = (intrinsicW * scale).roundToInt().coerceAtLeast(1)
        val h = (intrinsicH * scale).roundToInt().coerceAtLeast(1)
        dr.setBounds(0, 0, w, h)
        val paintFm = paint.fontMetricsInt
        val centerY = y + (paintFm.ascent + paintFm.descent) / 2
        canvas.save()
        canvas.translate(x + (box - w) / 2f, (centerY - h / 2).toFloat())
        dr.draw(canvas)
        canvas.restore()
    }
}

/**
 * Loads the images for any custom-emote spans in this TextView's text (e.g. a room-list preview),
 * so they render inline instead of showing the bare U+FFFC placeholder.
 */
fun TextView.bindEmoteImageSpans() {
    val spanned = text as? Spanned ?: return
    spanned.getSpans(0, spanned.length, EmoteImageSpan::class.java).forEach { it.bind(this) }
}
