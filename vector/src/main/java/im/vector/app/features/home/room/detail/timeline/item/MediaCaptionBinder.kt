/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.text.Spanned
import android.text.method.MovementMethod
import android.view.View.OnLongClickListener
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.text.PrecomputedTextCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import im.vector.app.core.epoxy.onLongClickIgnoringLinks
import im.vector.lib.core.utils.epoxy.charsequence.EpoxyCharSequence
import io.noties.markwon.MarkwonPlugin
import org.matrix.android.sdk.api.extensions.orFalse

internal object MediaCaptionBinder {

    fun bind(
            view: AppCompatTextView,
            caption: EpoxyCharSequence?,
            bindingOptions: BindingOptions?,
            movementMethod: MovementMethod?,
            itemLongClickListener: OnLongClickListener?,
            markwonPlugins: List<MarkwonPlugin>? = null,
            useBigFont: Boolean = false,
    ) {
        val text = caption?.charSequence
        if (text.isNullOrEmpty()) {
            view.isVisible = false
            view.setTextFuture(null)
            view.text = null
            return
        }
        view.isVisible = true
        // Same sizes as MessageTextItem: emoji/emote-only captions render large like text messages.
        view.textSize = if (useBigFont) 44F else 15.5F
        view.movementMethod = movementMethod
        view.setOnClickListener {}
        view.onLongClickIgnoringLinks(itemLongClickListener)
        // Run the Markwon plugins around the text set like MessageTextItem does, so inline custom
        // emoticons and images actually load (EmoteImageSpan/AsyncDrawable stay on their placeholder
        // otherwise). Spans like these are MetricAffectingSpans, so such captions never take the
        // text-future branch and afterSetText always sees the real spanned text.
        (text as? Spanned)?.let { spanned -> markwonPlugins?.forEach { it.beforeSetText(view, spanned) } }
        if (bindingOptions?.canUseTextFuture.orFalse()) {
            val textFuture = PrecomputedTextCompat.getTextFuture(text, TextViewCompat.getTextMetricsParams(view), null)
            view.setTextFuture(textFuture)
        } else {
            view.setTextFuture(null)
            view.text = text
        }
        markwonPlugins?.forEach { it.afterSetText(view) }
    }
}
