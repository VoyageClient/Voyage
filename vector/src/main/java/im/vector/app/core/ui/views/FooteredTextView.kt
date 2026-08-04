/*
 * Copyright 2021-2024 SchildiChat and New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.PointF
import android.os.Build
import android.text.Layout
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.util.AttributeSet
import android.view.ActionMode
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.annotation.RequiresApi
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.text.getSpans
import im.vector.app.core.utils.CodeSelectionBoundsHost
import im.vector.app.core.utils.ReadOnlySelectionFocus
import im.vector.app.core.utils.SelectionFocusHost
import im.vector.app.core.utils.clampSelectionToCodeSpans
import im.vector.app.core.utils.mirrorPressedToRowRipple
import im.vector.app.core.utils.readOnlySelectionInputConnection
import im.vector.app.core.utils.releasePressedRippleOnSelection
import im.vector.app.core.utils.replaySwallowedTap
import im.vector.app.core.utils.startActionModeGuarded
import im.vector.app.features.home.room.detail.timeline.tools.applySpoilerRenderLayer
import im.vector.app.features.html.HtmlCodeSpan
import kotlin.math.ceil
import kotlin.math.min

class FooteredTextView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr), AbstractFooteredTextView, CodeSelectionBoundsHost, SelectionFocusHost {

    override val footerState: AbstractFooteredTextView.FooterState = AbstractFooteredTextView.FooterState()

    private val lastTouch = PointF()

    override val selectionFocus = ReadOnlySelectionFocus(this)

    override fun getAppCompatTextView(): AppCompatTextView = this
    override fun setMeasuredDimensionExposed(measuredWidth: Int, measuredHeight: Int) = setMeasuredDimension(measuredWidth, measuredHeight)

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        applySpoilerRenderLayer()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val mode = View.MeasureSpec.getMode(widthMeasureSpec)
        val size = View.MeasureSpec.getSize(widthMeasureSpec)

        // Stock wrap_content under-measures leading-margin (blockquote) content because
        // Layout.getDesiredWidth ignores the indent, so it re-wraps narrower than it should. Re-measure
        // wide enough for the natural longest line plus the indent before any further width math reads
        // the layout. This applies in and out of bubbles (the collapse happens in super.onMeasure).
        val leadingMargin = maxLeadingMargin()
        if (leadingMargin > 0 && mode != View.MeasureSpec.UNSPECIFIED && size > 0) {
            val target = min(ceil(Layout.getDesiredWidth(text, paint)).toInt() + leadingMargin + safetyPx(), size)
            if (measuredWidth < target) {
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(target, View.MeasureSpec.EXACTLY), heightMeasureSpec)
            }
        }

        if (footerState.footerWidth == 0 && footerState.footerHeight == 0) {
            // Non-bubble (no footer to overlay): never shrink to content (that's only for a bubble hugging
            // its content for the timestamp overlay). A match_parent body or a block-code message fills
            // the row; otherwise keep the (leading-margin-corrected) measure from above.
            val wantsFullWidth = layoutParams?.width == ViewGroup.LayoutParams.MATCH_PARENT ||
                    (footerState.fullWidthBlockCode && hasBlockCodeSpan())
            if (wantsFullWidth && mode != View.MeasureSpec.UNSPECIFIED && size > 0 && measuredWidth < size) {
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY), heightMeasureSpec)
            }
            return
        }

        val updatedMeasures = updateDimensionsWithFooter(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(updatedMeasures.first, updatedMeasures.second)
    }

    private fun hasBlockCodeSpan(): Boolean {
        val spanned = text as? Spanned ?: return false
        return spanned.getSpans<HtmlCodeSpan>(0, spanned.length).any { it.isBlock }
    }

    private fun maxLeadingMargin(): Int {
        val spanned = text as? Spanned ?: return 0
        return spanned.getSpans<LeadingMarginSpan>(0, spanned.length).maxOfOrNull { it.getLeadingMargin(true) } ?: 0
    }

    private fun safetyPx(): Int = ceil(2 * resources.displayMetrics.density).toInt()

    override fun onDraw(canvas: Canvas) {
        updateFooterOnPreDraw(canvas)
        super.onDraw(canvas)
    }

    override var codeSelectionBounds: IntRange? = null

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        codeSelectionBounds = clampSelectionToCodeSpans(codeSelectionBounds)
        releasePressedRippleOnSelection(selStart, selEnd)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        mirrorPressedToRowRipple(pressed, lastTouch.x, lastTouch.y)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        lastTouch.set(event.x, event.y)
        val wasFocused = isFocused
        selectionFocus.beforeTouch(event)
        val handled = super.onTouchEvent(event)
        replaySwallowedTap(event, wasFocused)
        selectionFocus.afterTouch(event)
        return handled
    }

    override fun performLongClick(): Boolean {
        selectionFocus.beforeLongClick()
        return super.performLongClick()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        selectionFocus.detach()
    }

    // Touch-only focus: focus search landing on a selectable view churns selection spans, and
    // every span change relayouts the whole row (see ReadOnlySelectableTextView)
    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        if (!isTextSelectable) super.addFocusables(views, direction, focusableMode)
    }

    override fun onCheckIsTextEditor(): Boolean = isTextSelectable

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
            if (isTextSelectable) readOnlySelectionInputConnection(outAttrs) else super.onCreateInputConnection(outAttrs)

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? =
            startActionModeGuarded { super.startActionMode(callback) }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
            startActionModeGuarded { super.startActionMode(callback, type) }
}
