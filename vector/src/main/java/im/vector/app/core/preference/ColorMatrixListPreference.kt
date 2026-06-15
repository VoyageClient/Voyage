/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.preference

import android.content.Context
import android.util.AttributeSet
import androidx.preference.ListPreference
import im.vector.app.R

/**
 * A [ListPreference] that displays its entries as a grid of color swatches (e.g. for the theme accent
 * picker). The swatch colors come from the `entryPreviews` array, which is parallel to `entryValues`.
 */
class ColorMatrixListPreference(context: Context, attrs: AttributeSet) : ListPreference(context, attrs) {

    val entryPreviews: Array<CharSequence>?

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.ColorMatrixListPreference)
        entryPreviews = a.getTextArray(R.styleable.ColorMatrixListPreference_entryPreviews)
        a.recycle()
        // Reserve icon space like VectorListPreference so the row aligns with the other settings entries.
        isIconSpaceReserved = true
    }
}
