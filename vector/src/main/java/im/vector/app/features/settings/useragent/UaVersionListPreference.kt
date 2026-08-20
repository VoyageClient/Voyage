/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.useragent

import android.content.Context
import android.util.AttributeSet
import androidx.preference.ListPreference
import im.vector.app.features.settings.useragent.data.UaOption

/**
 * A [ListPreference] backed by a fetched, usage-annotated option list. Options are held newest-first;
 * [UaVersionListPreferenceDialogFragment] re-sorts them by usage share when the user asks for it.
 */
class UaVersionListPreference(context: Context, attrs: AttributeSet) : ListPreference(context, attrs) {

    /** Usage share per option, parallel to entryValues; NaN where unknown (e.g. curl has no share data). */
    var optionShares: DoubleArray? = null
        private set

    // ListPreference.getSummary() runs the summary through String.format(summary, entry), which throws
    // on a literal '%' (our "12.3%" usage labels). Keep and return the summary verbatim instead.
    private var rawSummary: CharSequence? = null

    init {
        isIconSpaceReserved = true
    }

    override fun setSummary(summary: CharSequence?) {
        rawSummary = summary
        super.setSummary(summary)
    }

    override fun getSummary(): CharSequence? = rawSummary

    fun setOptions(options: List<UaOption>) {
        entryValues = options.map { it.value }.toTypedArray()
        entries = options.map { it.label }.toTypedArray()
        optionShares = DoubleArray(options.size) { options[it].share ?: Double.NaN }
    }
}
