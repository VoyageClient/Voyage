/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.tools

import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider
import org.matrix.android.sdk.api.util.MatrixItem

/**
 * Paints an emote's leading sender name like a display name: the sender's color, bold only while names
 * are colored. Re-resolved when [MatrixItemColorProvider] reports a change, so a recolor only needs the
 * view re-bound rather than the rendered text rebuilt.
 */
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
        textPaint.color = color
        applyBold(textPaint)
    }

    override fun updateMeasureState(textPaint: TextPaint) {
        resolve()
        applyBold(textPaint)
    }

    // OR the weight in rather than setting it, so the emote's surrounding italic survives either order.
    private fun applyBold(textPaint: TextPaint) {
        if (!bold) return
        val old = textPaint.typeface
        textPaint.typeface = Typeface.create(old, (old?.style ?: Typeface.NORMAL) or Typeface.BOLD)
    }
}
