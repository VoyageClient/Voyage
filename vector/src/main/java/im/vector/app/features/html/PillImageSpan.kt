/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

@file:Suppress("DEPRECATION")

package im.vector.app.features.html

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ReplacementSpan
import android.text.style.TextAppearanceSpan
import android.widget.TextView
import androidx.annotation.UiThread
import androidx.appcompat.content.res.AppCompatResources
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.material.chip.ChipDrawable
import im.vector.app.R
import im.vector.app.core.extensions.isMatrixId
import im.vector.app.core.glide.GlideRequests
import im.vector.app.core.ui.PerformanceMode
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.emoji.TwemojiSpan
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.tools.withEmojis
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.extensions.orTrue
import org.matrix.android.sdk.api.session.room.send.MatrixItemSpan
import org.matrix.android.sdk.api.util.MatrixItem
import java.lang.ref.WeakReference
import kotlin.math.ceil

/**
 * This span is able to replace a text by a [ChipDrawable]
 * It's needed to call [bind] method to start requesting avatar, otherwise only the placeholder icon will be displayed if not already cached.
 * Implements MatrixItemSpan so that it could be automatically transformed in matrix links and displayed as pills.
 */
class PillImageSpan(
        private val glideRequests: GlideRequests,
        private val avatarRenderer: AvatarRenderer,
        private val context: Context,
        override val matrixItem: MatrixItem
) : ReplacementSpan(), MatrixItemSpan {

    // ChipDrawable draws its label straight to the canvas, so Twemoji sprite spans can't render
    // through it. When the name spanifies to sprites, the chip gets empty text (bounds widened by
    // the label width) and [drawEmojiLabel] draws the spanned label at the chip's text position.
    private var emojiLabel: CharSequence? = null
    private var emojiLabelPaint: TextPaint? = null
    private var emojiLabelLayout: StaticLayout? = null
    private var emojiLabelLayoutWidth = -1

    private val pillDrawable = createChipDrawable()
    private val target = PillImageSpanTarget(this)
    private var tv: WeakReference<TextView>? = null
    private val spoilerTextPaint = TextPaint()

    @UiThread
    fun bind(textView: TextView) {
        tv = WeakReference(textView)
        avatarRenderer.render(glideRequests, matrixItem, target)
    }

    // ReplacementSpan *****************************************************************************

    override fun getSize(
            paint: Paint, text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
    ): Int {
        // While hidden, occupy only the (blurred) name's width with regular text line metrics, so
        // the mention flows like the surrounding text and leaves no gap. The chip width is reserved
        // again once revealed (the host view is re-laid-out on toggle).
        if (isInsideHiddenSpoiler(text, start, end)) {
            fm?.let { paint.getFontMetricsInt(it) }
            return ceil(paint.measureText(matrixItem.getBestName())).toInt()
        }
        val rect = pillDrawable.bounds
        if (fm != null) {
            val fmPaint = paint.fontMetricsInt
            val fontHeight = fmPaint.bottom - fmPaint.top
            val drHeight = rect.bottom - rect.top
            val top = drHeight / 2 - fontHeight / 4
            val bottom = drHeight / 2 + fontHeight / 4
            fm.ascent = -bottom
            fm.top = -bottom
            fm.bottom = top
            fm.descent = top
        }
        return rect.right
    }

    override fun draw(
            canvas: Canvas, text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
    ) {
        val fm = paint.fontMetricsInt
        val transY: Int = y + (fm.descent + fm.ascent - pillDrawable.bounds.bottom) / 2
        val blurFraction = spoilerBlurFraction(text, start, end)

        // While hidden, render the mention as its blurred plaintext name so it blends with the
        // surrounding blurred text instead of standing out as a chip. Crossfade into the chip as it reveals.
        if (blurFraction > 0f) {
            drawSpoilerName(canvas, x, y, paint, blurFraction)
            if (blurFraction >= 1f) return
        }

        canvas.save()
        canvas.translate(x, transY.toFloat())

        val rect = Rect()
        canvas.getClipBounds(rect)
        val maxWidth = rect.right
        if (pillDrawable.bounds.width() > maxWidth) {
            pillDrawable.setBounds(0, 0, maxWidth, pillDrawable.intrinsicHeight)
            pillDrawable.ellipsize = TextUtils.TruncateAt.END
        }

        pillDrawable.alpha = ((1f - blurFraction) * 255).toInt()
        pillDrawable.draw(canvas)
        pillDrawable.alpha = 255
        emojiLabel?.let { drawEmojiLabel(canvas, it, blurFraction) }
        canvas.restore()
    }

    private fun drawEmojiLabel(canvas: Canvas, label: CharSequence, blurFraction: Float) {
        val paint = emojiLabelPaint ?: return
        val iconWidth = if (pillDrawable.chipIcon != null && pillDrawable.isChipIconVisible) {
            pillDrawable.iconStartPadding + pillDrawable.chipIconSize + pillDrawable.iconEndPadding
        } else {
            0f
        }
        val textStartX = pillDrawable.chipStartPadding + iconWidth + pillDrawable.textStartPadding
        val available =
                (pillDrawable.bounds.width() - textStartX - pillDrawable.textEndPadding - pillDrawable.chipEndPadding).toInt()
        if (available <= 0) return
        var layout = emojiLabelLayout
        if (layout == null || emojiLabelLayoutWidth != available) {
            val toDraw = if (ceil(Layout.getDesiredWidth(label, paint)).toInt() > available) {
                TextUtils.ellipsize(label, paint, available.toFloat(), TextUtils.TruncateAt.END)
            } else {
                label
            }
            layout = StaticLayout(toDraw, paint, available, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false)
            emojiLabelLayout = layout
            emojiLabelLayoutWidth = available
        }
        paint.alpha = ((1f - blurFraction) * 255).toInt()
        canvas.save()
        canvas.translate(textStartX, (pillDrawable.bounds.height() - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
        paint.alpha = 255
    }

    private fun spoilerBlurFraction(text: CharSequence, start: Int, end: Int): Float {
        val spanned = text as? Spanned ?: return 0f
        return spanned.getSpans(start, end, SpoilerSpan::class.java).maxOfOrNull { it.blurFraction } ?: 0f
    }

    private fun isInsideHiddenSpoiler(text: CharSequence, start: Int, end: Int): Boolean {
        val spanned = text as? Spanned ?: return false
        return spanned.getSpans(start, end, SpoilerSpan::class.java).any { !it.isRevealed }
    }

    private fun drawSpoilerName(canvas: Canvas, x: Float, baseline: Int, paint: Paint, blurFraction: Float) {
        spoilerTextPaint.set(paint)
        // Performance mode: skip the (software-layer) BlurMaskFilter; the tint colour alone renders the
        // name as a flat hidden block.
        spoilerTextPaint.maskFilter = if (PerformanceMode.enabled) {
            null
        } else {
            BlurMaskFilter((paint.textSize * SPOILER_BLUR_RATIO * blurFraction).coerceAtLeast(0.1f), BlurMaskFilter.Blur.NORMAL)
        }
        spoilerTextPaint.color = ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_spoiler_background_color)
        spoilerTextPaint.alpha = (blurFraction * 255).toInt()
        canvas.drawText(matrixItem.getBestName(), x, baseline.toFloat(), spoilerTextPaint)
    }

    internal fun updateAvatarDrawable(drawable: Drawable?) {
        pillDrawable.chipIcon = drawable
        tv?.get()?.invalidate()
    }

    // Private methods *****************************************************************************

    private fun createChipDrawable(): ChipDrawable {
        val textPadding = context.resources.getDimension(im.vector.lib.ui.styles.R.dimen.pill_text_padding)
        val icon = when {
            matrixItem is MatrixItem.RoomAliasItem && matrixItem.avatarUrl.isNullOrEmpty() &&
                    matrixItem.displayName == context.getString(CommonStrings.pill_message_in_room, matrixItem.id) -> {
                AppCompatResources.getDrawable(context, R.drawable.ic_permalink_round)
            }
            matrixItem is MatrixItem.RoomItem && matrixItem.avatarUrl.isNullOrEmpty() && (
                    matrixItem.displayName == context.getString(CommonStrings.pill_message_in_unknown_room) ||
                            matrixItem.displayName == context.getString(CommonStrings.pill_message_unknown_room_or_space) ||
                            matrixItem.displayName == context.getString(CommonStrings.pill_message_from_unknown_user)
                    ) -> {
                AppCompatResources.getDrawable(context, R.drawable.ic_permalink_round)
            }
            matrixItem is MatrixItem.UserItem && matrixItem.avatarUrl.isNullOrEmpty() && matrixItem.displayName?.isMatrixId().orTrue() -> {
                AppCompatResources.getDrawable(context, R.drawable.ic_user_round)
            }
            else -> {
                try {
                    avatarRenderer.getCachedDrawable(glideRequests, matrixItem)
                } catch (exception: Exception) {
                    avatarRenderer.getPlaceholderDrawable(matrixItem)
                }
            }
        }

        val name = matrixItem.getBestName()
        val spanified = name.withEmojis()
        val needsManualLabel = (spanified as? Spanned)
                ?.getSpans(0, spanified.length, TwemojiSpan::class.java)?.isNotEmpty() == true

        return ChipDrawable.createFromResource(context, R.xml.pill_view).apply {
            text = if (needsManualLabel) "" else name
            textEndPadding = textPadding
            textStartPadding = textPadding
            setChipMinHeightResource(im.vector.lib.ui.styles.R.dimen.pill_min_height)
            setChipIconSizeResource(im.vector.lib.ui.styles.R.dimen.pill_avatar_size)
            chipIcon = icon
            if (matrixItem is MatrixItem.EveryoneInRoomItem) {
                chipBackgroundColor = ColorStateList.valueOf(ThemeUtils.getColor(context, com.google.android.material.R.attr.colorError))
                // setTextColor API does not exist right now for ChipDrawable, use textAppearance
                setTextAppearanceResource(im.vector.lib.ui.styles.R.style.TextAppearance_Vector_Body_OnError)
            }
            if (needsManualLabel) {
                emojiLabel = spanified
                val paint = createChipLabelPaint(context).also { emojiLabelPaint = it }
                // With empty chip text, intrinsicWidth is the base (paddings + avatar) width; widen
                // the bounds by the label so the pill occupies the same space the chip text would.
                val labelWidth = ceil(Layout.getDesiredWidth(spanified, paint)).toInt()
                setBounds(0, 0, intrinsicWidth + labelWidth, intrinsicHeight)
            } else {
                setBounds(0, 0, intrinsicWidth, intrinsicHeight)
            }
        }
    }

    companion object {
        // Keep in sync with SpoilerSpan's text blur so a hidden mention matches the surrounding text.
        private const val SPOILER_BLUR_RATIO = 0.4f
    }
}

// Approximate a Material chip's label style (chip text-appearance + theme text colour), for drawing
// a chip label manually — ChipDrawable's internal TextPaint isn't accessible.
internal fun createChipLabelPaint(context: Context): TextPaint {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    val ta = context.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.textAppearanceBody2))
    val styleRes = ta.getResourceId(0, 0)
    ta.recycle()
    if (styleRes != 0) {
        TextAppearanceSpan(context, styleRes).updateDrawState(paint)
    } else {
        paint.textSize = 14 * context.resources.displayMetrics.scaledDensity
    }
    paint.color = ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_primary)
    return paint
}

