/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.epoxy.ListDividerDecoration
import im.vector.app.core.platform.OnBackPressed
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.databinding.FragmentSettingsSearchBinding
import im.vector.app.databinding.ItemSettingsSearchResultBinding
import im.vector.app.features.settings.VectorSettingsActivity
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Results of the settings toolbar search, laid over the current settings page. */
@AndroidEntryPoint
class SettingsSearchFragment : VectorBaseFragment<FragmentSettingsSearchBinding>(), OnBackPressed {

    @Inject lateinit var searchIndex: SettingsSearchIndex

    private var matcher: SettingsSearchMatcher? = null

    var query = ""
        private set

    private val resultsAdapter = ResultsAdapter { entry ->
        (activity as? VectorSettingsActivity)?.openSearchResult(entry)
    }

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentSettingsSearchBinding =
            FragmentSettingsSearchBinding.inflate(inflater, container, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        query = savedInstanceState?.getString(KEY_QUERY).orEmpty()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        views.settingsSearchResults.layoutManager = LinearLayoutManager(requireContext())
        views.settingsSearchResults.adapter = resultsAdapter
        views.settingsSearchResults.addItemDecoration(ListDividerDecoration(requireContext()))

        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val entries = withContext(Dispatchers.Default) { searchIndex.build(context) }
            matcher = SettingsSearchMatcher(entries)
            render()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_QUERY, query)
    }

    override fun onBackPressed(toolbarButton: Boolean): Boolean {
        (activity as? VectorSettingsActivity)?.closeSearch()
        return true
    }

    fun setQuery(query: String) {
        this.query = query
        render()
    }

    private fun render() {
        if (view == null) return
        val matcher = matcher ?: return
        val results = matcher.search(query)
        resultsAdapter.submit(results, rootTitle = getString(CommonStrings.title_activity_settings))
        views.settingsSearchEmpty.isVisible = query.isNotBlank() && results.isEmpty()
    }

    private class ResultsAdapter(
            private val onClick: (SettingsSearchEntry) -> Unit,
    ) : RecyclerView.Adapter<ResultsAdapter.Holder>() {

        private var results: List<SettingsSearchEntry> = emptyList()
        private var rootTitle = ""

        class Holder(val views: ItemSettingsSearchResultBinding) : RecyclerView.ViewHolder(views.root)

        fun submit(results: List<SettingsSearchEntry>, rootTitle: String) {
            this.results = results
            this.rootTitle = rootTitle
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        override fun getItemCount() = results.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                Holder(ItemSettingsSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = results[position]
            holder.views.settingsSearchResultBreadcrumb.text = entry.breadcrumb.ifEmpty { listOf(rootTitle) }.joinToString(" › ")
            holder.views.settingsSearchResultTitle.text = entry.title
            holder.views.settingsSearchResultSummary.text = entry.summary
            holder.views.settingsSearchResultSummary.isVisible = !entry.summary.isNullOrBlank()
            holder.views.root.setOnClickListener { onClick(entry) }
        }
    }

    companion object {
        private const val KEY_QUERY = "query"
    }
}
