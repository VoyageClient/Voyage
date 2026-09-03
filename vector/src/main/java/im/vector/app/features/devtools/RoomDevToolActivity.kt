/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.devtools

import android.content.Context
import android.content.Intent
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import androidx.activity.addCallback
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.Mavericks
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.Uninitialized
import com.airbnb.mvrx.viewModel
import com.airbnb.mvrx.withState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.padActionsFromScreenEdge
import im.vector.app.core.platform.SimpleFragmentActivity
import im.vector.app.core.platform.VectorMenuProvider
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.utils.createJSonViewerStyleProvider
import im.vector.lib.strings.CommonStrings
import kotlinx.parcelize.Parcelize
import org.billcarsonfr.jsonviewer.JSonViewerFragment
import javax.inject.Inject

@AndroidEntryPoint
class RoomDevToolActivity :
        SimpleFragmentActivity(),
        VectorMenuProvider {

    @Inject lateinit var colorProvider: ColorProvider

    //    private lateinit var viewModel: RoomDevToolViewModel
    private val viewModel: RoomDevToolViewModel by viewModel()

    override fun getTitleRes() = CommonStrings.dev_tools_menu_name

    override fun getMenuRes() = R.menu.menu_devtools

    private var currentDisplayMode: RoomDevToolViewState.Mode? = null

    private var searchMenuItem: MenuItem? = null

    @Parcelize
    data class Args(
            val roomId: String,
            val sendTarget: RoomDevToolViewState.SendTarget? = null
    ) : Parcelable

    override fun initUiAndData() {
        super.initUiAndData()
        views.toolbar.padActionsFromScreenEdge()
        // Route back (gesture, button and toolbar up) through the ViewModel, which owns the navigation
        // history. Always enabled; at Root it emits Dismiss, which finishes the activity.
        onBackPressedDispatcher.addCallback(this) {
            val expandedSearch = searchMenuItem?.takeIf { it.isActionViewExpanded }
            if (expandedSearch != null) {
                expandedSearch.collapseActionView()
            } else {
                viewModel.handle(RoomDevToolAction.OnBackPressed)
            }
        }
        viewModel.onEach {
            renderState(it)
        }

        viewModel.observeViewEvents {
            when (it) {
                DevToolsViewEvents.Dismiss -> finish()
                is DevToolsViewEvents.ShowAlertMessage -> {
                    MaterialAlertDialogBuilder(this)
                            .setMessage(it.message)
                            .setPositiveButton(CommonStrings.ok, null)
                            .show()
                    Unit
                }
                is DevToolsViewEvents.ShowSnackMessage -> showSnackbar(it.message)
            }
        }
    }

    private fun renderState(it: RoomDevToolViewState) {
        // The ViewModel owns the navigation history (handleBack), so we simply show the fragment for the
        // current mode. Keeping a separate FragmentManager back stack here made backing out glitchy and
        // left the toolbar title stale.
        if (it.displayMode != currentDisplayMode) {
            val fragment: Fragment = when (it.displayMode) {
                RoomDevToolViewState.Mode.Root -> RoomDevToolFragment()
                RoomDevToolViewState.Mode.StateEventDetail,
                RoomDevToolViewState.Mode.AccountDataDetail -> JSonViewerFragment.newInstance(
                        jsonString = it.selectedEventJson ?: "",
                        initialOpenDepth = -1,
                        wrap = true,
                        styleProvider = createJSonViewerStyleProvider(colorProvider)
                )
                RoomDevToolViewState.Mode.StateEventList,
                RoomDevToolViewState.Mode.StateEventListByType,
                RoomDevToolViewState.Mode.AccountDataList -> RoomDevToolStateEventListFragment()
                RoomDevToolViewState.Mode.EditEventContent -> RoomDevToolEditFragment()
                is RoomDevToolViewState.Mode.SendEventForm -> RoomDevToolSendFormFragment()
            }
            supportFragmentManager.beginTransaction()
                    .setCustomAnimations(R.anim.fade_in, R.anim.fade_out, R.anim.fade_in, R.anim.fade_out)
                    .replace(views.container.id, fragment)
                    .commit()
            currentDisplayMode = it.displayMode
            updateToolBar(it)
            invalidateOptionsMenu()
        }

        if (it.displayMode == RoomDevToolViewState.Mode.StateEventDetail ||
                it.displayMode == RoomDevToolViewState.Mode.AccountDataDetail) {
            (supportFragmentManager.findFragmentById(views.container.id) as? JSonViewerFragment)
                    ?.setSearchQuery(it.detailSearchQuery)
        }

        when (it.modalLoading) {
            is Loading -> showWaitingView()
            is Success -> hideWaitingView()
            is Fail -> {
                hideWaitingView()
            }
            Uninitialized -> {
            }
        }
    }

    override fun handleMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menuItemEdit -> {
                viewModel.handle(RoomDevToolAction.MenuEdit)
                true
            }
            R.id.menuItemSend -> {
                viewModel.handle(RoomDevToolAction.MenuItemSend)
                true
            }
            else -> false
        }
    }

    override fun onDestroy() {
        currentDisplayMode = null
        searchMenuItem = null
        super.onDestroy()
    }

    override fun handlePostCreateMenu(menu: Menu) {
        val searchItem = menu.findItem(R.id.menuItemSearch) ?: return
        searchMenuItem = searchItem
        (searchItem.actionView as? SearchView)?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = true

            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.handle(RoomDevToolAction.UpdateSearchQuery(newText.orEmpty()))
                return true
            }
        })
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem) = true

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                viewModel.handle(RoomDevToolAction.UpdateSearchQuery(""))
                return true
            }
        })
    }

    override fun handlePrepareMenu(menu: Menu) {
        withState(viewModel) { state ->
            menu.findItem(R.id.menuItemSearch)?.let { searchItem ->
                val isDetail = state.displayMode == RoomDevToolViewState.Mode.StateEventDetail ||
                        state.displayMode == RoomDevToolViewState.Mode.AccountDataDetail
                searchItem.isVisible = isDetail ||
                        state.displayMode == RoomDevToolViewState.Mode.StateEventList ||
                        state.displayMode == RoomDevToolViewState.Mode.StateEventListByType ||
                        state.displayMode == RoomDevToolViewState.Mode.AccountDataList
                (searchItem.actionView as? SearchView)?.queryHint = getString(
                        if (isDetail) CommonStrings.dev_tools_search_hint_json else CommonStrings.dev_tools_search_hint
                )
                // The menu is re-created on every mode change; re-open it on the query we still hold, so
                // coming back from an event opened out of the results keeps them.
                val activeQuery = if (isDetail) state.detailSearchQuery else state.searchQuery
                if (searchItem.isVisible && activeQuery.isNotEmpty() && !searchItem.isActionViewExpanded) {
                    searchItem.expandActionView()
                    (searchItem.actionView as? SearchView)?.setQuery(activeQuery, false)
                }
            }
            menu.findItem(R.id.menuItemEdit).isVisible = state.canEditState &&
                    (state.displayMode == RoomDevToolViewState.Mode.StateEventDetail ||
                            state.displayMode == RoomDevToolViewState.Mode.AccountDataDetail)
            menu.findItem(R.id.menuItemSend).isVisible = state.displayMode == RoomDevToolViewState.Mode.EditEventContent ||
                    state.displayMode is RoomDevToolViewState.Mode.SendEventForm
        }
    }

    companion object {

        fun intent(context: Context, roomId: String, sendTarget: RoomDevToolViewState.SendTarget? = null): Intent {
            return Intent(context, RoomDevToolActivity::class.java).apply {
                putExtra(Mavericks.KEY_ARG, Args(roomId, sendTarget))
            }
        }
    }

    private fun updateToolBar(state: RoomDevToolViewState) {
        val title = when (state.displayMode) {
            RoomDevToolViewState.Mode.Root -> {
                getString(getTitleRes())
            }
            RoomDevToolViewState.Mode.StateEventList -> {
                getString(CommonStrings.dev_tools_state_event)
            }
            RoomDevToolViewState.Mode.StateEventDetail -> {
                state.selectedEvent?.type
            }
            RoomDevToolViewState.Mode.AccountDataList -> {
                getString(CommonStrings.dev_tools_explore_room_account_data)
            }
            RoomDevToolViewState.Mode.AccountDataDetail -> {
                state.selectedAccountData?.type
            }
            RoomDevToolViewState.Mode.EditEventContent -> {
                getString(CommonStrings.dev_tools_edit_content)
            }
            RoomDevToolViewState.Mode.StateEventListByType -> {
                state.currentStateType ?: ""
            }
            is RoomDevToolViewState.Mode.SendEventForm -> {
                getString(
                        when (state.displayMode.target) {
                            RoomDevToolViewState.SendTarget.STATE -> CommonStrings.dev_tools_send_custom_state_event
                            RoomDevToolViewState.SendTarget.MESSAGE -> CommonStrings.dev_tools_send_custom_event
                            RoomDevToolViewState.SendTarget.ACCOUNT_DATA -> CommonStrings.dev_tools_send_room_account_data
                        }
                )
            }
        }

        supportActionBar?.let {
            it.title = title
        } ?: run {
            setTitle(title)
        }
        invalidateOptionsMenu()
    }
}
