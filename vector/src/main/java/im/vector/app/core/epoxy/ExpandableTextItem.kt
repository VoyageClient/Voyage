/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.epoxy

import android.text.TextUtils
import android.text.method.MovementMethod
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.extensions.hasClickableSpanAt
import im.vector.app.core.utils.setReadOnlySelectable
import im.vector.app.features.html.bindEmoteImageSpans
import im.vector.lib.strings.CommonStrings

@EpoxyModelClass
abstract class ExpandableTextItem : VectorEpoxyModel<ExpandableTextItem.Holder>(R.layout.item_expandable_textview) {

    @EpoxyAttribute
    lateinit var content: CharSequence

    @EpoxyAttribute
    var maxLines: Int = 3

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var movementMethod: MovementMethod? = null

    // Persisted by the caller so the expanded state survives model rebuilds (app resume, room-state reloads).
    @EpoxyAttribute
    var expanded: Boolean = false

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var onExpandedChange: ((Boolean) -> Unit)? = null

    private var isExpanded = false

    // The content view is selectable, so a tap on a link runs the link movement method AND the view's
    // own performClick (the expand/collapse toggle) — both fire. Record whether the last tap belonged to
    // the text (it hit a link, or it dismissed a selection) so the toggle can bow out.
    private var lastTapHitText = false

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun bind(holder: Holder) {
        super.bind(holder)
        // Order matters: setTextIsSelectable() re-creates the editor and resets the movement method, so make
        // the view selectable and attach the link movement method BEFORE setting the (span-bearing) text —
        // otherwise link taps aren't caught and fall through to the expand/collapse toggle below.
        holder.content.setReadOnlySelectable(true)
        // Read-only text: keep selection (copy) but never raise the soft keyboard when it takes focus
        // (e.g. after tapping a link and returning to the screen).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            holder.content.showSoftInputOnFocus = false
        }
        holder.content.movementMethod = movementMethod
        isExpanded = expanded
        // Apply the final collapsed/expanded state before the text is laid out, so a reused holder never
        // flashes the full height before settling — that flash was the flicker on open.
        applyMaxLines(holder.content)
        holder.content.text = content
        holder.content.bindEmoteImageSpans()
        holder.content.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val textView = v as TextView
                // Read before the view handles the tap, which is what clears an existing selection.
                lastTapHitText = textView.hasClickableSpanAt(event) || textView.hasSelection()
            }
            false
        }

        // Manual pre-draw listener (not doOnPreDraw) so the frame can be canceled when the arrow's
        // visibility changes — otherwise the first frame draws at the wrong height and then jumps.
        holder.content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                holder.content.viewTreeObserver.removeOnPreDrawListener(this)
                // Measure the full line count off-view (a selectable TextView doesn't reliably report ellipsis,
                // and reading it off the live view would need a full-height pass — the flicker we're avoiding).
                val fullLines = holder.content.fullLineCount()
                val needsArrow = fullLines > maxLines
                val changed = holder.arrow.isVisible != needsArrow
                if (needsArrow) {
                    updateArrow(holder)
                    val toggle = View.OnClickListener {
                        if (lastTapHitText) {
                            lastTapHitText = false
                            return@OnClickListener
                        }
                        isExpanded = !isExpanded
                        onExpandedChange?.invoke(isExpanded)
                        // Set maxLines directly rather than animating it: animating maxLines on a selectable
                        // (DynamicLayout) TextView crashes in getLineTop(-1) when a re-measure races the animation,
                        // which rapid link taps rebinding the screen readily trigger.
                        applyMaxLines(holder.content)
                        updateArrow(holder)
                    }
                    holder.view.setOnClickListener(toggle)
                    // The selectable text view consumes taps, so it needs the toggle too
                    holder.content.setOnClickListener(toggle)
                } else {
                    // A recycled holder still carries the previous model's toggle, which would drive
                    // that model's onExpandedChange from this item.
                    holder.view.setOnClickListener(null)
                    holder.content.setOnClickListener(null)
                }
                holder.arrow.isVisible = needsArrow
                return !changed
            }
        })
    }

    private fun applyMaxLines(textView: TextView) {
        textView.maxLines = if (isExpanded) Integer.MAX_VALUE else maxLines
        textView.ellipsize = if (isExpanded) null else TextUtils.TruncateAt.END
    }

    private fun updateArrow(holder: Holder) {
        holder.arrow.setImageResource(if (isExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
        holder.arrow.contentDescription = holder.view.context.getString(
                if (isExpanded) CommonStrings.merged_events_collapse else CommonStrings.merged_events_expand
        )
    }

    // Full line count the text would occupy at the current width, independent of the view's own maxLines /
    // selectable state — so expandability is detected without flashing the whole topic on screen.
    private fun TextView.fullLineCount(): Int {
        val available = width - compoundPaddingLeft - compoundPaddingRight
        if (available <= 0) return 0
        val sdk = android.os.Build.VERSION.SDK_INT
        val mult = if (sdk >= android.os.Build.VERSION_CODES.JELLY_BEAN) lineSpacingMultiplier else 1f
        val extra = if (sdk >= android.os.Build.VERSION_CODES.JELLY_BEAN) lineSpacingExtra else 0f
        val includePad = if (sdk >= android.os.Build.VERSION_CODES.JELLY_BEAN) includeFontPadding else true
        @Suppress("DEPRECATION")
        return android.text.StaticLayout(
                text, paint, available, android.text.Layout.Alignment.ALIGN_NORMAL, mult, extra, includePad
        ).lineCount
    }

    class Holder : VectorEpoxyHolder() {
        val content by bind<TextView>(R.id.expandableContent)
        val arrow by bind<ImageView>(R.id.expandableArrow)
    }
}
