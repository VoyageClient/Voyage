/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home

import com.airbnb.mvrx.Mavericks
import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.ViewModelContext
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.dispatchers.CoroutineDispatchers
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.core.pushers.EnsureFcmTokenIsRetrievedUseCase
import im.vector.app.core.pushers.PushersManager
import im.vector.app.core.pushers.RegisterUnifiedPushUseCase
import im.vector.app.core.pushers.UnregisterUnifiedPushUseCase
import im.vector.app.core.session.EnsureSessionSyncingUseCase
import im.vector.app.features.login.ReAuthHelper
import im.vector.app.features.onboarding.AuthenticationDescription
import im.vector.app.features.raw.wellknown.ElementWellKnown
import im.vector.app.features.raw.wellknown.getElementWellknown
import im.vector.app.features.raw.wellknown.isSecureBackupRequired
import im.vector.app.features.raw.wellknown.withElementWellKnown
import im.vector.app.features.session.coroutineScope
import im.vector.app.features.settings.VectorPreferences
import im.vector.lib.core.utils.compat.getParcelableExtraCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.auth.UIABaseAuth
import org.matrix.android.sdk.api.auth.UserInteractiveAuthInterceptor
import org.matrix.android.sdk.api.auth.UserPasswordAuth
import org.matrix.android.sdk.api.auth.data.LoginFlowTypes
import org.matrix.android.sdk.api.auth.registration.RegistrationFlowResponse
import org.matrix.android.sdk.api.auth.registration.nextUncompletedStage
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.raw.RawService
import org.matrix.android.sdk.api.session.crypto.crosssigning.CrossSigningService
import org.matrix.android.sdk.api.session.getUserOrDefault
import org.matrix.android.sdk.api.session.sync.SyncRequestState
import org.matrix.android.sdk.api.settings.LightweightSettingsStorage
import org.matrix.android.sdk.api.util.toMatrixItem
import org.matrix.android.sdk.flow.flow
import timber.log.Timber
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HomeActivityViewModel @AssistedInject constructor(
        @Assisted private val initialState: HomeActivityViewState,
        private val activeSessionHolder: ActiveSessionHolder,
        private val rawService: RawService,
        private val reAuthHelper: ReAuthHelper,
        private val lightweightSettingsStorage: LightweightSettingsStorage,
        private val vectorPreferences: VectorPreferences,
        private val pushersManager: PushersManager,
        private val registerUnifiedPushUseCase: RegisterUnifiedPushUseCase,
        private val unregisterUnifiedPushUseCase: UnregisterUnifiedPushUseCase,
        private val ensureFcmTokenIsRetrievedUseCase: EnsureFcmTokenIsRetrievedUseCase,
        private val ensureSessionSyncingUseCase: EnsureSessionSyncingUseCase,
        private val coroutineDispatchers: CoroutineDispatchers,
) : VectorViewModel<HomeActivityViewState, HomeActivityViewActions, HomeActivityViewEvents>(initialState) {

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<HomeActivityViewModel, HomeActivityViewState> {
        override fun create(initialState: HomeActivityViewState): HomeActivityViewModel
    }

    companion object : MavericksViewModelFactory<HomeActivityViewModel, HomeActivityViewState> by hiltMavericksViewModelFactory() {
        override fun initialState(viewModelContext: ViewModelContext): HomeActivityViewState? {
            val activity: HomeActivity = viewModelContext.activity()
            val args: HomeActivityArgs? = activity.intent.getParcelableExtraCompat(Mavericks.KEY_ARG)
            return args?.let { HomeActivityViewState(authenticationDescription = it.authenticationDescription) }
                    ?: super.initialState(viewModelContext)
        }
    }

    private var isInitialized = false
    private var hasCheckedBootstrap = false
    private var onceTrusted = false

    private fun initialize() {
        if (isInitialized) return
        isInitialized = true
        // Ensure Session is syncing
        ensureSessionSyncingUseCase.execute()
        registerUnifiedPushIfNeeded()
        viewModelScope.launch(coroutineDispatchers.io) {
            cleanupFiles()
        }
        observeInitialSync()
        observeCrossSigningReset()
        promptForNotifications()
        initThreadsMigration()
    }

    private fun registerUnifiedPushIfNeeded() {
        if (vectorPreferences.areNotificationEnabledForDevice()) {
            registerUnifiedPush(distributor = "")
        } else {
            unregisterUnifiedPush()
        }
    }

    private fun registerUnifiedPush(distributor: String) {
        viewModelScope.launch {
            when (registerUnifiedPushUseCase.execute(distributor = distributor)) {
                is RegisterUnifiedPushUseCase.RegisterUnifiedPushResult.NeedToAskUserForDistributor -> {
                    _viewEvents.post(HomeActivityViewEvents.AskUserForPushDistributor)
                }
                RegisterUnifiedPushUseCase.RegisterUnifiedPushResult.Success -> {
                    ensureFcmTokenIsRetrievedUseCase.execute(pushersManager, registerPusher = vectorPreferences.areNotificationEnabledForDevice())
                }
            }
        }
    }

    private fun unregisterUnifiedPush() {
        viewModelScope.launch {
            unregisterUnifiedPushUseCase.execute(pushersManager)
        }
    }

    private fun promptForNotifications() {
        _viewEvents.post(HomeActivityViewEvents.ShowNotificationDialog)
    }

    private fun cleanupFiles() {
        // Mitigation: delete all cached decrypted files each time the application is started.
        activeSessionHolder.getSafeActiveSession()?.fileService()?.clearDecryptedCache()
    }

    private fun observeCrossSigningReset() {
        val safeActiveSession = activeSessionHolder.getSafeActiveSession() ?: return

        onceTrusted = safeActiveSession
                .cryptoService()
                .crossSigningService().allPrivateKeysKnown()

        safeActiveSession
                .flow()
                .liveCrossSigningInfo(safeActiveSession.myUserId)
                .onEach { info ->
                    val isVerified = info.getOrNull()?.isTrusted() ?: false
                    if (!isVerified && onceTrusted) {
                        rawService.withElementWellKnown(viewModelScope, safeActiveSession.sessionParams) {
                            sessionHasBeenUnverified(it)
                        }
                    }
                    onceTrusted = isVerified
                }
                .launchIn(viewModelScope)
    }

    /**
     * Handle threads migration. The migration includes:
     * - Notify users that had io.element.thread enabled from labs
     * - Re-Enable m.thread to those users (that they had enabled labs threads)
     * - Handle migration when threads are enabled by default
     */
    private fun initThreadsMigration() {
        // When we would like to enable threads for all users
//        if(vectorPreferences.shouldMigrateThreads()) {
//            vectorPreferences.setThreadMessagesEnabled()
//            lightweightSettingsStorage.setThreadMessagesEnabled(vectorPreferences.areThreadMessagesEnabled())
//        }

        when {
            !vectorPreferences.areThreadMessagesEnabled() && !vectorPreferences.wasThreadFlagChangedManually() -> {
                vectorPreferences.setThreadMessagesEnabled()
                lightweightSettingsStorage.setThreadMessagesEnabled(vectorPreferences.areThreadMessagesEnabled())
                // Clear Cache
                _viewEvents.post(HomeActivityViewEvents.MigrateThreads(checkSession = false))
            }
            // Notify users
            vectorPreferences.shouldNotifyUserAboutThreads() && vectorPreferences.areThreadMessagesEnabled() -> {
                Timber.i("----> Notify users about threads")
                // Notify the user if needed that we migrated to support m.thread
                // instead of io.element.thread so old thread messages will be displayed as normal timeline messages
                _viewEvents.post(HomeActivityViewEvents.NotifyUserForThreadsMigration)
                vectorPreferences.userNotifiedAboutThreads()
            }
            // Migrate users with enabled lab settings
            vectorPreferences.shouldNotifyUserAboutThreads() && vectorPreferences.shouldMigrateThreads() -> {
                Timber.i("----> Migrate threads with enabled labs")
                // If user had io.element.thread enabled then enable the new thread support,
                // clear cache to sync messages appropriately
                vectorPreferences.setThreadMessagesEnabled()
                lightweightSettingsStorage.setThreadMessagesEnabled(vectorPreferences.areThreadMessagesEnabled())
                // Clear Cache
                _viewEvents.post(HomeActivityViewEvents.MigrateThreads(checkSession = false))
            }
            // Enable all users
            vectorPreferences.shouldMigrateThreads() && vectorPreferences.areThreadMessagesEnabled() -> {
                Timber.i("----> Try to migrate threads")
                _viewEvents.post(HomeActivityViewEvents.MigrateThreads(checkSession = true))
            }
        }
    }

    private fun observeInitialSync() {
        val session = activeSessionHolder.getSafeActiveSession() ?: return

        session.syncService().getSyncRequestStateFlow()
                .onEach { status ->
                    when (status) {
                        is SyncRequestState.Idle -> {
                            maybeVerifyOrBootstrapCrossSigning()
                        }
                        else -> Unit
                    }

                    setState {
                        copy(
                                syncRequestState = status
                        )
                    }
                }
                .launchIn(viewModelScope)

        if (session.syncService().hasAlreadySynced()) {
            maybeVerifyOrBootstrapCrossSigning()
        }
    }

    private fun sessionHasBeenUnverified(elementWellKnown: ElementWellKnown?) {
        val session = activeSessionHolder.getSafeActiveSession() ?: return
        val isSecureBackupRequired = elementWellKnown?.isSecureBackupRequired() ?: false
        if (isSecureBackupRequired) {
            // If 4S is forced, force verification
            // for stability cancel all pending verifications?
            viewModelScope.launch {
                session.cryptoService().verificationService().getExistingVerificationRequests(session.myUserId).forEach {
                    session.cryptoService().verificationService().cancelVerificationRequest(it)
                }
            }
            _viewEvents.post(HomeActivityViewEvents.ForceVerification(false))
        } else {
            // cross signing keys have been reset
            // Trigger a popup to re-verify
            // Note: user can be unknown in case of logout

            // This is now handled by the banner in the HomeFragment
            // session.getUserOrDefault(session.myUserId)
            //         .toMatrixItem()
            //         .let { user ->
            //             _viewEvents.post(HomeActivityViewEvents.OnCrossSignedInvalidated(user))
            //         }
        }
    }

    private fun maybeVerifyOrBootstrapCrossSigning() {
        // The contents of this method should only run once
        if (hasCheckedBootstrap) return
        hasCheckedBootstrap = true

        // We do not use the viewModel context because we do not want to tie this action to activity view model
        activeSessionHolder.getSafeActiveSession()?.coroutineScope?.launch(Dispatchers.IO) {
            val session = activeSessionHolder.getSafeActiveSession() ?: return@launch Unit.also {
                Timber.w("## No session to init cross signing or bootstrap")
            }

            val elementWellKnown = rawService.getElementWellknown(session.sessionParams)
            val isSecureBackupRequired = elementWellKnown?.isSecureBackupRequired() ?: false

            // In case of account creation, it is already done before
            if (initialState.authenticationDescription is AuthenticationDescription.Register) {
                if (isSecureBackupRequired) {
                    _viewEvents.post(HomeActivityViewEvents.StartRecoverySetupFlow)
                } else {
                    val password = reAuthHelper.data ?: return@launch Unit.also {
                        Timber.w("No password to init cross signing")
                    }

                    // Silently initialize cross signing without 4S
                    // We do not use the viewModel context because we do not want to cancel this action
                    Timber.d("Initialize cross signing")
                    try {
                        session.cryptoService().crossSigningService().awaitCrossSigninInitialization { response, _ ->
                            resume(
                                    UserPasswordAuth(
                                            session = response.session,
                                            user = session.myUserId,
                                            password = password
                                    )
                            )
                        }
                    } catch (failure: Throwable) {
                        Timber.e(failure, "Failed to initialize cross signing")
                    }
                }
                return@launch
            }

            tryOrNull("## MaybeVerifyOrBootstrapCrossSigning: Failed to download keys") {
                session.cryptoService().downloadKeysIfNeeded(listOf(session.myUserId), true)
            }

            // From there we are up to date with server
            // Is there already cross signing keys here?
            val mxCrossSigningInfo = session.cryptoService().crossSigningService().getMyCrossSigningKeys()
            if (mxCrossSigningInfo != null) {
                if (isSecureBackupRequired && !session.sharedSecretStorageService().isRecoverySetup()) {
                    // If 4S is forced, start the full interactive setup flow
                    _viewEvents.post(HomeActivityViewEvents.StartRecoverySetupFlow)
                } else {
                    // Cross-signing is already set up for this user, is it trusted?
                    if (!mxCrossSigningInfo.isTrusted()) {
                        if (isSecureBackupRequired) {
                            // If 4S is forced, force verification
                            _viewEvents.post(HomeActivityViewEvents.ForceVerification(true))
                        } else {
                            // we wan't to check if there is a way to actually verify this session,
                            // that means that there is another session to verify against, or
                            // secure backup is setup
                            val hasTargetDeviceToVerifyAgainst = session
                                    .cryptoService()
                                    .getUserDevices(session.myUserId)
                                    .size >= 2 // this one + another
                            val is4Ssetup = session.sharedSecretStorageService().isRecoverySetup()
                            if (hasTargetDeviceToVerifyAgainst || is4Ssetup) {
                                // New session
                                // This is now handled by the banner in the HomeFragment
                                // _viewEvents.post(
                                //         HomeActivityViewEvents.CurrentSessionNotVerified(
                                //                 session.getUserOrDefault(session.myUserId).toMatrixItem(),
                                //                 vectorPreferences.isOnRustCrypto() && vectorPreferences.hadExistingLegacyData()
                                //         )
                                // )
                            } else {
                                _viewEvents.post(
                                        HomeActivityViewEvents.CurrentSessionCannotBeVerified(
                                                session.getUserOrDefault(session.myUserId).toMatrixItem(),
                                        )
                                )
                            }
                        }
                    }
                }
            } else {
                // Cross signing is not initialized
                if (isSecureBackupRequired) {
                    // If 4S is forced, start the full interactive setup flow
                    _viewEvents.post(HomeActivityViewEvents.StartRecoverySetupFlow)
                } else {
                    // Initialize cross-signing silently
                    val password = reAuthHelper.data

                    if (password == null) {
                        // Check this is not an SSO account
                        if (session.homeServerCapabilitiesService().getHomeServerCapabilities().canChangePassword) {
                            // Ask password to the user: Upgrade security
                            _viewEvents.post(HomeActivityViewEvents.AskPasswordToInitCrossSigning(session.getUserOrDefault(session.myUserId).toMatrixItem()))
                        }
                        // Else (SSO) just ignore for the moment
                    } else {
                        // Try to initialize cross signing in background if possible
                        Timber.d("Initialize cross signing...")
                        try {
                            session.cryptoService().crossSigningService().awaitCrossSigninInitialization { response, errCode ->
                                // We missed server grace period or it's not setup, see if we remember locally password
                                if (response.nextUncompletedStage() == LoginFlowTypes.PASSWORD &&
                                        errCode == null &&
                                        reAuthHelper.data != null) {
                                    resume(
                                            UserPasswordAuth(
                                                    session = response.session,
                                                    user = session.myUserId,
                                                    password = reAuthHelper.data
                                            )
                                    )
                                    Timber.d("Initialize cross signing SUCCESS")
                                } else {
                                    resumeWithException(Exception("Cannot silently initialize cross signing, UIA missing"))
                                }
                            }
                        } catch (failure: Throwable) {
                            Timber.e(failure, "Failed to initialize cross signing")
                        }
                    }
                }
            }
        }
    }

    override fun handle(action: HomeActivityViewActions) {
        when (action) {
            HomeActivityViewActions.ViewStarted -> {
                initialize()
            }
            is HomeActivityViewActions.RegisterPushDistributor -> {
                registerUnifiedPush(distributor = action.distributor)
            }
        }
    }
}

private suspend fun CrossSigningService.awaitCrossSigninInitialization(
        block: Continuation<UIABaseAuth>.(response: RegistrationFlowResponse, errCode: String?) -> Unit
) {
        initializeCrossSigning(
                object : UserInteractiveAuthInterceptor {
                    override fun performStage(flowResponse: RegistrationFlowResponse, errCode: String?, promise: Continuation<UIABaseAuth>) {
                        promise.block(flowResponse, errCode)
                    }
                }
        )
}
