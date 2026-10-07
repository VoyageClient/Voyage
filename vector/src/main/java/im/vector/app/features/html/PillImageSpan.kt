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
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.Editable
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import android.text.style.TextAppearanceSpan
import android.widget.TextView
import androidx.annotation.UiThread
import androidx.appcompat.content.res.AppCompatResources
import com.amulyakhare.textdrawable.TextDrawable
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.material.chip.ChipDrawable
import im.vector.app.R
import im.vector.app.core.extensions.isMatrixId
import im.vector.app.core.glide.GlideRequests
import im.vector.app.core.glide.ShapeClipDrawable
import im.vector.app.core.glide.restartAnimation
import im.vector.app.core.ui.PerformanceMode
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.emoji.TwemojiSpan
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.settings.AvatarShape
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.core.utils.epoxy.charsequence.ContentHashedSpan
import im.vector.lib.core.utils.text.neutralizeDirectionOverrides
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.extensions.orTrue
import org.matrix.android.sdk.api.session.room.send.MatrixItemSpan
import org.matrix.android.sdk.api.util.MatrixItem
import java.lang.ref.WeakReference
import java.text.BreakIterator
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
        matrixItem: MatrixItem,
        // What [expandPillSpans] writes into the outgoing body, and the link text of the generated
        // permalink: the composer passes the un-overridden name, since a local display-name override
        // means nothing to the rest of the room.
        override val bodyText: String? = null,
        // Only set where the pill has no room to draw the name and avatar from (see [PillItemResolver]).
        private val itemResolver: PillItemResolver? = null,
) : ReplacementSpan(), MatrixItemSpan, ContentHashedSpan {

    override var matrixItem: MatrixItem = matrixItem
        private set

    override fun contentHash() = matrixItem.hashCode()

    // ChipDrawable draws its label straight to the canvas, so Twemoji sprite spans can't render
    // through it. When the name spanifies to sprites, the chip gets empty text (bounds widened by
    // the label width) and [drawEmojiLabel] draws the spanned label at the chip's text position.
    private var fullEmojiLabel: CharSequence? = null
    private var emojiLabel: CharSequence? = null
    private var emojiLabelPaint: TextPaint? = null
    private var emojiLabelLayout: StaticLayout? = null
    private var emojiLabelLayoutWidth = -1
    private var textStyle: LabelStyle? = null

    // Display-only; the outgoing body built by [expandPillSpans] uses [bodyText].
    private var displayName = matrixItem.getBestName().neutralizeDirectionOverrides()

    // What a copy of the pill puts on the clipboard, and what backspacing into it restores: an id the
    // composer pills again. "@room" and a room known only by its id have none, so they use the name.
    val copyText: String
        get() = when (matrixItem) {
            is MatrixItem.UserItem,
            is MatrixItem.RoomAliasItem -> matrixItem.id
            else -> bodyText ?: matrixItem.getBestName()
        }

    // Set by createChipDrawable when it picks a generic icon over an avatar; the async avatar render is
    // then skipped, so an unresolved permalink keeps the link icon instead of a letter placeholder.
    private var useGenericIcon = false

    // Set when the avatar came straight out of Glide's cache: re-rendering it asynchronously would
    // blink the pill back through a placeholder for no gain.
    private var hasCachedAvatar = false

    private var itemResolutionRequested = false

    // The unwrapped drawable the chip icon is built from, so the animation can be driven through the
    // paths that know how to rewind it. Declared above [pillDrawable], whose initialiser sets it.
    private var rawIcon: Drawable? = null

    private var pillDrawable = createChipDrawable()
    private var naturalWidth = pillDrawable.bounds.width()
    private var maxWidth: Int? = null
    private var fittedCap = -1
    private val target = PillImageSpanTarget(this)
    private var tv: WeakReference<TextView>? = null
    private val spoilerTextPaint = TextPaint()

    @UiThread
    fun bind(textView: TextView) {
        tv = WeakReference(textView)
        // The chip is drawn by hand from [draw], so nothing owns it; taking its callback is what lets an
        // animated avatar's frames reach the TextView.
        pillDrawable.callback = chipCallback
        // Picks the animation back up when a recycled row rebinds a pill that was parked below.
        if (avatarRenderer.animatesAvatars()) rawIcon?.restartAnimation()
        resolveItemIfNeeded()
        if (useGenericIcon || hasCachedAvatar) return
        avatarRenderer.render(glideRequests, matrixItem, target, forceCircle = true)
    }

    fun limitWidthTo(width: Int): Boolean {
        val newWidth = width.takeIf { it > 0 }
        if (maxWidth == newWidth) return false
        maxWidth = newWidth
        return true
    }

    private fun resolveItemIfNeeded() {
        val resolver = itemResolver ?: return
        if (itemResolutionRequested || !matrixItem.avatarUrl.isNullOrEmpty()) return
        itemResolutionRequested = true
        resolver.resolve(matrixItem) { applyResolvedItem(it) }
    }

    // Redraw the pill now the server has told us what it points at.
    private fun applyResolvedItem(item: MatrixItem) {
        matrixItem = item
        displayName = item.getBestName().neutralizeDirectionOverrides()
        fullEmojiLabel = null
        emojiLabel = null
        emojiLabelPaint = null
        emojiLabelLayout = null
        emojiLabelLayoutWidth = -1
        textStyle = null
        useGenericIcon = false
        hasCachedAvatar = false
        rawIcon = null
        pillDrawable = createChipDrawable()
        naturalWidth = pillDrawable.bounds.width()
        fittedCap = -1
        pillDrawable.callback = chipCallback
        val textView = tv?.get() ?: return
        if (!useGenericIcon && !hasCachedAvatar) avatarRenderer.render(glideRequests, item, target, forceCircle = true)
        textView.repaintSpan(this)
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
            return ceil(paint.measureText(displayName)).toInt()
        }
        applyTextStyle(textStyleOf(paint))
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
        return fittedWidth()
    }

    private fun fittedWidth(): Int {
        val res = context.resources
        // The screen bound only matters at very large font scales, where the sp cap outgrows a line.
        val defaultCap = minOf(
                res.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.pill_max_width),
                (res.displayMetrics.widthPixels * MAX_SCREEN_FRACTION).toInt()
        )
        fitTo(maxWidth?.let { minOf(it, defaultCap) } ?: defaultCap)
        return pillDrawable.bounds.width()
    }

    // Not the chip's own ellipsize: it keeps the chip at the full cap, leaving a gap after the "…".
    private fun fitTo(cap: Int) {
        if (cap == fittedCap) return
        fittedCap = cap
        val chip = pillDrawable
        val callback = chip.callback
        // Changing the chip's text invalidates it, which would repaint the span in the middle of a layout.
        chip.callback = null
        val manual = fullEmojiLabel != null
        val label: CharSequence = fullEmojiLabel ?: displayName
        val manualChromeWidth = if (manual) chip.intrinsicWidth else 0
        fun widthOf(text: CharSequence) = if (manual) {
            manualChromeWidth + ceil(Layout.getDesiredWidth(text, emojiLabelPaint!!)).toInt()
        } else {
            chip.text = text
            chip.intrinsicWidth
        }

        var fitted: CharSequence = label
        if (naturalWidth > cap) {
            val boundaries = graphemeBoundaries(label)
            fun candidate(end: Int): CharSequence =
                    SpannableStringBuilder(label.subSequence(0, end).trimEnd()).append(ELLIPSIS)
            var low = 0
            var high = boundaries.size - 1
            while (low < high) {
                val mid = (low + high + 1) / 2
                if (widthOf(candidate(boundaries[mid])) <= cap) low = mid else high = mid - 1
            }
            fitted = candidate(boundaries[low])
        }
        val width = if (fitted === label) naturalWidth else widthOf(fitted)
        if (manual) {
            emojiLabel = fitted
            emojiLabelLayout = null
        } else {
            chip.text = fitted
        }
        chip.ellipsize = if (width > cap) TextUtils.TruncateAt.END else null
        chip.setBounds(0, 0, minOf(width, cap), chip.intrinsicHeight)
        chip.callback = callback
    }

    // The paint handed to a replacement span already carries the surrounding bold and italic spans. They
    // combine as a typeface plus fake-bold and skew flags, so all three are copied rather than re-derived.
    private fun textStyleOf(paint: Paint): LabelStyle? {
        val typeface = paint.typeface?.takeIf { it.style != Typeface.NORMAL }
        if (typeface == null && !paint.isFakeBoldText && paint.textSkewX == 0f) return null
        return LabelStyle(typeface, paint.isFakeBoldText, paint.textSkewX)
    }

    // ChipDrawable's label paint can't be styled, so a bold or italic pill draws its label by hand.
    private fun applyTextStyle(style: LabelStyle?) {
        if (style == textStyle) return
        textStyle = style
        val chip = pillDrawable
        val callback = chip.callback
        chip.callback = null
        val label = fullEmojiLabel ?: displayName.prepareForDisplay()
        if (style == null && !hasTwemoji(label)) {
            fullEmojiLabel = null
            emojiLabel = null
            emojiLabelPaint = null
            chip.text = displayName
            chip.setBounds(0, 0, chip.intrinsicWidth, chip.intrinsicHeight)
        } else {
            val paint = createChipLabelPaint(context).apply {
                style?.let {
                    if (it.typeface != null) typeface = it.typeface
                    isFakeBoldText = it.fakeBold
                    textSkewX = it.skewX
                }
                if (matrixItem is MatrixItem.EveryoneInRoomItem) color = ThemeUtils.getColor(context, com.google.android.material.R.attr.colorOnError)
            }
            fullEmojiLabel = label
            emojiLabel = label
            emojiLabelPaint = paint
            chip.text = ""
            chip.setBounds(0, 0, chip.intrinsicWidth + ceil(Layout.getDesiredWidth(label, paint)).toInt(), chip.intrinsicHeight)
        }
        emojiLabelLayout = null
        emojiLabelLayoutWidth = -1
        naturalWidth = chip.bounds.width()
        fittedCap = -1
        chip.callback = callback
    }

    private fun hasTwemoji(label: CharSequence) =
            (label as? Spanned)?.getSpans(0, label.length, TwemojiSpan::class.java)?.isNotEmpty() == true

    private fun graphemeBoundaries(text: CharSequence): List<Int> {
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text.toString())
        val boundaries = mutableListOf<Int>()
        var boundary = iterator.first()
        while (boundary != BreakIterator.DONE) {
            boundaries.add(boundary)
            boundary = iterator.next()
        }
        return boundaries
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

        // Not sized from the clip bounds: a partial redraw clips to the dirty region, squeezing the chip to its avatar.
        fittedWidth()

        // Being painted is what says the pill is on screen; take the animation back up if it was parked
        // while off it. Plain start(), not a rewind: the pill would otherwise jump to frame 0 on a scroll.
        (rawIcon as? Animatable)?.let { if (!it.isRunning && avatarRenderer.animatesAvatars()) it.start() }
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
        canvas.drawText(displayName, x, baseline.toFloat(), spoilerTextPaint)
    }

    internal fun updateAvatarDrawable(drawable: Drawable?) {
        (rawIcon as? Animatable)?.stop()
        val icon = drawable?.let { prepareIcon(it) }
        pillDrawable.chipIcon = icon
        // The raw drawable, not the wrapper: only it has the rewind-to-first-frame paths.
        if (avatarRenderer.animatesAvatars()) rawIcon?.restartAnimation()
        tv?.get()?.repaintSpan(this)
    }

    /**
     * Glide's memory cache hands every pill for a given avatar the *same* drawable instance, and a
     * Drawable has a single Callback — so only the pill that bound last ever receives frames and the
     * rest sit frozen. A copy gets a callback of its own; it shares the frame loader (which keeps a
     * callback list), so the frames are still decoded once. Only animated icons need that: a
     * TextDrawable letter placeholder (Glide hands it back through onLoadCleared) copies as a bare
     * ShapeDrawable, which paints the avatar colour without the letter.
     */
    private fun prepareIcon(drawable: Drawable): Drawable {
        val icon = if (drawable is Animatable) drawable.constantState?.newDrawable() ?: drawable else drawable
        rawIcon = icon
        return circular(icon)
    }

    // Glide's bitmap transforms can't shape animated content, so it arrives square; mask it to the
    // circle every pill avatar is drawn as, whatever avatar shape the rest of the app uses. A pill is
    // an icon the height of a line of text, sitting in a span that repaints as a whole.
    private fun circular(drawable: Drawable): Drawable =
            if (drawable is BitmapDrawable || drawable is TextDrawable) drawable else ShapeClipDrawable(drawable, AvatarShape.CIRCLE)

    private val chipCallback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) {
            val view = tv?.get()
            if (view == null || !view.isShown) {
                // Nothing is painting this pill (a recycled row, or a cached message scrolled off): frames
                // nobody draws only take decode time from the pills that are on screen. [draw] restarts it.
                (rawIcon as? Animatable)?.stop()
                return
            }
            // Not view.invalidate(): the TextView replays its display list without re-running this span's
            // draw, so frames never reach the screen (see [repaintSpan]).
            view.repaintSpan(this@PillImageSpan)
        }

        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            tv?.get()?.postDelayed(what, `when` - SystemClock.uptimeMillis())
        }

        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            tv?.get()?.removeCallbacks(what)
        }
    }

    // Private methods *****************************************************************************

    private fun createChipDrawable(): ChipDrawable {
        val textPadding = context.resources.getDimension(im.vector.lib.ui.styles.R.dimen.pill_text_padding)
        val item = matrixItem
        val icon = when {
            item is MatrixItem.RoomAliasItem && item.avatarUrl.isNullOrEmpty() &&
                    item.displayName == context.getString(CommonStrings.pill_message_in_room, item.id) -> {
                useGenericIcon = true
                AppCompatResources.getDrawable(context, R.drawable.ic_permalink_round)
            }
            item is MatrixItem.RoomItem && item.avatarUrl.isNullOrEmpty() && (
                    item.displayName == context.getString(CommonStrings.pill_message_in_unknown_room) ||
                            item.displayName == context.getString(CommonStrings.pill_message_unknown_room_or_space) ||
                            item.displayName == context.getString(CommonStrings.pill_message_from_unknown_user)
                    ) -> {
                useGenericIcon = true
                AppCompatResources.getDrawable(context, R.drawable.ic_permalink_round)
            }
            item is MatrixItem.UserItem && item.avatarUrl.isNullOrEmpty() && item.displayName?.isMatrixId().orTrue() -> {
                useGenericIcon = true
                AppCompatResources.getDrawable(context, R.drawable.ic_user_round)
            }
            else -> {
                try {
                    prepareIcon(avatarRenderer.getCachedDrawable(glideRequests, item, forceCircle = true))
                            .also { hasCachedAvatar = true }
                } catch (exception: Exception) {
                    avatarRenderer.getPlaceholderDrawable(item, forceCircle = true)
                }
            }
        }

        val spanified = displayName.prepareForDisplay()
        val needsManualLabel = (spanified as? Spanned)
                ?.getSpans(0, spanified.length, TwemojiSpan::class.java)?.isNotEmpty() == true

        return ChipDrawable.createFromResource(context, R.xml.pill_view).apply {
            text = if (needsManualLabel) "" else displayName
            textEndPadding = textPadding
            textStartPadding = textPadding
            setChipMinHeightResource(im.vector.lib.ui.styles.R.dimen.pill_min_height)
            setChipIconSizeResource(im.vector.lib.ui.styles.R.dimen.pill_avatar_size)
            chipIcon = icon
            if (item is MatrixItem.EveryoneInRoomItem) {
                chipBackgroundColor = ColorStateList.valueOf(ThemeUtils.getColor(context, com.google.android.material.R.attr.colorError))
                // setTextColor API does not exist right now for ChipDrawable, use textAppearance
                setTextAppearanceResource(im.vector.lib.ui.styles.R.style.TextAppearance_Vector_Body_OnError)
            }
            if (needsManualLabel) {
                fullEmojiLabel = spanified
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

        private const val MAX_SCREEN_FRACTION = 0.7f
        private const val ELLIPSIS = "…"
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

private data class LabelStyle(val typeface: Typeface?, val fakeBold: Boolean, val skewX: Float)

// A pill's display name (with emoji) as backing text gets split by the layout at emoji-cluster
// boundaries, so the chip is drawn for one run and the emoji rendered as plain glyphs for the next.
// Back the span with a single object-replacement char instead - it can't be split, and the chip
// draws the real name from matrixItem.
const val PILL_PLACEHOLDER = "￼"

fun Spannable.setPillSpan(span: PillImageSpan, start: Int, end: Int) {
    if (start < 0 || end > length || end < start) return
    if (this is Editable && end > start) {
        // Replacing all of a span's text drops the span, so a <strong> around just the mention would
        // vanish; carry those styles over to the placeholder.
        val enclosed = getSpans(start, end, MetricAffectingSpan::class.java)
                .filter { it !is ReplacementSpan && getSpanStart(it) >= start && getSpanEnd(it) <= end }
                .map { it to getSpanFlags(it) }
        replace(start, end, PILL_PLACEHOLDER)
        enclosed.forEach { (style, flags) -> setSpan(style, start, start + PILL_PLACEHOLDER.length, flags) }
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
            val name = span.bodyText ?: span.matrixItem.getBestName()
            builder.replace(start, end, name)
            builder.setSpan(span, start, start + name.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    return builder
}

// Copy/cut of a composer selection: the pill's backing text is a bare placeholder char, so hand the
// clipboard each pill's [copyText] instead of an unreadable U+FFFC.
fun CharSequence.pillsToCopyText(): CharSequence {
    if (this !is Spanned) return this
    val spans = getSpans(0, length, PillImageSpan::class.java)
    if (spans.isEmpty()) return this
    val builder = SpannableStringBuilder(this)
    spans.sortedByDescending { builder.getSpanStart(it) }.forEach { span ->
        val start = builder.getSpanStart(span)
        val end = builder.getSpanEnd(span)
        if (start in 0 until end) builder.replace(start, end, span.copyText)
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
