/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.devtools

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.extensions.padActionsFromScreenEdge
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.platform.VectorMenuProvider
import im.vector.app.databinding.FragmentGenericRecyclerBinding
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataEvent
import javax.inject.Inject

@AndroidEntryPoint
class AccountDataFragment :
        VectorBaseFragment<FragmentGenericRecyclerBinding>(),
        VectorMenuProvider,
        AccountDataEpoxyController.InteractionListener {

    @Inject lateinit var epoxyController: AccountDataEpoxyController

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentGenericRecyclerBinding {
        return FragmentGenericRecyclerBinding.inflate(inflater, container, false)
    }

    private val viewModel: AccountDataViewModel by fragmentViewModel(AccountDataViewModel::class)

    override fun getMenuRes() = R.menu.menu_account_data_list

    private var searchMenuItem: MenuItem? = null

    private val searchBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            searchMenuItem?.collapseActionView()
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? AppCompatActivity)?.supportActionBar?.setTitle(CommonStrings.settings_account_data)
        settingsToolbar()?.padActionsFromScreenEdge()
    }

    // Shared with every other settings screen, so the padding is undone in onDestroyView.
    private fun settingsToolbar() = activity?.findViewById<Toolbar>(R.id.settingsToolbar)

    override fun invalidate() = withState(viewModel) { state ->
        epoxyController.setData(state)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        views.genericRecyclerView.configureWith(epoxyController, dividerDrawable = R.drawable.divider_horizontal)
        epoxyController.interactionListener = this
        epoxyController.searchableContentProvider = viewModel::searchableContent
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, searchBackCallback)
    }

    override fun handlePostCreateMenu(menu: Menu) {
        val searchItem = menu.findItem(R.id.menuItemSearch) ?: return
        searchMenuItem = searchItem
        (searchItem.actionView as? SearchView)?.let { searchView ->
            searchView.queryHint = getString(CommonStrings.dev_tools_search_hint)
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?) = true

                override fun onQueryTextChange(newText: String?): Boolean {
                    viewModel.handle(AccountDataAction.UpdateSearchQuery(newText.orEmpty()))
                    return true
                }
            })
        }
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                searchBackCallback.isEnabled = true
                // Encrypted content is only searchable once the ADK is cached. Ask for it silently, but
                // only when something is actually encrypted, so we never create an ADK just by searching.
                withState(viewModel) { state ->
                    if (!viewModel.adkCached() && state.accountData.invoke().orEmpty().any { viewModel.isEncrypted(it) }) {
                        viewModel.handle(AccountDataAction.EnsureAdk)
                    }
                }
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                searchBackCallback.isEnabled = false
                viewModel.handle(AccountDataAction.UpdateSearchQuery(""))
                return true
            }
        })
    }

    override fun onDestroyView() {
        views.genericRecyclerView.cleanup()
        epoxyController.interactionListener = null
        epoxyController.searchableContentProvider = null
        searchMenuItem = null
        settingsToolbar()?.padActionsFromScreenEdge(false)
        super.onDestroyView()
    }

    override fun handleMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menuItemAdd -> {
                navigateTo(AccountDataCreateFragment())
                true
            }
            else -> false
        }
    }

    override fun didTap(data: UserAccountDataEvent) {
        navigateTo(AccountDataDetailFragment.newInstance(data.type))
    }

    private fun navigateTo(fragment: Fragment) {
        parentFragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.right_in, R.anim.fade_out, R.anim.fade_in, R.anim.right_out)
                .replace(R.id.vector_settings_page, fragment)
                .addToBackStack(null)
                .commit()
    }
}
