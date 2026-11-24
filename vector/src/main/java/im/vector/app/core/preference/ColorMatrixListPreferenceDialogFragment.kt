/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.preference

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceDialogFragmentCompat
import im.vector.app.R

/**
 * Dialog showing the [ColorMatrixListPreference] entries as a tappable grid of color swatches.
 * Tapping a swatch selects it (inset with a border); the choice is only applied when the user taps OK.
 */
class ColorMatrixListPreferenceDialogFragment : PreferenceDialogFragmentCompat() {

    private var value: String? = null
    private var trailingRowCount = 0
    private lateinit var entries: Array<CharSequence>
    private lateinit var entryValues: Array<CharSequence>
    private lateinit var entryPreviews: Array<CharSequence>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pref = preference as ColorMatrixListPreference
        value = savedInstanceState?.getString(SAVE_STATE_VALUE) ?: pref.value
        entries = pref.entries
        entryValues = pref.entryValues
        entryPreviews = pref.entryPreviews
                ?: throw IllegalStateException("ColorMatrixListPreference requires an entryPreviews array.")
        trailingRowCount = pref.trailingRowCount
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(SAVE_STATE_VALUE, value)
    }

    override fun onCreateDialogView(context: Context): View =
            ColorMatrixPickerView(context, entries, entryValues, entryPreviews, value, trailingRowCount) { value = it }

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)
        builder.setPositiveButton(android.R.string.ok, this)
        builder.setNegativeButton(android.R.string.cancel, this)
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        val pref = preference as ColorMatrixListPreference
        val selected = value
        if (positiveResult && selected != null) {
            if (pref.callChangeListener(selected)) {
                pref.value = selected
            }
        }
    }

    companion object {
        private const val SAVE_STATE_VALUE = "ColorMatrixListPreferenceDialogFragment.value"

        fun newInstance(key: String): ColorMatrixListPreferenceDialogFragment {
            return ColorMatrixListPreferenceDialogFragment().apply {
                arguments = Bundle(1).apply { putString(ARG_KEY, key) }
            }
        }
    }
}