// A pill's display name (with emoji) as backing text gets split by the layout at emoji-cluster
// boundaries, so the chip is drawn for one run and the emoji rendered as plain glyphs for the next.
// Back the span with a single object-replacement char instead - it can't be split, and the chip
// draws the real name from matrixItem.
const val PILL_PLACEHOLDER = "￼"

fun Spannable.setPillSpan(span: PillImageSpan, start: Int, end: Int) {
    if (this is Editable && end > start) {
        replace(start, end, PILL_PLACEHOLDER)
        setSpan(span, start, start + PILL_PLACEHOLDER.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    } else {
        setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

// Inverse of setPillSpan's collapse: restore each pill's display name under its span so the sent
// body carries real text (e.g. "@room") instead of the bare placeholder char, while keeping the
// span in place so user mentions are still turned into permalinks on send.
fun CharSequence.expandPillSpans(): CharSequence {
    if (this !is Spanned) return this
    val spans = getSpans(0, length, PillImageSpan::class.java)
    if (spans.isEmpty()) return this
    val builder = SpannableStringBuilder(this)
    spans.sortedByDescending { builder.getSpanStart(it) }.forEach { span ->
        val start = builder.getSpanStart(span)
        val end = builder.getSpanEnd(span)
        if (start in 0 until end) {
            val name = span.matrixItem.getBestName()
            builder.replace(start, end, name)
            builder.setSpan(span, start, start + name.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    return builder
}

/**
 * Loads the avatars for any mention pills in this TextView's text (e.g. a room-list preview), so they show
 * the real avatar instead of the bare placeholder icon. Mirrors [bindEmoteImageSpans]; call once per bind.
 */
fun TextView.bindPillImageSpans() {
    val spanned = text as? Spanned ?: return
    spanned.getSpans(0, spanned.length, PillImageSpan::class.java).forEach { it.bind(this) }
}

/**
 * Glide target to handle avatar retrieval into [PillImageSpan].
 */
private class PillImageSpanTarget(pillImageSpan: PillImageSpan) : SimpleTarget<Drawable>() {

    private val pillImageSpan = WeakReference(pillImageSpan)

    override fun onResourceReady(drawable: Drawable, transition: Transition<in Drawable>?) {
        updateWith(drawable)
    }

    override fun onLoadCleared(placeholder: Drawable?) {
        updateWith(placeholder)
    }

    private fun updateWith(drawable: Drawable?) {
        pillImageSpan.get()?.apply {
            updateAvatarDrawable(drawable)
        }
    }
}
