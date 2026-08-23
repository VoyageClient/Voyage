/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.colorpicker

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider
import im.vector.lib.strings.CommonStrings
import java.util.Locale

/**
 * Full HSV picker with a hex field. Returns the chosen color through the Fragment Result API under
 * [ARG_REQUEST_KEY] as [RESULT_HEX] (#RRGGBB).
 */
class HsvColorPickerDialogFragment : DialogFragment() {

    private var color = Color.BLACK
    private var fromEditText = false
    private lateinit var picker: HsvColorPickerView
    private lateinit var newPanel: View
    private lateinit var hexEditText: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        color = savedInstanceState?.getInt(SAVE_COLOR) ?: requireArguments().getInt(ARG_COLOR, Color.BLACK)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(SAVE_COLOR, color)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        return MaterialAlertDialogBuilder(context)
                .setTitle(CommonStrings.color_picker_custom)
                .setView(buildView(context))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    setFragmentResult(
                            requireArguments().getString(ARG_REQUEST_KEY)!!,
                            bundleOf(RESULT_HEX to MatrixItemColorProvider.toHex(color))
                    )
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
    }

    private fun buildView(context: Context): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val initial = color

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }

        picker = HsvColorPickerView(context).apply {
            setColor(initial)
            setOnColorChangedListener { onColorChanged(it) }
        }
        root.addView(picker, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)))

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, dp(8))
        }
        val oldPanel = View(context).also { ViewCompat.setBackground(it, ColorSwatches.square(initial)) }
        row.addView(oldPanel, LinearLayout.LayoutParams(dp(48), dp(36)))
        row.addView(TextView(context).apply {
            text = "→"
            setPadding(dp(8), 0, dp(8), 0)
        })
        newPanel = View(context).also { ViewCompat.setBackground(it, ColorSwatches.square(initial)) }
        row.addView(newPanel, LinearLayout.LayoutParams(dp(48), dp(36)))
        row.addView(View(context), LinearLayout.LayoutParams(0, 0).apply { weight = 1f })
        row.addView(TextView(context).apply {
            text = "#"
            typeface = android.graphics.Typeface.MONOSPACE
        })
        hexEditText = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(6))
            maxLines = 1
            typeface = android.graphics.Typeface.MONOSPACE
            setText(hexOf(initial))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable) {
                    if (!hexEditText.isFocused) return
                    val text = s.toString()
                    if (!text.matches(HEX6)) return
                    val parsed = Color.parseColor("#$text")
                    if (parsed != picker.getColor()) {
                        fromEditText = true
                        picker.setColor(parsed, callback = true)
                    }
                }
            })
        }
        row.addView(hexEditText, LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(row)

        return ScrollView(context).apply { addView(root) }
    }

    private fun onColorChanged(newColor: Int) {
        color = newColor
        ViewCompat.setBackground(newPanel, ColorSwatches.square(newColor))
        if (!fromEditText) {
            hexEditText.setText(hexOf(newColor))
            if (hexEditText.hasFocus()) {
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(hexEditText.windowToken, 0)
                hexEditText.clearFocus()
            }
        }
        fromEditText = false
    }

    private fun hexOf(color: Int) = String.format(Locale.ROOT, "%06X", color and 0xFFFFFF)

    companion object {
        private const val ARG_REQUEST_KEY = "requestKey"
        private const val ARG_COLOR = "color"
        private const val SAVE_COLOR = "HsvColorPickerDialogFragment.color"
        const val RESULT_HEX = "hex"
        private val HEX6 = Regex("^[0-9A-Fa-f]{6}$")

        fun newInstance(requestKey: String, initialColor: Int): HsvColorPickerDialogFragment {
            return HsvColorPickerDialogFragment().apply {
                arguments = bundleOf(ARG_REQUEST_KEY to requestKey, ARG_COLOR to initialColor)
            }
        }
    }
}
