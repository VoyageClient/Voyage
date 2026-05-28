/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.text.method.MovementMethod
import android.view.View.OnLongClickListener
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.text.PrecomputedTextCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.epoxy.onLongClickIgnoringLinks
import im.vector.lib.core.utils.epoxy.charsequence.EpoxyCharSequence
import org.matrix.android.sdk.api.extensions.orFalse

/**
 * Shared bind logic for the optional caption TextView on media items (image/video/file/audio).
 * Wires the precomputed-text fast path, link movement, click + long-click pass-through to the
 * outer Epoxy item, and visibility toggling.
 */
internal object MediaCaptionBinder {

    fun bind(
            view: AppCompatTextView,
            caption: EpoxyCharSequence?,
            bindingOptions: BindingOptions?,
            movementMethod: MovementMethod?,
            itemClickListener: ClickListener?,
            itemLongClickListener: OnLongClickListener?,
    ) {
        val text = caption?.charSequence
        if (text.isNullOrEmpty()) {
            view.isVisible = false
            view.setTextFuture(null)
            view.text = null
            return
        }
        view.isVisible = true
        view.movementMethod = movementMethod
        view.onClick(itemClickListener)
        view.onLongClickIgnoringLinks(itemLongClickListener)
        if (bindingOptions?.canUseTextFuture.orFalse()) {
            val textFuture = PrecomputedTextCompat.getTextFuture(text, TextViewCompat.getTextMetricsParams(view), null)
            view.setTextFuture(textFuture)
        } else {
            view.setTextFuture(null)
            view.text = text
        }
    }
}
