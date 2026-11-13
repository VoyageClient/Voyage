/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.signout.hard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.core.session.AccountInfoCache
import im.vector.app.core.session.LastActiveSessionStore
import im.vector.app.core.session.SignedOutNoticeStore
import im.vector.app.core.session.SwitchAccountUseCase
import im.vector.app.core.vpn.VpnDetector
import im.vector.app.core.vpn.VpnGate
import im.vector.app.databinding.ActivitySignedOutBinding
import im.vector.app.features.MainActivity
import im.vector.app.features.MainActivityArgs
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.accountswitcher.AccountSwitcherAdapter
import im.vector.app.features.home.accountswitcher.AccountSwitcherEntry
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.html.withTimelineBullets
import im.vector.app.features.onboarding.OnboardingActivity
import im.vector.app.features.settings.applySelectedAppLogo
import im.vector.lib.strings.CommonStrings
import im.vector.lib.ui.styles.dialogs.MaterialProgressDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.failure.GlobalError
import org.matrix.android.sdk.api.session.profile.ColorPreference
import org.matrix.android.sdk.api.util.MatrixItem
import timber.log.Timber
import javax.inject.Inject

/**
 * In this screen, the user is viewing a message informing that he has been logged out.
 */
@AndroidEntryPoint
class SignedOutActivity : VectorBaseActivity<ActivitySignedOutBinding>() {

    @Inject lateinit var signedOutNoticeStore: SignedOutNoticeStore
    @Inject lateinit var accountInfoCache: AccountInfoCache
    @Inject lateinit var avatarRenderer: AvatarRenderer
    @Inject lateinit var switchAccountUseCase: SwitchAccountUseCase
    @Inject lateinit var lastActiveSessionStore: LastActiveSessionStore
    @Inject lateinit var vpnDetector: VpnDetector
    @Inject lateinit var vpnGate: VpnGate

    private lateinit var accountAdapter: AccountSwitcherAdapter
    private var isSwitchingAccount = false

    override fun getBinding() = ActivitySignedOutBinding.inflate(layoutInflater)

