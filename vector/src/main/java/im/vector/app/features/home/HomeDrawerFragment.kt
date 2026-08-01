/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.extensions.copyOnLongClick
import im.vector.app.core.extensions.observeK
import im.vector.app.core.extensions.replaceChildFragment
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.resources.BuildMeta
import im.vector.app.core.session.AccountInfoCache
import im.vector.app.core.session.LogoutAccountUseCase
import im.vector.app.core.session.SwitchAccountUseCase
import im.vector.app.databinding.FragmentHomeDrawerBinding
import im.vector.app.features.MainActivity
import im.vector.app.features.MainActivityArgs
import im.vector.app.features.home.accountswitcher.AccountSwitcherAdapter
import im.vector.app.features.home.accountswitcher.AccountSwitcherEntry
import im.vector.app.features.onboarding.OnboardingActivity
import im.vector.app.features.roomdirectory.pendingrequests.PendingJoinRequestsActivity
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.settings.VectorSettingsActivity
import im.vector.app.features.spaces.SpaceListFragment
import im.vector.app.features.workers.signout.SignOutUiWorker
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.getRoomSummariesLive
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import org.matrix.android.sdk.api.session.user.getUserLive
import org.matrix.android.sdk.api.util.toMatrixItem
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class HomeDrawerFragment :
        VectorBaseFragment<FragmentHomeDrawerBinding>() {

    @Inject lateinit var session: Session
    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var avatarRenderer: AvatarRenderer
    @Inject lateinit var buildMeta: BuildMeta
    @Inject lateinit var activeSessionHolder: ActiveSessionHolder
    @Inject lateinit var switchAccountUseCase: SwitchAccountUseCase
    @Inject lateinit var logoutAccountUseCase: LogoutAccountUseCase
    @Inject lateinit var accountInfoCache: AccountInfoCache

    private lateinit var sharedActionViewModel: HomeSharedActionViewModel
    private lateinit var accountAdapter: AccountSwitcherAdapter
    private var hasPendingJoinRequests = false

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentHomeDrawerBinding {
        return FragmentHomeDrawerBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sharedActionViewModel = activityViewModelProvider.get(HomeSharedActionViewModel::class.java)

        if (savedInstanceState == null) {
            replaceChildFragment(R.id.homeDrawerGroupListContainer, SpaceListFragment::class.java)
        }
        // Drawer header reflects the live active user. The AccountInfoCache is populated
        // separately by ConfigureAndStartSessionUseCase observing the same flow on the
        // session.coroutineScope, so we don't duplicate the cache write here.
        session.userService().getUserLive(session.myUserId).observeK(viewLifecycleOwner) { optionalUser ->
            val user = optionalUser?.getOrNull()
            if (user != null) {
                avatarRenderer.render(user.toMatrixItem(), views.homeDrawerHeaderAvatarView)
                views.homeDrawerUsernameView.text = (user.displayName?.takeIf { it.isNotBlank() } ?: user.userId).prepareForDisplay()
                views.homeDrawerUserIdView.text = user.userId
                // Keep the switcher's active row in sync with profile edits when the panel is up.
                if (views.homeDrawerAccountList.isVisible) refreshAccountList()
            }
        }
        // Profile
        views.homeDrawerHeader.debouncedClicks { openProfile() }
        views.homeDrawerUserIdView.copyOnLongClick()
        // Long-clickable children swallow taps, so the header's click has to be repeated here.
        views.homeDrawerUserIdView.debouncedClicks { openProfile() }
        // Settings
        views.homeDrawerHeaderSettingsView.debouncedClicks {
            sharedActionViewModel.post(HomeActivitySharedAction.CloseDrawer)
            navigator.openSettings(requireActivity())
        }
        // Sign out
        views.homeDrawerHeaderSignoutView.debouncedClicks {
            sharedActionViewModel.post(HomeActivitySharedAction.CloseDrawer)
            SignOutUiWorker(requireActivity()).perform()
        }

        setupAccountSwitcher()

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                            WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(
                    systemBars.left,
                    systemBars.top,
                    systemBars.right,
                    systemBars.bottom,
            )
            WindowInsetsCompat.CONSUMED
        }

        // Debug menu
        views.homeDrawerHeaderDebugView.debouncedClicks {
            sharedActionViewModel.post(HomeActivitySharedAction.CloseDrawer)
            navigator.openDebug(requireActivity())
        }

        views.homeDrawerPendingRequestsView.debouncedClicks {
            sharedActionViewModel.post(HomeActivitySharedAction.CloseDrawer)
            startActivity(PendingJoinRequestsActivity.newIntent(requireContext()))
        }
        session.roomService()
                .getRoomSummariesLive(roomSummaryQueryParams { memberships = listOf(Membership.KNOCK) })
                .observeK(viewLifecycleOwner) { summaries ->
                    hasPendingJoinRequests = !summaries.isNullOrEmpty()
                    val showPending = hasPendingJoinRequests && !views.homeDrawerAccountList.isVisible
                    views.homeDrawerPendingRequestsView.isVisible = showPending
                    views.homeDrawerPendingRequestsDivider.isVisible = showPending
                }
    }

    private fun openProfile() {
        sharedActionViewModel.post(HomeActivitySharedAction.CloseDrawer)
        navigator.openSettings(requireActivity(), directAccess = VectorSettingsActivity.EXTRA_DIRECT_ACCESS_GENERAL)
    }

    private fun setupAccountSwitcher() {
        accountAdapter = AccountSwitcherAdapter(
                avatarRenderer = avatarRenderer,
                accountInfoCache = accountInfoCache,
                onAccountClick = { entry -> onAccountClicked(entry) },
                onLogoutClick = { entry -> confirmLogout(entry) },
                onAddAccountClick = { onAddAccountClicked() },
        )
        views.homeDrawerAccountList.layoutManager = LinearLayoutManager(requireContext())
        views.homeDrawerAccountList.adapter = accountAdapter
        refreshAccountList()

        views.homeDrawerAccountSwitcherToggle.debouncedClicks {
            val expanded = !views.homeDrawerAccountList.isVisible
            setSwitcherExpanded(expanded)
            if (expanded) refreshAccountList()
        }
    }

    private fun setSwitcherExpanded(expanded: Boolean) {
        views.homeDrawerAccountList.isVisible = expanded
        views.homeDrawerGroupListContainer.isVisible = !expanded
        // The pending-requests entry belongs to the spaces list; hide it while the account switcher is up.
        val showPending = hasPendingJoinRequests && !expanded
        views.homeDrawerPendingRequestsView.isVisible = showPending
        views.homeDrawerPendingRequestsDivider.isVisible = showPending
        views.homeDrawerAccountSwitcherToggle.setImageResource(
                if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
        )
    }

    private fun refreshAccountList() {
        viewLifecycleOwner.lifecycleScope.launch {
            val activeId = runCatching { activeSessionHolder.getOrInitializeSession()?.sessionId }
                    .getOrNull()
            // Pull the live profile for the active account — covers the first-activation
            // window where the on-disk cache hasn't been seeded yet.
            val activeUser = activeId?.let {
                runCatching { session.userService().getUser(session.myUserId) }.getOrNull()
            }
            val snapshots = runCatching { accountInfoCache.listAccounts() }
                    .onFailure { Timber.w(it, "refreshAccountList: failed to read account list") }
                    .getOrNull() ?: return@launch
            val entries = snapshots.map { snap ->
                val isActive = snap.sessionId == activeId
                AccountSwitcherEntry(
                        sessionId = snap.sessionId,
                        userId = snap.userId,
                        displayName = if (isActive) (activeUser?.displayName ?: snap.displayName) else snap.displayName,
                        homeServerHost = snap.homeServerHost,
                        isActive = isActive,
                        liveAvatarUrl = if (isActive) activeUser?.avatarUrl else null,
                )
            }.sortedByDescending { it.isActive }
            accountAdapter.submit(entries)
        }
    }

    private fun onAccountClicked(entry: AccountSwitcherEntry) {
        if (entry.isActive) {
            setSwitcherExpanded(false)
            return
        }
        // Activity scope, not the view-lifecycle scope — restartApp() finishes the activity
        // and a fragment-view-scoped coroutine would be cancelled mid-flight.
        requireActivity().lifecycleScope.launch {
            runCatching { switchAccountUseCase.execute(entry.sessionId) }
                    .onSuccess {
                        // Re-resolve the activity at restart time. A config change between
                        // launch and now would otherwise leave us with a stale reference.
                        if (isAdded) MainActivity.restartApp(requireActivity(), MainActivityArgs())
                    }
                    .onFailure { Timber.e(it, "Account switch to ${entry.sessionId} failed") }
        }
    }

    private fun confirmLogout(entry: AccountSwitcherEntry) {
        MaterialAlertDialogBuilder(requireActivity())
                .setTitle(CommonStrings.action_sign_out)
                .setMessage(getString(CommonStrings.action_sign_out_confirmation_simple))
                .setPositiveButton(CommonStrings.action_sign_out) { _, _ -> performLogout(entry) }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
    }

    private fun performLogout(entry: AccountSwitcherEntry) {
        viewLifecycleOwner.lifecycleScope.launch {
            when (logoutAccountUseCase.tryServerSignOut(entry.sessionId)) {
                LogoutAccountUseCase.Result.SignedOutCleanly,
                LogoutAccountUseCase.Result.NotFound -> refreshAccountList()
                LogoutAccountUseCase.Result.ServerUnreachable -> promptOfflineLogout(entry)
            }
        }
    }

    private fun promptOfflineLogout(entry: AccountSwitcherEntry) {
        MaterialAlertDialogBuilder(requireActivity())
                .setTitle(CommonStrings.account_switcher_server_unreachable_title)
                .setMessage(CommonStrings.account_switcher_server_unreachable_message)
                .setPositiveButton(CommonStrings.account_switcher_sign_out_anyway) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        logoutAccountUseCase.forceLocalSignOut(entry.sessionId)
                        refreshAccountList()
                    }
                }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
    }

    private fun onAddAccountClicked() {
        sharedActionViewModel.post(HomeActivitySharedAction.CloseDrawer)
        val intent = Intent(requireContext(), OnboardingActivity::class.java).apply {
            putExtra(OnboardingActivity.EXTRA_KEEP_EXISTING_SESSION, true)
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        views.homeDrawerHeaderDebugView.isVisible = buildMeta.isDebug && vectorPreferences.developerMode()
        // Only refresh when the switcher is the current view — keeps the cost off the
        // common path of opening the drawer with the spaces list visible.
        if (views.homeDrawerAccountList.isVisible) refreshAccountList()
    }
}
