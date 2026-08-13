/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.BlendModeColorFilterCompat
import androidx.core.graphics.BlendModeCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.airbnb.mvrx.viewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.dalvik.DalvikVerifierGate
import im.vector.app.core.extensions.startSyncing
import im.vector.app.core.extensions.vectorStore
import im.vector.app.core.glide.MediaCache
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.core.session.AccountInfoCache
import im.vector.app.core.session.SwitchAccountUseCase
import im.vector.app.core.utils.deleteAllFiles
import im.vector.app.core.vpn.VpnGate
import im.vector.app.databinding.ActivityMainBinding
import im.vector.app.features.home.HomeActivity
import im.vector.app.features.home.ShortcutsHandler
import im.vector.app.features.home.room.detail.RoomDetailActivity
import im.vector.app.features.home.room.threads.ThreadsActivity
import im.vector.app.features.location.live.map.LiveLocationMapViewActivity
import im.vector.app.features.notifications.NotificationDrawerManager
import im.vector.app.features.pin.UnlockedActivity
import im.vector.app.features.pin.lockscreen.crypto.LockScreenKeyRepository
import im.vector.app.features.pin.lockscreen.pincode.PinCodeHelper
import im.vector.app.features.popup.PopupAlertManager
import im.vector.app.features.redaction.preservation.RedactionCacheCleaner
import im.vector.app.features.redaction.preservation.RedactionPreservationService
import im.vector.app.features.session.VectorSessionStore
import im.vector.app.features.signout.hard.SignedOutActivity
import im.vector.app.features.start.StartAppAction
import im.vector.app.features.start.StartAppAndroidService
import im.vector.app.features.start.StartAppViewEvent
import im.vector.app.features.start.StartAppViewModel
import im.vector.app.features.start.StartAppViewState
import im.vector.app.features.themes.ActivityOtherThemes
import im.vector.app.features.ui.UiStateRepository
import im.vector.lib.core.utils.compat.getParcelableExtraCompat
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import org.matrix.android.sdk.api.auth.AuthenticationService
import org.matrix.android.sdk.api.auth.data.sessionId
import org.matrix.android.sdk.api.failure.GlobalError
import org.matrix.android.sdk.api.session.Session
import timber.log.Timber
import javax.inject.Inject

@Parcelize
data class MainActivityArgs(
        val clearCache: Boolean = false,
        val clearCredentials: Boolean = false,
        val ignoreLogoutServerError: Boolean = false,
        val keepServerSession: Boolean = false,
        val isUserLoggedOut: Boolean = false,
        val isAccountDeactivated: Boolean = false,
        val isSoftLogout: Boolean = false
) : Parcelable

/**
 * This is the entry point of Element Android.
 * This Activity, when started with argument, is also doing some cleanup when user signs out,
 * clears cache, is logged out, or is soft logged out.
 */
@AndroidEntryPoint
class MainActivity : VectorBaseActivity<ActivityMainBinding>(), UnlockedActivity {

    companion object {
        private const val EXTRA_ARGS = "EXTRA_ARGS"
        private const val EXTRA_NEXT_INTENT = "EXTRA_NEXT_INTENT"
        private const val EXTRA_INIT_SESSION = "EXTRA_INIT_SESSION"
        private const val EXTRA_ROOM_ID = "EXTRA_ROOM_ID"
        private const val ACTION_ROOM_DETAILS_FROM_SHORTCUT = "ROOM_DETAILS_FROM_SHORTCUT"
        private const val RESTART_REQUEST_CODE = 0

        // Special action to clear cache and/or clear credentials
        fun restartApp(activity: Activity, args: MainActivityArgs) {
            val intent = Intent(activity, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

            intent.putExtra(EXTRA_ARGS, args)
            activity.startActivity(intent)
        }

        // Full process restart: schedule a fresh launch then kill the process, so Application.onCreate
        // re-runs. Needed for settings wired once at startup (e.g. the emoji path) that restartApp's
        // activity-only relaunch wouldn't pick up.
        fun restartProcess(activity: Activity) {
            val intent = Intent(activity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_CANCEL_CURRENT
            }
            val pendingIntent = PendingIntent.getActivity(activity, RESTART_REQUEST_CODE, intent, flags)
            val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + 200, pendingIntent)
            Runtime.getRuntime().exit(0)
        }

        fun getIntentToInitSession(activity: Activity): Intent {
            val intent = Intent(activity, MainActivity::class.java)
            intent.putExtra(EXTRA_INIT_SESSION, true)
            return intent
        }

        fun getIntentWithNextIntent(context: Context, nextIntent: Intent): Intent {
            val intent = Intent(context, MainActivity::class.java)
            intent.putExtra(EXTRA_NEXT_INTENT, nextIntent)
            return intent
        }

        // Shortcuts can't have intents with parcelables
        fun shortcutIntent(context: Context, roomId: String): Intent {
            return Intent(context, MainActivity::class.java).apply {
                action = ACTION_ROOM_DETAILS_FROM_SHORTCUT
                putExtra(EXTRA_ROOM_ID, roomId)
            }
        }

        val allowList = listOf(
                HomeActivity::class.java.name,
                MainActivity::class.java.name,
                RoomDetailActivity::class.java.name,
                ThreadsActivity::class.java.name,
                LiveLocationMapViewActivity::class.java.name,
        )
    }

