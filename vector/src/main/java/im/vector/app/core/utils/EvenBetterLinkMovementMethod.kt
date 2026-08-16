/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.utils

import android.os.Build
import android.text.Selection
import android.text.Spannable
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.MotionEvent
import android.widget.TextView
import im.vector.app.features.html.SpoilerSpan
import me.saket.bettermovementmethod.BetterLinkMovementMethod
import me.saket.bettermovementmethod.R

class EvenBetterLinkMovementMethod(private val onLinkClickListener: OnLinkClickListener? = null) : BetterLinkMovementMethod() {

    interface OnLinkClickListener {
        /**
         * @param textView The TextView on which a click was registered.
         * @param span The ClickableSpan which is clicked on.
         * @param url The clicked URL.
         * @param actualText The original text which is spanned. Can be used to compare actualText and target url to prevent misleading urls.
         * @return true if this click was handled, false to let Android handle the URL.
         */
        fun onLinkClicked(textView: TextView, span: ClickableSpan, url: String, actualText: String): Boolean
    }

    // Set once a long press has been handled by the app. The library keeps re-highlighting the url on
    // every MOVE for the rest of the gesture — the finger is still down — which is what left the link
    // tinted after release.
    private var highlightSuppressed = false

    // While a spoiler is hidden, any tap inside it (including on mentions or links it contains)
    // should reveal the spoiler rather than activate the inner span. Once revealed, prefer the
    // inner span so mentions and links stay clickable.
    override fun findClickableSpanUnderTouch(textView: TextView, spannable: Spannable, event: MotionEvent): ClickableSpan? {
        val touched = super.findClickableSpanUnderTouch(textView, spannable, event) ?: return null
        val offset = touchOffset(textView, event)
        val spans = if (offset in 0..spannable.length) {
            spannable.getSpans(offset, offset, ClickableSpan::class.java)
        } else {
            emptyArray()
        }
        spans.firstOrNull { it is SpoilerSpan && !it.isRevealed }?.let { return it }
        return spans.firstOrNull { it !is SpoilerSpan } ?: touched
    }

    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) highlightSuppressed = false
        return super.onTouchEvent(widget, buffer, event)
    }

    // Don't flash the link-highlight (accent) when toggling a spoiler.
    override fun highlightUrl(textView: TextView, clickableSpan: ClickableSpan, text: Spannable) {
        if (highlightSuppressed) return
        if (clickableSpan is SpoilerSpan) return
        // super calls TextView.getHighlightColor, which only exists from API 16 — skip the
        // (purely cosmetic) tap flash below that instead of crashing.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) return
        super.highlightUrl(textView, clickableSpan, text)
    }

    private fun touchOffset(textView: TextView, event: MotionEvent): Int {
        val layout = textView.layout ?: return -1
        val x = event.x.toInt() - textView.totalPaddingLeft + textView.scrollX
        val y = event.y.toInt() - textView.totalPaddingTop + textView.scrollY
        val line = layout.getLineForVertical(y)
        return layout.getOffsetForHorizontal(line, x.toFloat())
    }

    // The library only drops its highlight from its own touch handling; a long click answered by the
    // app never gets there, leaving the url tinted until the next touch. Its own removal is a no-op
    // once its private "highlighted" flag has been reset, so strip the span it parked on the view too.
    fun clearUrlHighlight(textView: TextView) {
        highlightSuppressed = true
        removeUrlHighlightColor(textView)
        val text = textView.text as? Spannable ?: return
        (textView.getTag(R.id.bettermovementmethod_highlight_background_span) as? BackgroundColorSpan)
                ?.let { text.removeSpan(it) }
        Selection.removeSelection(text)
        textView.invalidate()
    }

    override fun dispatchUrlClick(textView: TextView, clickableSpan: ClickableSpan) {
        val spanned = textView.text as Spanned
        val actualText = textView.text.subSequence(spanned.getSpanStart(clickableSpan), spanned.getSpanEnd(clickableSpan)).toString()
        val url = (clickableSpan as? URLSpan)?.url ?: actualText

        if (onLinkClickListener == null || !onLinkClickListener.onLinkClicked(textView, clickableSpan, url, actualText)) {
            // Let Android handle this long click as a short-click.
            clickableSpan.onClick(textView)
        }
    }
}
