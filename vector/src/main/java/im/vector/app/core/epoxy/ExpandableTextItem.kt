/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.epoxy

import android.content.Context
import android.text.SpannableString
import android.text.TextUtils
import android.text.method.MovementMethod
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.core.utils.setReadOnlySelectable
import im.vector.app.features.html.bindEmoteImageSpans
import im.vector.app.features.html.bindPillImageSpans
import im.vector.lib.strings.CommonStrings

@EpoxyModelClass
abstract class ExpandableTextItem : VectorEpoxyModel<ExpandableTextItem.Holder>(R.layout.item_expandable_textview) {

    @EpoxyAttribute
    lateinit var content: CharSequence

    @EpoxyAttribute
    var maxLines: Int = 3

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var movementMethod: MovementMethod? = null

    // Read at bind time, not captured when the model is built: a rebuild enqueued before the user
    // expanded still carries the old value, and binding it collapses the view for a frame.
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var expandedProvider: (() -> Boolean)? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var onExpandedChange: ((Boolean) -> Unit)? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var renderRichContent: ((LinearLayout, MovementMethod?) -> Unit)? = null

    private var isExpanded = false

    override fun bind(holder: Holder) {
        super.bind(holder)
        setBottomPadding(holder, 16)
        // Order matters: setTextIsSelectable() re-creates the editor and resets the movement method, so make
        // the view selectable and attach the link movement method before setting the span-bearing text.
        holder.content.setReadOnlySelectable(true)
        holder.content.movementMethod = movementMethod
        isExpanded = expandedProvider?.invoke() == true
        // Apply the final collapsed/expanded state before the text is laid out, so a reused holder never
        // flashes the full height before settling — that flash was the flicker on open.
        applyMaxLines(holder.content)
        // TextView mutates selection spans, but Epoxy hashes the model content. Copy the Spannable
        // while retaining span objects so asynchronous emote updates still reach the view.
        holder.content.text = SpannableString(content)
        holder.content.bindEmoteImageSpans()
        holder.content.bindPillImageSpans()
        holder.richContent.removeAllViews()
        renderRichContent?.invoke(holder.richContent, movementMethod)
        updateContentVisibility(holder)

        // Manual pre-draw listener (not doOnPreDraw) so the frame can be canceled when the toggle's
        // visibility changes — otherwise the first frame draws at the wrong height and then jumps.
        holder.content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                holder.content.viewTreeObserver.removeOnPreDrawListener(this)
                val needsToggle = if (renderRichContent != null) {
                    holder.richContent.uncappedHeight > collapsedRichHeight(holder)
                } else {
                    holder.content.fullLineCount() > maxLines
                }
                val changed = holder.toggle.isVisible != needsToggle || setBottomPadding(holder, if (needsToggle) 0 else 16)
                if (needsToggle) {
                    updateArrow(holder)
                    holder.toggle.setOnClickListener {
                        isExpanded = !isExpanded
                        onExpandedChange?.invoke(isExpanded)
                        // Set maxLines directly rather than animating it: animating maxLines on a selectable
                        // (DynamicLayout) TextView crashes in getLineTop(-1) when a re-measure races the animation,
                        // which rapid link taps rebinding the screen readily trigger.
                        applyMaxLines(holder.content)
                        updateContentVisibility(holder)
                        updateArrow(holder)
                    }
                } else {
                    // A recycled holder still carries the previous model's toggle, which would drive
                    // that model's onExpandedChange from this item.
                    holder.toggle.setOnClickListener(null)
                }
                holder.toggle.isVisible = needsToggle
                return !changed
            }
        })
    }

    private fun applyMaxLines(textView: TextView) {
        textView.maxLines = if (isExpanded) Integer.MAX_VALUE else maxLines
        textView.ellipsize = if (isExpanded) null else TextUtils.TruncateAt.END
    }

    private fun updateContentVisibility(holder: Holder) {
        val showRich = renderRichContent != null
        holder.content.isVisible = !showRich
        holder.richContent.isVisible = showRich
        holder.richContent.heightLimitPx = if (showRich && !isExpanded) {
            collapsedRichHeight(holder)
        } else {
            Int.MAX_VALUE
        }
    }

    private fun collapsedRichHeight(holder: Holder): Int =
            (maxLines * holder.content.paint.fontSpacing).toInt() + DimensionConverter(holder.view.resources).dpToPx(32)

    private fun updateArrow(holder: Holder) {
        val icon = if (isExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
        holder.arrow.setImageDrawable(AppCompatResources.getDrawable(holder.view.context, icon))
        holder.toggle.contentDescription = holder.view.context.getString(
                if (isExpanded) CommonStrings.merged_events_collapse else CommonStrings.merged_events_expand
        )
    }

    private fun setBottomPadding(holder: Holder, paddingDp: Int): Boolean {
        val bottomPadding = DimensionConverter(holder.view.resources).dpToPx(paddingDp)
        if (holder.view.paddingBottom == bottomPadding) return false
        holder.view.setPadding(
                holder.view.paddingLeft,
                holder.view.paddingTop,
                holder.view.paddingRight,
                bottomPadding
        )
        return true
    }

    // Measuring off-view avoids a full-height pass on the live selectable TextView.
    private fun TextView.fullLineCount(): Int {
        val available = width - compoundPaddingLeft - compoundPaddingRight
        if (available <= 0) return 0
        val mult = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) lineSpacingMultiplier else 1f
        val extra = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) lineSpacingExtra else 0f
        val includePad = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) includeFontPadding else true
        @Suppress("DEPRECATION")
        return android.text.StaticLayout(
                text, paint, available, android.text.Layout.Alignment.ALIGN_NORMAL, mult, extra, includePad
        ).lineCount
    }

    class Holder : VectorEpoxyHolder() {
        val content by bind<TextView>(R.id.expandableContent)
        val richContent by bind<CappedLinearLayout>(R.id.expandableRichContent)
        val toggle by bind<View>(R.id.expandableToggle)
        val arrow by bind<ImageView>(R.id.expandableArrow)
    }
}

class CappedLinearLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    var uncappedHeight: Int = 0
        private set

    var heightLimitPx: Int = Int.MAX_VALUE
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        uncappedHeight = measuredHeight
        if (measuredHeight > heightLimitPx) setMeasuredDimension(measuredWidth, heightLimitPx)
    }
}
