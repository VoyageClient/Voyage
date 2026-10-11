/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.colorpicker

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.features.home.room.detail.timeline.render.RichMessageBodyRenderer
import im.vector.app.features.html.highlight.CodeBlockHighlighter
import im.vector.app.features.html.highlight.CodePalette
import javax.inject.Inject

/**
 * Picks the code block palette, previewing each on a code block as the timeline would draw it.
 *
 * Result under [ARG_REQUEST_KEY]: [RESULT_PALETTE] is the chosen [CodePalette]'s name.
 */
@AndroidEntryPoint
class CodePalettePickerDialogFragment : DialogFragment() {

    @Inject lateinit var richMessageBodyRenderer: RichMessageBodyRenderer

    private var selected: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected = savedInstanceState?.getString(SAVE_SELECTED) ?: requireArguments().getString(ARG_SELECTED)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(SAVE_SELECTED, selected)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        return MaterialAlertDialogBuilder(context)
                .setTitle(requireArguments().getString(ARG_TITLE))
                .setView(buildContent(context))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    setFragmentResult(requireArguments().getString(ARG_REQUEST_KEY)!!, bundleOf(RESULT_PALETTE to selected))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
    }

    // Recycled, so only the rows on screen are built: every preview is a full code block.
    private fun buildContent(context: Context): View {
        val density = resources.displayMetrics.density
        val palettes = CodePalette.values()
        val adapter = PaletteAdapter(palettes, density)
        return RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            val pad = (8 * density).toInt()
            setPadding(pad, pad, pad, pad)
            clipToPadding = false
            scrollToPosition(palettes.indexOfFirst { it.name == selected }.coerceAtLeast(0))
        }
    }

    private class PaletteRow(val row: LinearLayout, val button: RadioButton, val previewSlot: FrameLayout) : RecyclerView.ViewHolder(row)

    private inner class PaletteAdapter(private val palettes: Array<CodePalette>, private val density: Float) : RecyclerView.Adapter<PaletteRow>() {

        override fun getItemCount() = palettes.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PaletteRow {
            val context = parent.context
            val pad = (12 * density).toInt()
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val button = RadioButton(context)
            val previewSlot = FrameLayout(context)
            row.addView(button)
            row.addView(previewSlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = (32 * density).toInt()
            })
            return PaletteRow(row, button, previewSlot).also { holder ->
                row.setOnClickListener { select(holder.bindingAdapterPosition) }
                button.setOnClickListener { select(holder.bindingAdapterPosition) }
            }
        }

        override fun onBindViewHolder(holder: PaletteRow, position: Int, payloads: List<Any>) {
            if (payloads.contains(SELECTION_PAYLOAD)) {
                holder.button.isChecked = palettes[position].name == selected
            } else {
                onBindViewHolder(holder, position)
            }
        }

        override fun onBindViewHolder(holder: PaletteRow, position: Int) {
            val palette = palettes[position]
            holder.button.text = getString(palette.titleRes)
            holder.button.isChecked = palette.name == selected
            holder.previewSlot.removeAllViews()
            val preview = richMessageBodyRenderer.buildCodeBlockPreview(holder.row.context, PREVIEW_CODE, listOf(PREVIEW_LANGUAGE), palette) {
                select(holder.bindingAdapterPosition)
            }
            holder.previewSlot.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                val margins = preview.layoutParams as? ViewGroup.MarginLayoutParams
                topMargin = margins?.topMargin ?: 0
                bottomMargin = margins?.bottomMargin ?: 0
            })
        }

        private fun select(position: Int) {
            if (position == RecyclerView.NO_POSITION) return
            val previous = palettes.indexOfFirst { it.name == selected }
            selected = palettes[position].name
            if (previous >= 0) notifyItemChanged(previous, SELECTION_PAYLOAD)
            notifyItemChanged(position, SELECTION_PAYLOAD)
        }
    }

    companion object {
        private const val ARG_REQUEST_KEY = "requestKey"
        private const val ARG_TITLE = "title"
        private const val ARG_SELECTED = "selected"
        private const val SAVE_SELECTED = "CodePalettePickerDialogFragment.selected"

        const val RESULT_PALETTE = "palette"

        private const val SELECTION_PAYLOAD = "selection"

        private const val PREVIEW_LANGUAGE = "kotlin"
        private val PREVIEW_CODE = """
            |// Count who's here
            |fun greet(names: List<String>): Int {
            |    val count = names.size + 1
            |    println("Hello, ${'$'}count people!")
            |    return if (count > 0) count else -1
            |}
            |""".trimMargin()

        /** What the previews highlight, so it can be prepared before the dialog opens. */
        val PREVIEW_REQUEST = CodeBlockHighlighter.Request(PREVIEW_CODE, listOf(PREVIEW_LANGUAGE))

        fun newInstance(requestKey: String, title: String, selected: String) =
                CodePalettePickerDialogFragment().apply {
                    arguments = bundleOf(
                            ARG_REQUEST_KEY to requestKey,
                            ARG_TITLE to title,
                            ARG_SELECTED to selected,
                    )
                }
    }
}
