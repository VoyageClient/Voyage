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
import androidx.core.view.isVisible
import im.vector.app.core.epoxy.onLongClickIgnoringLinksSelectingCode
import im.vector.app.core.utils.setReadOnlySelectable
import im.vector.lib.core.utils.epoxy.charsequence.EpoxyCharSequence
import io.noties.markwon.MarkwonPlugin

internal object MediaCaptionBinder {

    fun bind(
            view: AppCompatTextView,
            caption: EpoxyCharSequence?,
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
        view.setReadOnlySelectable(true)
        view.movementMethod = movementMethod
        view.setOnClickListener {}
        view.onLongClickIgnoringLinksSelectingCode(itemLongClickListener)
        // Run the Markwon plugins around the text set like MessageTextItem does, so inline custom
        // emoticons and images actually load (EmoteImageSpan/AsyncDrawable stay on their placeholder
        // otherwise).
        (text as? Spanned)?.let { spanned -> markwonPlugins?.forEach { it.beforeSetText(view, spanned) } }
        view.setTextFuture(null)
        view.text = text
        markwonPlugins?.forEach { it.afterSetText(view) }
    }
}