    private val startAppViewModel: StartAppViewModel by viewModel()

    override fun getBinding() = ActivityMainBinding.inflate(layoutInflater)

    override fun getOtherThemes() = ActivityOtherThemes.Launcher

    // Keep the splash windowBackground (logo); don't replace it with a flat color.
    override val forceOpaqueWindowBackgroundPreLollipop = false

    private lateinit var args: MainActivityArgs

    @Inject lateinit var notificationDrawerManager: NotificationDrawerManager
    @Inject lateinit var uiStateRepository: UiStateRepository
    @Inject lateinit var shortcutsHandler: ShortcutsHandler
    @Inject lateinit var pinCodeHelper: PinCodeHelper
    @Inject lateinit var popupAlertManager: PopupAlertManager
    @Inject lateinit var redactionCacheCleaner: RedactionCacheCleaner
    @Inject lateinit var redactionPreservationService: RedactionPreservationService
    @Inject lateinit var lockScreenKeyRepository: LockScreenKeyRepository
    @Inject lateinit var authenticationService: AuthenticationService
    @Inject lateinit var switchAccountUseCase: SwitchAccountUseCase
    @Inject lateinit var accountInfoCache: AccountInfoCache
    @Inject lateinit var mediaCache: MediaCache
    @Inject lateinit var vpnGate: VpnGate

    private var startAppRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The base activity applies the user theme, which replaces the launcher theme's splash window
        // background with a plain colour — the glyph would only ever show in the starting window.
        window.setBackgroundDrawable(ContextCompat.getDrawable(this, im.vector.lib.ui.styles.R.drawable.splash))

        if (DalvikVerifierGate.gateIfNeeded(this)) {
            // Pre-KitKat with the bytecode verifier still active: a blocking dialog is up (quit or
            // reboot). Don't start the app — opening a room would crash on LinearAlloc.
            return
        }

