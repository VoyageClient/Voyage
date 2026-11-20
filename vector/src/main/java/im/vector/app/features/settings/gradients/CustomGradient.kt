/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.gradients

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import java.util.Locale

data class CustomGradient(val label: String, val colors: List<Int>) {
    fun isValid() = LABEL.matches(label) && colors.size in 1..MAX_COLORS && colors.all { it in 0..0xFFFFFF }

    enum class Problem { EMPTY_LABEL, INVALID_LABEL, DUPLICATE_LABEL, NO_COLORS }

    companion object {
        const val MAX_COLORS = 16
        const val MAX_LABEL_LENGTH = 32
        private val LABEL = Regex("[A-Za-z0-9_-]{1,$MAX_LABEL_LENGTH}")

        fun drawable(colors: List<Int>, shape: Int): GradientDrawable {
            val opaque = colors.map { it or Color.BLACK }
            // The LinearGradient behind a gradient drawable throws with fewer than two colors.
            val drawable = if (opaque.size == 1) {
                GradientDrawable().apply { setColor(opaque.single()) }
            } else {
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, opaque.toIntArray())
            }
            return drawable.apply { this.shape = shape }
        }

        val LABEL_FILTER = InputFilter { source, start, end, _, _, _ ->
            val kept = source.subSequence(start, end).filter { it.isAllowedLabelChar() }
            if (kept.length == end - start) null else kept
        }

        private fun Char.isAllowedLabelChar() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_' || this == '-'

        fun labelKey(label: String) = label.lowercase(Locale.ROOT)

        /** [originalLabel] is the gradient being edited, so keeping its own label is not a conflict. */
        fun validate(label: String, colors: List<Int>, existing: List<CustomGradient>, originalLabel: String?): Problem? = when {
            label.isEmpty() -> Problem.EMPTY_LABEL
            !LABEL.matches(label) -> Problem.INVALID_LABEL
            existing.any { it.label != originalLabel && labelKey(it.label) == labelKey(label) } -> Problem.DUPLICATE_LABEL
            colors.isEmpty() -> Problem.NO_COLORS
            else -> null
        }
    }
}
