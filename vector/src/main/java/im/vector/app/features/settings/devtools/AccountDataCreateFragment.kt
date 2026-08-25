/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.devtools

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.extensions.registerStartForActivityResult
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.platform.VectorMenuProvider
import im.vector.app.databinding.FragmentGenericRecyclerBinding
import im.vector.app.features.crypto.quads.AdkFlows
import im.vector.app.features.crypto.quads.SharedSecureStorageActivity
import im.vector.lib.core.utils.text.neutralizeDirectionOverrides
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.Session
import javax.inject.Inject

@AndroidEntryPoint
class AccountDataCreateFragment :
        VectorBaseFragment<FragmentGenericRecyclerBinding>(),
        VectorMenuProvider,
        AccountDataCreateController.InteractionListener {

    @Inject lateinit var epoxyController: AccountDataCreateController
    @Inject lateinit var session: Session

    private val viewModel: AccountDataViewModel by fragmentViewModel(AccountDataViewModel::class)

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentGenericRecyclerBinding {
        return FragmentGenericRecyclerBinding.inflate(inflater, container, false)
    }

    override fun getMenuRes() = R.menu.menu_account_data_create

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        views.genericRecyclerView.configureWith(epoxyController)
        epoxyController.interactionListener = this
        viewModel.observeViewEvents {
            when (it) {
                is AccountDataViewEvents.Failure -> showFailure(it.throwable)
                AccountDataViewEvents.UpdateSuccess -> {
                    vectorBaseActivity.showSnackbar(getString(CommonStrings.dev_tools_success_account_data))
                    parentFragmentManager.popBackStack()
                }
                is AccountDataViewEvents.AdkRequired -> handleAdkRequired(it.pending)
            }
        }
    }

    override fun onDestroyView() {
        views.genericRecyclerView.cleanup()
        epoxyController.interactionListener = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        (activity as? AppCompatActivity)?.supportActionBar?.setTitle(CommonStrings.dev_tools_add_account_data)
    }

    override fun invalidate() = withState(viewModel) { state ->
        epoxyController.setData(state)
    }

    override fun processAction(action: AccountDataAction) {
        viewModel.handle(action)
    }

    override fun handleMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menuItemSend -> {
                submit()
                true
            }
            else -> false
        }
    }

    private fun submit() = withState(viewModel) { state ->
        val type = state.draft.type?.trim().orEmpty()
        if (type.isEmpty()) {
            showFailure(IllegalArgumentException(getString(CommonStrings.dev_tools_error_no_type)))
            return@withState
        }
        val content = state.draft.content.orEmpty()
        val update = AccountDataAction.UpdateAccountData(type, content, state.draft.encrypt)
        if (state.accountData.invoke().orEmpty().any { it.type == type }) {
            MaterialAlertDialogBuilder(requireActivity())
                    .setTitle(CommonStrings.dev_tools_add_account_data)
                    .setMessage(getString(CommonStrings.dev_tools_account_data_overwrite_warning, type.neutralizeDirectionOverrides()))
                    .setNegativeButton(CommonStrings.action_cancel, null)
                    .setPositiveButton(CommonStrings.action_save) { _, _ ->
                        viewModel.handle(update)
                    }
                    .show()
        } else {
            viewModel.handle(update)
        }
    }

    private fun handleAdkRequired(pending: AccountDataAction.UpdateAccountData) {
        val intent = AdkFlows.buildAdkIntent(requireContext(), session)
        if (intent == null) {
            showFailure(IllegalStateException(getString(CommonStrings.account_data_encryption_needs_backup)))
            return
        }
        pendingUpdate = pending
        adkActivityResultLauncher.launch(intent)
    }

    private var pendingUpdate: AccountDataAction.UpdateAccountData? = null

    private val adkActivityResultLauncher = registerStartForActivityResult { activityResult ->
        val cipher = activityResult.data?.getStringExtra(SharedSecureStorageActivity.EXTRA_DATA_RESULT)
        val update = pendingUpdate
        pendingUpdate = null
        if (activityResult.resultCode == Activity.RESULT_OK && cipher != null && update != null) {
            viewModel.handle(AccountDataAction.GotAdkFromSsss(cipher, SharedSecureStorageActivity.DEFAULT_RESULT_KEYSTORE_ALIAS, thenUpdate = update))
        }
    }
}