    override val rootView: View
        get() = views.signedOut

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupViews()
    }

    private fun setupViews() {
        views.signedOut.applySelectedAppLogo()
        views.signedOutNotice.text = getString(CommonStrings.signed_out_notice).withTimelineBullets()
        accountAdapter = AccountSwitcherAdapter(
                avatarRenderer = avatarRenderer,
                accountInfoCache = accountInfoCache,
                onAccountClick = { entry -> onAccountClicked(entry) },
                onLogoutClick = {},
                onLogoutLongClick = {},
                onAddAccountClick = { onAddAccountClicked() },
                showLogout = false,
                // The list bleeds past the form padding so the row ripple spans the screen
                rowHorizontalPaddingPx = (FORM_HORIZONTAL_PADDING_DP * resources.displayMetrics.density).toInt(),
        )
        views.signedOutAccountList.layoutManager = LinearLayoutManager(this)
        views.signedOutAccountList.isNestedScrollingEnabled = false
        views.signedOutAccountList.adapter = accountAdapter
        views.signedOutSubmit.debouncedClicks { signIn() }
        loadContent()
    }

    private fun loadContent() {
        lifecycleScope.launch {
            val notice = withContext(Dispatchers.IO) { signedOutNoticeStore.get() }
            val accounts = runCatching { accountInfoCache.listAccounts() }
                    .onFailure { Timber.w(it, "SignedOutActivity: failed to read account list") }
                    .getOrNull()
                    .orEmpty()

            notice?.let { renderSignedOutAccount(it.userId, it.displayName) }

            val hasAccounts = accounts.isNotEmpty()
            views.signedOutOtherAccountsTitle.isVisible = hasAccounts
            views.signedOutAccountList.isVisible = hasAccounts
            views.signedOutSubmit.isVisible = !hasAccounts
            accountAdapter.submit(
                    accounts.map { snap ->
                        // None is active: rows render offline from the cache, never through the auto-picked session
                        AccountSwitcherEntry(
                                sessionId = snap.sessionId,
                                userId = snap.userId,
                                displayName = snap.displayName,
                                homeServerHost = snap.homeServerHost,
                                isActive = false,
                                liveAvatarUrl = null,
                                colorPreference = snap.colorPreference,
                        )
                    }
            )
        }
    }

    private fun renderSignedOutAccount(userId: String, displayName: String?) {
        val row = views.signedOutAccount
        row.root.isVisible = true
        row.accountSwitcherItemLogout.isVisible = false
        val avatar = signedOutNoticeStore.avatarFile.takeIf { it.exists() && it.length() > 0 }
        avatarRenderer.render(
                MatrixItem.UserItem(userId, displayName, null, colorPreference = ColorPreference()),
                avatar?.let { Uri.fromFile(it) },
                row.accountSwitcherItemAvatar,
        )
        row.accountSwitcherItemDisplayName.text = (displayName?.takeIf { it.isNotBlank() } ?: userId).prepareForDisplay()
        row.accountSwitcherItemUserId.text = userId
    }

    private fun onAccountClicked(entry: AccountSwitcherEntry) {
        if (isSwitchingAccount) return
        // The account picked automatically at sign-out already went through the launch VPN gate
        val needsSwitch = entry.sessionId != lastActiveSessionStore.get()
        if (needsSwitch &&
                vectorPreferences.isVpnConfirmSwitchEnabled() &&
                entry.sessionId !in vectorPreferences.getVpnExcludedSessionIds() &&
                !vpnDetector.isVpnActive()) {
            MaterialAlertDialogBuilder(this)
                    .setTitle(CommonStrings.vpn_switch_dialog_title)
                    .setMessage(CommonStrings.vpn_switch_dialog_message)
                    .setPositiveButton(CommonStrings.vpn_switch_dialog_confirm) { _, _ ->
                        vpnGate.acknowledge()
                        continueWith(entry)
                    }
                    .setNegativeButton(CommonStrings.action_cancel, null)
                    .show()
        } else {
            continueWith(entry)
        }
    }

    private fun continueWith(entry: AccountSwitcherEntry) {
        if (isSwitchingAccount) return
        isSwitchingAccount = true
        val dialog = MaterialProgressDialog(this).show(getString(CommonStrings.please_wait))
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (entry.sessionId != lastActiveSessionStore.get()) {
                        switchAccountUseCase.execute(entry.sessionId)
                    }
                    signedOutNoticeStore.clear()
                }
                // Remove the dialog before the task transition can animate its outgoing window.
                dialog.window?.setWindowAnimations(0)
                dialog.dismiss()
                MainActivity.restartApp(this@SignedOutActivity, MainActivityArgs())
            } catch (failure: CancellationException) {
                isSwitchingAccount = false
                throw failure
            } catch (failure: Exception) {
                isSwitchingAccount = false
                Timber.e(failure, "SignedOutActivity: switch to ${entry.sessionId} failed")
                dialog.dismiss()
                MaterialAlertDialogBuilder(this@SignedOutActivity)
                        .setTitle(CommonStrings.dialog_title_error)
                        .setMessage(errorFormatter.toHumanReadable(failure))
                        .setPositiveButton(CommonStrings.ok, null)
                        .show()
            }
        }
    }

    private fun onAddAccountClicked() {
        // Back returns here; a completed sign-in restarts the app and expires the notice
        startActivity(
                Intent(this, OnboardingActivity::class.java)
                        .putExtra(OnboardingActivity.EXTRA_KEEP_EXISTING_SESSION, true)
        )
    }

    private fun signIn() {
        // The notice stays pending until a sign-in completes, so leaving the login screen brings it back
        navigator.openLogin(this, null, Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        finish()
    }

    companion object {
        // LoginFormContainer's side padding
        private const val FORM_HORIZONTAL_PADDING_DP = 36

        fun newIntent(context: Context): Intent {
            return Intent(context, SignedOutActivity::class.java)
        }
    }

    override fun handleInvalidToken(globalError: GlobalError.InvalidToken) {
        // No op here
        Timber.w("Ignoring invalid token global error")
    }
}
