/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.tools

import android.graphics.Typeface
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.widget.TextView
import im.vector.app.core.platform.ProfileColorFadeRegistry
import im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider
import org.matrix.android.sdk.api.util.MatrixItem

class SenderNameSpan(
        private val matrixItem: MatrixItem,
        private val colorProvider: MatrixItemColorProvider,
) : MetricAffectingSpan() {

    private var generation = -1L
    private var color = 0
    private var bold = false

    private fun resolve() {
        val current = colorProvider.changes.value
        if (current == generation) return
        generation = current
        color = colorProvider.getNameColor(matrixItem)
        bold = colorProvider.isNameColored()
    }

    override fun updateDrawState(textPaint: TextPaint) {
        resolve()
        textPaint.color = ProfileColorFadeRegistry.timelineColor(matrixItem.id, color) ?: color
        applyBold(textPaint)
    }

    override fun updateMeasureState(textPaint: TextPaint) {
        resolve()
        applyBold(textPaint)
    }

    fun currentColor(): Int {
        resolve()
        return color
    }

    fun fadeFrom(@androidx.annotation.ColorInt previous: Int, textView: TextView) {
        resolve()
        if (previous == color) return
        ProfileColorFadeRegistry.bindTimelineSpan(textView, matrixItem.id, color, previous, fade = true)
    }

    fun bind(textView: TextView) {
        resolve()
        val previous = renderedColors.put(matrixItem.id, color)
        val fade = colorProvider.shouldFadeProfile(matrixItem.id)
        ProfileColorFadeRegistry.bindTimelineSpan(textView, matrixItem.id, color, previous, fade)
        colorProvider.profileFadeBound(matrixItem.id)
    }

    // Preserve surrounding italics regardless of span order.
    private fun applyBold(textPaint: TextPaint) {
        if (!bold) return
        val old = textPaint.typeface
        val style = when (old?.style) {
            Typeface.ITALIC, Typeface.BOLD_ITALIC -> Typeface.BOLD_ITALIC
            else -> Typeface.BOLD
        }
        textPaint.typeface = Typeface.create(old, style)
    }

    private companion object {
        private val renderedColors = mutableMapOf<String, Int>()
    }
}

fun TextView.bindSenderNameFades() {
    (text as? Spanned)
            ?.getSpans(0, length(), SenderNameSpan::class.java)
            ?.forEach { it.bind(this) }
}
