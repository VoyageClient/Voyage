/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.settings.devices

import com.airbnb.mvrx.MavericksViewModelFactory
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.platform.EmptyViewEvents
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.core.platform.VectorViewModelAction
import im.vector.app.features.settings.devices.notification.GetNotificationsStatusUseCase
import im.vector.app.features.settings.devices.notification.ToggleNotificationsUseCase
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.crypto.model.DeviceInfo
import org.matrix.android.sdk.flow.flow

sealed class DeviceVerificationInfoAction : VectorViewModelAction {
    data class TogglePushNotifications(val deviceId: String, val enabled: Boolean) : DeviceVerificationInfoAction()
}

class DeviceVerificationInfoBottomSheetViewModel @AssistedInject constructor(
        @Assisted initialState: DeviceVerificationInfoBottomSheetViewState,
        val session: Session,
        private val getNotificationsStatusUseCase: GetNotificationsStatusUseCase,
        private val toggleNotificationsUseCase: ToggleNotificationsUseCase
) : VectorViewModel<DeviceVerificationInfoBottomSheetViewState, DeviceVerificationInfoAction, EmptyViewEvents>(initialState) {

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<DeviceVerificationInfoBottomSheetViewModel, DeviceVerificationInfoBottomSheetViewState> {
        override fun create(initialState: DeviceVerificationInfoBottomSheetViewState): DeviceVerificationInfoBottomSheetViewModel
    }

    companion object : MavericksViewModelFactory<DeviceVerificationInfoBottomSheetViewModel, DeviceVerificationInfoBottomSheetViewState>
    by hiltMavericksViewModelFactory()

    init {
        initState()
        session.flow().liveCrossSigningInfo(session.myUserId)
                .execute {
                    copy(
                            hasAccountCrossSigning = it.invoke()?.getOrNull() != null,
                            accountCrossSigningIsTrusted = it.invoke()?.getOrNull()?.isTrusted() == true
                    )
                }

        session.flow().liveUserCryptoDevices(session.myUserId)
                .map { list ->
                    list.firstOrNull { it.deviceId == initialState.deviceId }
                }
                .execute {
                    copy(
                            cryptoDeviceInfo = it,
                            isMine = it.invoke()?.deviceId == session.sessionParams.deviceId
                    )
                }

        session.flow().liveUserCryptoDevices(session.myUserId)
                .map { it.size }
                .execute {
                    copy(
                            hasOtherSessions = it.invoke() ?: 0 > 1
                    )
                }

        session.flow().liveMyDevicesInfo()
                .map { devices ->
                    devices.firstOrNull { it.deviceId == initialState.deviceId } ?: DeviceInfo(deviceId = initialState.deviceId)
                }
                .execute {
                    copy(deviceInfo = it)
                }

        getNotificationsStatusUseCase.execute(session, initialState.deviceId)
                .onEach { setState { copy(notificationsStatus = it) } }
                .launchIn(viewModelScope)
    }

    private fun initState() {
        viewModelScope.launch {
            val hasAccountCrossSigning = session.cryptoService().crossSigningService().isCrossSigningInitialized()
            val accountCrossSigningIsTrusted = session.cryptoService().crossSigningService().isCrossSigningVerified()
            val isRecoverySetup = session.sharedSecretStorageService().isRecoverySetup()
            setState {
                copy(
                        hasAccountCrossSigning = hasAccountCrossSigning,
                        accountCrossSigningIsTrusted = accountCrossSigningIsTrusted,
                        isRecoverySetup = isRecoverySetup
                )
            }
        }
    }

    override fun handle(action: DeviceVerificationInfoAction) {
        when (action) {
            is DeviceVerificationInfoAction.TogglePushNotifications -> {
                viewModelScope.launch {
                    toggleNotificationsUseCase.execute(action.deviceId, action.enabled)
                }
            }
        }
    }
}