        shortcutsHandler.updateShortcutsWithPreviousIntent()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            // android:indeterminateTint is ignored pre-Lollipop, leaving the holo-blue default
            views.progressBar.indeterminateDrawable?.colorFilter =
                    BlendModeColorFilterCompat.createBlendModeColorFilterCompat(Color.WHITE, BlendModeCompat.SRC_IN)
        }

        startAppViewModel.onEach {
            renderState(it)
        }
        startAppViewModel.observeViewEvents {
            handleViewEvents(it)
        }

        // Session init (and its network side effects) waits for the VPN gate; while closed the
        // VectorBaseActivity observer shows VpnWarningActivity over this splash screen
        vpnGate.refreshSnapshot()
        vpnGateState.liveClosed.observe(this) { closed ->
            if (!closed && !startAppRequested) {
                startAppRequested = true
                startAppViewModel.handle(StartAppAction.StartApp)
            }
        }
    }

    private fun renderState(state: StartAppViewState) {
        if (state.mayBeLongToProcess) {
            views.status.setText(CommonStrings.updating_your_data)
        }
        views.status.isVisible = state.mayBeLongToProcess
    }

    private fun handleViewEvents(event: StartAppViewEvent) {
        when (event) {
            StartAppViewEvent.StartForegroundService -> handleStartForegroundService()
            StartAppViewEvent.AppStarted -> handleAppStarted()
        }
    }

    private fun handleStartForegroundService() {
        if (startAppViewModel.shouldStartApp()) {
            // Start foreground service, because the operation may take a while
            val intent = Intent(this, StartAppAndroidService::class.java)
            ContextCompat.startForegroundService(this, intent)
        }
    }

    private fun handleAppStarted() {
        // On the first run with rust crypto this would be false
        if (!vectorPreferences.isOnRustCrypto()) {
            if (activeSessionHolder.hasActiveSession()) {
                vectorPreferences.setHadExistingLegacyData(activeSessionHolder.getActiveSession().isOpenable)
            } else {
                vectorPreferences.setHadExistingLegacyData(false)
            }
        }

        vectorPreferences.setIsOnRustCrypto(true)

        if (intent.hasExtra(EXTRA_NEXT_INTENT)) {
            // Start the next Activity
            startSyncing()
            val nextIntent = intent.getParcelableExtraCompat<Intent>(EXTRA_NEXT_INTENT)
                    ?.takeIf { it.isValid() }
            startIntentAndFinish(nextIntent)
        } else if (intent.hasExtra(EXTRA_INIT_SESSION)) {
            startSyncing()
            setResult(RESULT_OK)
            finish()
        } else if (intent.action == ACTION_ROOM_DETAILS_FROM_SHORTCUT) {
            startSyncing()
            val roomId = intent.getStringExtra(EXTRA_ROOM_ID)
            if (roomId?.isNotEmpty() == true) {
                navigator.openRoom(this, roomId)
            }
            finish()
        } else {
            args = parseArgs()
            if (args.clearCredentials || args.isUserLoggedOut || args.clearCache) {
                clearNotifications()
            }
            // Handle some wanted cleanup
            if (args.clearCache || args.clearCredentials) {
                doCleanUp()
            } else {
                startSyncing()
                startNextActivityAndFinish()
            }
        }
    }

    private fun startSyncing() {
        activeSessionHolder.getSafeActiveSession()?.startSyncing(this)
    }

    private fun clearNotifications() {
        // Dismiss all notifications
        notificationDrawerManager.clearAllEvents()

        // Also clear the dynamic shortcuts
        shortcutsHandler.clearShortcuts()

        // Also clear the alerts
        popupAlertManager.cancelAll()
    }

    private fun parseArgs(): MainActivityArgs {
        val argsFromIntent: MainActivityArgs? = intent.getParcelableExtraCompat(EXTRA_ARGS)
        Timber.w("Starting MainActivity with $argsFromIntent")

        return MainActivityArgs(
                clearCache = argsFromIntent?.clearCache ?: false,
                clearCredentials = argsFromIntent?.clearCredentials ?: false,
                ignoreLogoutServerError = argsFromIntent?.ignoreLogoutServerError ?: false,
                keepServerSession = argsFromIntent?.keepServerSession ?: false,
                isUserLoggedOut = argsFromIntent?.isUserLoggedOut ?: false,
                isAccountDeactivated = argsFromIntent?.isAccountDeactivated ?: false,
                isSoftLogout = argsFromIntent?.isSoftLogout ?: false
        )
    }

    private fun doCleanUp() {
        val session = activeSessionHolder.getSafeActiveSession()
        if (session == null) {
            startNextActivityAndFinish()
            return
        }

        val onboardingStore = session.vectorStore(this)
        when {
            args.isAccountDeactivated -> {
                lifecycleScope.launch {
                    // Just do the local cleanup
                    Timber.w("Account deactivated, start app")
                    // Preserved media lives in filesDir, which doLocalCleanup does not touch.
                    redactionPreservationService.clearForSignedOutUser(session.myUserId)
                    activeSessionHolder.clearActiveSession()
                    doLocalCleanup(clearPreferences = true, onboardingStore)
                    startNextActivityAndFinish()
                }
            }
            args.clearCredentials -> {
                signout(session, onboardingStore, ignoreServerError = args.ignoreLogoutServerError)
            }
            args.clearCache -> {
                lifecycleScope.launch {
                    session.clearCache()
                    // The redaction cache is a separate store the session wipe doesn't touch, and
                    // whether it goes with the app cache is the user's choice.
                    redactionCacheCleaner.onAppCacheCleared()
                    doLocalCleanup(clearPreferences = false, onboardingStore)
                    session.startSyncing(applicationContext)
                    startNextActivityAndFinish()
                }
            }
        }
    }

    private fun signout(
            session: Session,
            onboardingStore: VectorSessionStore,
            ignoreServerError: Boolean,
    ) {
        lifecycleScope.launch {
            try {
                session.signOutService().signOut(!args.isUserLoggedOut && !args.keepServerSession, ignoreServerError)
            } catch (failure: Throwable) {
                Timber.e(failure, "SIGN_OUT: error, propose to sign out anyway")
                displaySignOutFailedDialog(session, onboardingStore)
                return@launch
            }
            Timber.w("SIGN_OUT: success, start app")
            val signedOutSessionId = session.sessionId
            redactionPreservationService.clearForSignedOutUser(session.myUserId)
            activeSessionHolder.clearActiveSession()
            accountInfoCache.delete(signedOutSessionId)
            val remaining = authenticationService.getAllSessionParams()
            val switched = remaining.isNotEmpty() && runCatching {
                switchAccountUseCase.execute(remaining.first().credentials.sessionId())
            }.onFailure { Timber.e(it, "SIGN_OUT: failed to switch to remaining account, falling back to login") }
                    .isSuccess
            if (switched) {
                // The auto-switch target was never chosen under a VPN check: let the launch gate re-prompt
                vpnGate.clearAcknowledgement()
                doLocalCleanup(clearPreferences = false, onboardingStore)
                restartApp(this@MainActivity, MainActivityArgs())
            } else {
                doLocalCleanup(clearPreferences = true, onboardingStore)
                startNextActivityAndFinish()
            }
        }
    }

    override fun handleInvalidToken(globalError: GlobalError.InvalidToken) {
        // No op here
        Timber.w("Ignoring invalid token global error")
    }

    private suspend fun doLocalCleanup(clearPreferences: Boolean, vectorSessionStore: VectorSessionStore) {
        mediaCache.clearThumbnails()

        if (clearPreferences) {
            vectorPreferences.clearPreferences()
            uiStateRepository.reset()
            pinLocker.unlock()
            pinCodeHelper.deletePinCode()
            vectorSessionStore.clear()
            lockScreenKeyRepository.deleteSystemKey()
        }
        withContext(Dispatchers.IO) {
            // Also clear cache (Logs, etc...)
            deleteAllFiles(this@MainActivity.cacheDir)
        }
    }

    private fun displaySignOutFailedDialog(
            session: Session,
            onboardingStore: VectorSessionStore,
    ) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            MaterialAlertDialogBuilder(this)
                    .setTitle(CommonStrings.dialog_title_error)
                    .setMessage(CommonStrings.sign_out_failed_dialog_message)
                    .setPositiveButton(CommonStrings.sign_out_anyway) { _, _ ->
                        signout(session, onboardingStore, ignoreServerError = true)
                    }
                    .setNeutralButton(CommonStrings.global_retry) { _, _ ->
                        signout(session, onboardingStore, ignoreServerError = false)
                    }
                    .setNegativeButton(CommonStrings.action_cancel) { _, _ -> startNextActivityAndFinish(ignoreClearCredentials = true) }
                    .setCancelable(false)
                    .show()
        }
    }

    private fun startNextActivityAndFinish(ignoreClearCredentials: Boolean = false) {
        val intent = when {
            args.clearCredentials &&
                    !ignoreClearCredentials &&
                    (!args.isUserLoggedOut || args.isAccountDeactivated) -> {
                // User has explicitly asked to log out or deactivated his account
                navigator.openLogin(this, null)
                null
            }
            args.isSoftLogout -> {
                // The homeserver has invalidated the token, with a soft logout
                navigator.softLogout(this)
                null
            }
            args.isUserLoggedOut ->
                // the homeserver has invalidated the token (password changed, device deleted, other security reasons)
                SignedOutActivity.newIntent(this)
            activeSessionHolder.hasActiveSession() ->
                // We have a session.
                // Check it can be opened
                if (activeSessionHolder.getActiveSession().isOpenable) {
                    HomeActivity.newIntent(this, firstStartMainActivity = false, existingSession = true)
                } else {
                    // The token is still invalid
                    navigator.softLogout(this)
                    null
                }
            else -> {
                // First start, or no active session
                navigator.openLogin(this, null)
                null
            }
        }
        startIntentAndFinish(intent)
    }

    private fun startIntentAndFinish(intent: Intent?) {
        intent?.let { startActivity(it) }
        finish()
    }

    private fun Intent.isValid(): Boolean {
        val componentName = resolveActivity(packageManager) ?: return false
        val packageName = componentName.packageName
        val className = componentName.className
        return packageName == buildMeta.applicationId && className in allowList
    }

    override val rootView: View
        get() = views.mainRoot
}
