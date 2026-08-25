/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.devtools

import android.app.Activity
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.args
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.hideKeyboard
import im.vector.app.core.extensions.registerStartForActivityResult
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.platform.VectorMenuProvider
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.utils.createJSonViewerStyleProvider
import im.vector.app.databinding.FragmentAccountDataDetailBinding
import im.vector.app.features.crypto.quads.AdkFlows
import im.vector.app.features.crypto.quads.SharedSecureStorageActivity
import im.vector.lib.core.utils.text.DirectionOverridesTransformation
import im.vector.lib.core.utils.text.neutralizeDirectionOverrides
import im.vector.lib.strings.CommonStrings
import kotlinx.parcelize.Parcelize
import org.billcarsonfr.jsonviewer.JSonViewerFragment
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataEvent
import javax.inject.Inject

@Parcelize
data class AccountDataDetailArgs(
        val type: String
) : Parcelable

@AndroidEntryPoint
class AccountDataDetailFragment :
        VectorBaseFragment<FragmentAccountDataDetailBinding>(),
        VectorMenuProvider {

    @Inject lateinit var colorProvider: ColorProvider
    @Inject lateinit var session: Session

    private val fragmentArgs: AccountDataDetailArgs by args()
    private val viewModel: AccountDataViewModel by fragmentViewModel(AccountDataViewModel::class)

    private var editing = false
    private var showRaw = false
    private var currentJson: String? = null
    private var ensureAdkAttempted = false
    private var pendingUpdate: AccountDataAction.UpdateAccountData? = null

    private val adkActivityResultLauncher = registerStartForActivityResult { activityResult ->
        val update = pendingUpdate
        pendingUpdate = null
        val cipher = activityResult.data?.getStringExtra(SharedSecureStorageActivity.EXTRA_DATA_RESULT)
        if (activityResult.resultCode == Activity.RESULT_OK && cipher != null) {
            viewModel.handle(AccountDataAction.GotAdkFromSsss(cipher, SharedSecureStorageActivity.DEFAULT_RESULT_KEYSTORE_ALIAS, thenUpdate = update))
        }
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            exitEditMode()
        }
    }

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentAccountDataDetailBinding {
        return FragmentAccountDataDetailBinding.inflate(inflater, container, false)
    }

    override fun getMenuRes() = R.menu.menu_account_data_detail

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        views.editText.transformationMethod = DirectionOverridesTransformation
        editing = savedInstanceState?.getBoolean(KEY_EDITING) ?: false
        showRaw = savedInstanceState?.getBoolean(KEY_SHOW_RAW) ?: false
        // Fragment instance survives back-stack view recreation; reset so invalidate() rebuilds the viewer.
        currentJson = null
        applyEditingMode()
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        viewModel.observeViewEvents {
            when (it) {
                is AccountDataViewEvents.Failure -> showFailure(it.throwable)
                AccountDataViewEvents.UpdateSuccess -> {
                    vectorBaseActivity.showSnackbar(getString(CommonStrings.dev_tools_success_account_data))
                    parentFragmentManager.popBackStack()
                }
                is AccountDataViewEvents.AdkRequired -> {
                    dismissLoadingDialog()
                    val intent = AdkFlows.buildAdkIntent(requireContext(), session)
                    if (intent == null) {
                        showFailure(IllegalStateException(getString(CommonStrings.account_data_encryption_needs_backup)))
                    } else {
                        pendingUpdate = it.pending
                        adkActivityResultLauncher.launch(intent)
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_EDITING, editing)
        outState.putBoolean(KEY_SHOW_RAW, showRaw)
    }

    override fun onResume() {
        super.onResume()
        (activity as? AppCompatActivity)?.supportActionBar?.title = fragmentArgs.type.neutralizeDirectionOverrides()
    }

    // Encrypted content is displayed decrypted whenever the ADK allows it, unless "Raw" is checked.
    private fun showDecrypted(event: UserAccountDataEvent): Boolean {
        return viewModel.isEncrypted(event) && !showRaw && viewModel.adkCached()
    }

    override fun invalidate() = withState(viewModel) { state ->
        val event = state.accountData.invoke()?.find { it.type == fragmentArgs.type }
        if (event == null) {
            // Deleted (here or elsewhere) — nothing left to show.
            if (state.accountData is Success) parentFragmentManager.popBackStack()
            return@withState
        }
        requireActivity().invalidateOptionsMenu()
        // The 4S key may be cached from an earlier unlock: try to fetch the ADK without prompting
        if (viewModel.isEncrypted(event) && !viewModel.adkCached() && !ensureAdkAttempted) {
            ensureAdkAttempted = true
            viewModel.handle(AccountDataAction.EnsureAdk)
        }
        val json = viewModel.sanitizedJson(event, decrypted = showDecrypted(event))
        if (json != currentJson) {
            currentJson = json
            childFragmentManager.beginTransaction()
                    .replace(
                            views.jsonViewerContainer.id,
                            JSonViewerFragment.newInstance(
                                    jsonString = json,
                                    initialOpenDepth = -1,
                                    wrap = true,
                                    styleProvider = createJSonViewerStyleProvider(colorProvider)
                            )
                    )
                    .commit()
        }
    }

    override fun handlePrepareMenu(menu: Menu) = withState(viewModel) { state ->
        val event = state.accountData.invoke()?.find { it.type == fragmentArgs.type }
        val encrypted = event != null && viewModel.isEncrypted(event)
        val adkCached = viewModel.adkCached()
        menu.findItem(R.id.menuItemEdit).isVisible = !editing
        menu.findItem(R.id.menuItemDelete).isVisible = !editing
        menu.findItem(R.id.menuItemSend).isVisible = editing
        menu.findItem(R.id.menuItemRaw).apply {
            isVisible = encrypted && adkCached && !editing
            isChecked = showRaw
        }
        menu.findItem(R.id.menuItemDecrypt).isVisible = encrypted && !adkCached && !editing
    }

    override fun handleMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menuItemEdit -> {
                enterEditMode()
                true
            }
            R.id.menuItemSend -> withState(viewModel) { state ->
                showLoadingDialog()
                val event = state.accountData.invoke()?.find { it.type == fragmentArgs.type }
                // Content edited in decrypted form goes back encrypted; a raw edit is sent verbatim.
                val encrypt = event != null && showDecrypted(event)
                viewModel.handle(AccountDataAction.UpdateAccountData(fragmentArgs.type, views.editText.text.toString(), encrypt))
                true
            }
            R.id.menuItemRaw -> {
                showRaw = !showRaw
                invalidate()
                true
            }
            R.id.menuItemDecrypt -> {
                requestAdkFromSsss()
                true
            }
            R.id.menuItemDelete -> {
                promptDelete()
                true
            }
            else -> false
        }
    }

    private fun requestAdkFromSsss() {
        val intent = AdkFlows.buildAdkReadIntent(requireContext(), session)
        if (intent == null) {
            vectorBaseActivity.showSnackbar(getString(CommonStrings.failed_to_access_secure_storage))
            return
        }
        adkActivityResultLauncher.launch(intent)
    }

    private fun enterEditMode() = withState(viewModel) { state ->
        val event = state.accountData.invoke()?.find { it.type == fragmentArgs.type } ?: return@withState
        editing = true
        views.editText.setText(viewModel.prettyContent(event, decrypted = showDecrypted(event)))
        applyEditingMode()
    }

    private fun exitEditMode() {
        editing = false
        applyEditingMode()
    }

    private fun applyEditingMode() {
        views.editorScrollView.isVisible = editing
        views.jsonViewerContainer.isVisible = !editing
        backCallback.isEnabled = editing
        if (editing) views.editText.requestFocus() else views.editText.hideKeyboard()
        requireActivity().invalidateOptionsMenu()
    }

    private fun promptDelete() {
        MaterialAlertDialogBuilder(requireActivity(), im.vector.lib.ui.styles.R.style.ThemeOverlay_Vector_MaterialAlertDialog_Destructive)
                .setTitle(CommonStrings.action_delete)
                .setMessage(getString(CommonStrings.delete_account_data_warning, fragmentArgs.type.neutralizeDirectionOverrides()))
                .setNegativeButton(CommonStrings.action_cancel, null)
                .setPositiveButton(CommonStrings.action_delete) { _, _ ->
                    viewModel.handle(AccountDataAction.DeleteAccountData(fragmentArgs.type))
                }
                .show()
    }

    companion object {
        private const val KEY_EDITING = "KEY_EDITING"
        private const val KEY_SHOW_RAW = "KEY_SHOW_RAW"

        fun newInstance(type: String) = AccountDataDetailFragment().apply {
            setArguments(AccountDataDetailArgs(type))
        }
    }
}
