/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.useragent

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceDialogFragmentCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import im.vector.lib.strings.CommonStrings

/** Implemented by the host settings fragment so the "sort by latest" choice is remembered between popups. */
interface UaVersionSortHost {
    var sortVersionsByLatest: Boolean
}

/**
 * Single-choice picker for a [UaVersionListPreference]. A checkbox toggles between "most used" (usage
 * share, descending) and "latest" (the provider's newest-first order); a search box narrows long lists
 * (device catalogues run to thousands). Backed by a RecyclerView so every option scrolls smoothly.
 */
class UaVersionListPreferenceDialogFragment : PreferenceDialogFragmentCompat() {

    private var selectedValue: String? = null
    private var values: Array<CharSequence> = emptyArray()
    private var labels: Array<CharSequence> = emptyArray()
    private var shares: DoubleArray? = null
    private var sortByLatest = false
    private var query = ""
    private var adapter: OptionAdapter? = null

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pref = preference as UaVersionListPreference
        selectedValue = savedInstanceState?.getString(SAVE_VALUE) ?: pref.value
        values = pref.entryValues ?: emptyArray()
        labels = pref.entries ?: emptyArray()
        shares = pref.optionShares
        sortByLatest = (targetFragment as? UaVersionSortHost)?.sortVersionsByLatest ?: false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(SAVE_VALUE, selectedValue)
    }

    @Suppress("DEPRECATION")
    override fun onCreateDialogView(context: Context): View {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }

        root.addView(CheckBox(context).apply {
            setText(CommonStrings.settings_ua_sort_by_latest)
            isChecked = sortByLatest
            setOnCheckedChangeListener { _, checked ->
                sortByLatest = checked
                (targetFragment as? UaVersionSortHost)?.sortVersionsByLatest = checked
                adapter?.submit(orderedIndices())
            }
        })

        if (values.size > SEARCH_THRESHOLD) {
            root.addView(EditText(context).apply {
                setHint(CommonStrings.settings_ua_search_hint)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        query = s?.toString()?.trim().orEmpty()
                        adapter?.submit(orderedIndices())
                    }
                })
            })
        }

        adapter = OptionAdapter()
        root.addView(
                RecyclerView(context).apply {
                    layoutManager = LinearLayoutManager(context)
                    adapter = this@UaVersionListPreferenceDialogFragment.adapter
                    // Bound the height so the dialog doesn't grow past the screen with a big list.
                    val h = (resources.displayMetrics.heightPixels * 0.5f).toInt()
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
                },
        )
        adapter?.submit(orderedIndices())
        return root
    }

    /** Option indices in display order, filtered by the current query. Most-used first unless "latest". */
    private fun orderedIndices(): List<Int> {
        val ordered = if (sortByLatest) {
            values.indices.toList()
        } else {
            val shareOf = shares
            values.indices.sortedWith(
                    compareByDescending<Int> { shareOf?.getOrNull(it)?.takeUnless { s -> s.isNaN() } ?: Double.NEGATIVE_INFINITY }
                            .thenBy { it },
            )
        }
        return if (query.isEmpty()) ordered
        else ordered.filter { labels.getOrNull(it)?.contains(query, ignoreCase = true) == true }
    }

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)
        builder.setPositiveButton(android.R.string.ok, this)
        builder.setNegativeButton(android.R.string.cancel, this)
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        adapter = null
        val pref = preference as UaVersionListPreference
        val selected = selectedValue
        if (positiveResult && selected != null && pref.callChangeListener(selected)) {
            pref.value = selected
        }
    }

    private inner class OptionAdapter : RecyclerView.Adapter<OptionAdapter.VH>() {
        private var order: List<Int> = emptyList()

        fun submit(newOrder: List<Int>) {
            order = newOrder
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = android.view.LayoutInflater.from(parent.context)
                    .inflate(android.R.layout.simple_list_item_single_choice, parent, false) as CheckedTextView
            return VH(view)
        }

        override fun getItemCount() = order.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val index = order[position]
            val value = values[index].toString()
            holder.text.text = labels.getOrNull(index) ?: value
            holder.text.isChecked = value == selectedValue
            holder.text.setOnClickListener {
                val previous = order.indexOfFirst { values[it].toString() == selectedValue }
                selectedValue = value
                if (previous >= 0) notifyItemChanged(previous)
                notifyItemChanged(position)
            }
        }

        inner class VH(val text: CheckedTextView) : RecyclerView.ViewHolder(text)
    }

    companion object {
        private const val SAVE_VALUE = "UaVersionListPreferenceDialogFragment.value"
        private const val SEARCH_THRESHOLD = 20

        fun newInstance(key: String) = UaVersionListPreferenceDialogFragment().apply {
            arguments = Bundle(1).apply { putString(ARG_KEY, key) }
        }
    }
}
