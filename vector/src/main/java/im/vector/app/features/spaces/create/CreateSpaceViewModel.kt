/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.spaces.create

import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.Uninitialized
import com.airbnb.mvrx.ViewModelContext
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.roomdirectory.createroom.canOverrideOwnPowerLevel
import im.vector.app.features.roomdirectory.createroom.creatableRoomVersions
import im.vector.app.features.roomdirectory.createroom.parseInitialStateJson
import im.vector.app.features.settings.VectorPreferences
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.MatrixPatterns
import org.matrix.android.sdk.api.MatrixPatterns.getServerName
import org.matrix.android.sdk.api.extensions.isEmail
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilities
import org.matrix.android.sdk.api.session.identity.IdentityServiceListener
import org.matrix.android.sdk.api.session.room.AliasAvailabilityResult
import org.matrix.android.sdk.api.session.room.failure.CreateRoomFailure
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import org.matrix.android.sdk.api.session.room.model.create.CreateRoomStateEvent

class CreateSpaceViewModel @AssistedInject constructor(
        @Assisted initialState: CreateSpaceState,
        private val session: Session,
        private val stringProvider: StringProvider,
        private val createSpaceViewModelTask: CreateSpaceViewModelTask,
        private val errorFormatter: ErrorFormatter,
        private val vectorPreferences: VectorPreferences,
) : VectorViewModel<CreateSpaceState, CreateSpaceAction, CreateSpaceEvents>(initialState) {

    private val identityService = session.identityService()

    private val identityServerManagerListener = object : IdentityServiceListener {
        override fun onIdentityServerChange() {
            val identityServerUrl = identityService.getCurrentIdentityServerUrl()
            setState {
                copy(
                        canInviteByMail = identityServerUrl != null
                )
            }
        }
    }

    init {
        val identityServerUrl = identityService.getCurrentIdentityServerUrl()
        val homeServerCapabilities = session.homeServerCapabilitiesService().getHomeServerCapabilities()
        val defaultRoomVersion = homeServerCapabilities.roomVersions?.defaultRoomVersion
        val supportsKnock = (defaultRoomVersion?.let {
            homeServerCapabilities.isFeatureSupported(HomeServerCapabilities.ROOM_CAP_KNOCK, it)
        } ?: false) ||
                HomeServerCapabilities.roomVersionAtLeast(defaultRoomVersion, HomeServerCapabilities.ROOM_VERSION_KNOCK)
        setState {
            copy(
                    homeServerName = session.myUserId.getServerName(),
                    canInviteByMail = identityServerUrl != null,
                    defaultRoomVersion = defaultRoomVersion,
                    roomVersion = defaultRoomVersion,
                    availableRoomVersions = homeServerCapabilities.creatableRoomVersions(),
                    isDeveloperMode = vectorPreferences.developerMode(),
                    supportsKnock = supportsKnock,
            )
        }
        startListenToIdentityManager()
    }

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<CreateSpaceViewModel, CreateSpaceState> {
        override fun create(initialState: CreateSpaceState): CreateSpaceViewModel
    }

    private fun startListenToIdentityManager() {
        identityService.addListener(identityServerManagerListener)
    }

    private fun stopListenToIdentityManager() {
        identityService.removeListener(identityServerManagerListener)
    }

    override fun onCleared() {
        stopListenToIdentityManager()
        super.onCleared()
    }

    companion object : MavericksViewModelFactory<CreateSpaceViewModel, CreateSpaceState> by hiltMavericksViewModelFactory() {

        override fun initialState(viewModelContext: ViewModelContext): CreateSpaceState {
            return CreateSpaceState(
                    defaultRooms = mapOf(
                            0 to viewModelContext.activity.getString(CommonStrings.create_spaces_default_public_room_name),
                            1 to viewModelContext.activity.getString(CommonStrings.create_spaces_default_public_random_room_name)
                    )
            )
        }
    }

    override fun handle(action: CreateSpaceAction) {
        when (action) {
            is CreateSpaceAction.NameChanged -> {
                setState {
                    if (aliasManuallyModified) {
                        copy(
                                nameInlineError = null,
                                name = action.name,
                                aliasVerificationTask = Uninitialized
                        )
                    } else {
                        val tentativeAlias =
                                MatrixPatterns.candidateAliasFromRoomName(action.name, homeServerName)
                        copy(
                                nameInlineError = null,
                                name = action.name,
                                aliasLocalPart = tentativeAlias,
                                aliasVerificationTask = Uninitialized
                        )
                    }
                }
            }
            is CreateSpaceAction.TopicChanged -> {
                setState {
                    copy(
                            topic = action.topic
                    )
                }
            }
            is CreateSpaceAction.SpaceAliasChanged -> {
                // This called only when the alias is change manually
                // not when programmatically changed via a change on name
                setState {
                    copy(
                            aliasManuallyModified = true,
                            aliasLocalPart = action.aliasLocalPart,
                            aliasVerificationTask = Uninitialized
                    )
                }
            }
            CreateSpaceAction.OnBackPressed -> {
                handleBackNavigation()
            }
            CreateSpaceAction.NextFromDetails -> {
                handleNextFromDetails()
            }
            CreateSpaceAction.NextFromDefaultRooms -> {
                handleNextFromDefaultRooms()
            }
            CreateSpaceAction.NextFromAdd3pid -> {
                handleNextFrom3pid()
            }
            is CreateSpaceAction.DefaultRoomNameChanged -> {
                setState {
                    copy(
                            defaultRooms = defaultRooms.orEmpty().toMutableMap().apply {
                                this[action.index] = action.name
                            }
                    )
                }
            }
            is CreateSpaceAction.DefaultInvite3pidChanged -> {
                setState {
                    copy(
                            default3pidInvite = default3pidInvite.orEmpty().toMutableMap().apply {
                                this[action.index] = action.email
                            },
                            emailValidationResult = emailValidationResult.orEmpty().toMutableMap().apply {
                                this.remove(action.index)
                            }
                    )
                }
            }
            is CreateSpaceAction.SetAvatar -> {
                setState { copy(avatarUri = action.uri) }
            }
            is CreateSpaceAction.SetJoinRule -> {
                setState {
                    copy(
                            joinRule = action.joinRule,
                            // Nothing to hide in a space anyone can walk into.
                            isEncrypted = isEncrypted && action.joinRule != RoomJoinRules.PUBLIC,
                            aliasVerificationTask = Uninitialized,
                    )
                }
            }
            is CreateSpaceAction.SetIsEncrypted -> {
                setState { copy(isEncrypted = action.isEncrypted) }
            }
            CreateSpaceAction.ToggleShowAdvanced -> {
                setState {
                    val hiding = showAdvanced
                    copy(
                            showAdvanced = !hiding,
                            disableFederation = disableFederation && !hiding,
                            roomVersion = if (hiding) defaultRoomVersion else roomVersion,
                            myPowerLevelOverride = if (hiding) null else myPowerLevelOverride,
                            initialStateJson = if (hiding) "" else initialStateJson,
                            initialStateJsonInvalid = false,
                    )
                }
            }
            is CreateSpaceAction.SetDisableFederation -> {
                setState { copy(disableFederation = action.disableFederation) }
            }
            is CreateSpaceAction.SetRoomVersion -> {
                setState {
                    val newState = copy(roomVersion = action.version)
                    if (newState.canOverrideOwnPowerLevel) newState else newState.copy(myPowerLevelOverride = null)
                }
            }
            is CreateSpaceAction.SetMyPowerLevel -> {
                setState { copy(myPowerLevelOverride = action.powerLevel) }
            }
            is CreateSpaceAction.SetInitialStateJson -> {
                setState { copy(initialStateJson = action.json, initialStateJsonInvalid = false) }
            }
        }
    }

    private fun handleBackNavigation() = withState { state ->
        when (state.step) {
            CreateSpaceState.Step.SetDetails -> {
                _viewEvents.post(CreateSpaceEvents.Dismiss)
            }
            CreateSpaceState.Step.AddEmailsOrInvites -> {
                setState { copy(step = CreateSpaceState.Step.SetDetails) }
                _viewEvents.post(CreateSpaceEvents.NavigateToDetails)
            }
            CreateSpaceState.Step.AddRooms -> {
                if (state.showsInviteStep) {
                    setState { copy(step = CreateSpaceState.Step.AddEmailsOrInvites) }
                    _viewEvents.post(CreateSpaceEvents.NavigateToAdd3Pid)
                } else {
                    setState { copy(step = CreateSpaceState.Step.SetDetails) }
                    _viewEvents.post(CreateSpaceEvents.NavigateToDetails)
                }
            }
        }
    }

    private fun handleNextFrom3pid() = withState { state ->
        // check if emails are valid
        val emailValidation = state.default3pidInvite?.mapValues {
            val email = it.value
            email.isNullOrEmpty() || email.isEmail()
        }
        if (emailValidation?.all { it.value } != false) {
            setState {
                copy(
                        step = CreateSpaceState.Step.AddRooms
                )
            }
            _viewEvents.post(CreateSpaceEvents.NavigateToAddRooms)
        } else {
            setState {
                copy(
                        emailValidationResult = emailValidation
                )
            }
        }
    }

    /** Null when the user typed initial state that is not a JSON array of state events. */
    private fun customInitialStates(state: CreateSpaceState): List<CreateRoomStateEvent>? {
        if (!state.isDeveloperMode || state.initialStateJson.isBlank()) return emptyList()
        return parseInitialStateJson(state.initialStateJson)
    }

    private fun handleNextFromDetails() = withState { state ->
        if (state.name.isNullOrBlank()) {
            setState {
                copy(
                        nameInlineError = stringProvider.getString(CommonStrings.create_space_error_empty_field_space_name)
                )
            }
        } else if (customInitialStates(state) == null) {
            setState { copy(initialStateJsonInvalid = true) }
        } else if (!state.isPublic) {
            goToStepAfterDetails()
        } else {
            // A public space is reachable by its address, so it has to be free before going on.
            val aliasLocalPart = state.aliasLocalPart
            _viewEvents.post(CreateSpaceEvents.ShowModalLoading(null))
            setState {
                copy(aliasVerificationTask = Loading())
            }
            viewModelScope.launch {
                try {
                    when (val result = session.roomDirectoryService().checkAliasAvailability(aliasLocalPart)) {
                        AliasAvailabilityResult.Available -> {
                            _viewEvents.post(CreateSpaceEvents.HideModalLoading)
                            goToStepAfterDetails()
                        }
                        is AliasAvailabilityResult.NotAvailable -> {
                            setState {
                                copy(aliasVerificationTask = Fail(result.roomAliasError))
                            }
                            _viewEvents.post(CreateSpaceEvents.HideModalLoading)
                        }
                    }
                } catch (failure: Throwable) {
                    setState {
                        copy(aliasVerificationTask = Fail(failure))
                    }
                    _viewEvents.post(CreateSpaceEvents.HideModalLoading)
                }
            }
        }
    }

    private fun goToStepAfterDetails() = withState { state ->
        if (state.showsInviteStep) {
            setState { copy(step = CreateSpaceState.Step.AddEmailsOrInvites) }
            _viewEvents.post(CreateSpaceEvents.NavigateToAdd3Pid)
        } else {
            setState { copy(step = CreateSpaceState.Step.AddRooms) }
            _viewEvents.post(CreateSpaceEvents.NavigateToAddRooms)
        }
    }

    private fun handleNextFromDefaultRooms() = withState { state ->
        val spaceName = state.name ?: return@withState
        setState {
            copy(creationResult = Loading())
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val alias = state.aliasLocalPart.takeIf { state.isPublic }
                val result = createSpaceViewModelTask.execute(
                        CreateSpaceTaskParams(
                                advancedOptions = state,
                                customInitialStates = customInitialStates(state).orEmpty(),
                                spaceName = spaceName,
                                spaceTopic = state.topic,
                                spaceAvatar = state.avatarUri,
                                joinRule = state.joinRule,
                                isEncrypted = state.isEncrypted,
                                defaultRooms = state.defaultRooms
                                        ?.entries
                                        ?.sortedBy { it.key }
                                        ?.mapNotNull { it.value }
                                        .orEmpty(),
                                spaceAlias = alias,
                                defaultEmailToInvite = state.default3pidInvite
                                        ?.values
                                        ?.mapNotNull { it.takeIf { it?.isEmail() == true } }
                                        ?.takeIf { state.showsInviteStep }
                                        .orEmpty()
                        )
                )
                when (result) {
                    is CreateSpaceTaskResult.Success -> {
                        setState {
                            copy(creationResult = Success(result.spaceId))
                        }
                        _viewEvents.post(
                                CreateSpaceEvents.FinishSuccess(
                                        result.spaceId,
                                        result.childIds.firstOrNull(),
                                        state.isJustMe()
                                )
                        )
                    }
                    is CreateSpaceTaskResult.PartialSuccess -> {
                        // XXX what can we do here?
                        setState {
                            copy(creationResult = Success(result.spaceId))
                        }
                        _viewEvents.post(
                                CreateSpaceEvents.FinishSuccess(
                                        result.spaceId,
                                        result.childIds.firstOrNull(),
                                        state.isJustMe()
                                )
                        )
                    }
                    is CreateSpaceTaskResult.FailedToCreateSpace -> {
                        if (result.failure is CreateRoomFailure.AliasError) {
                            setState {
                                copy(
                                        step = CreateSpaceState.Step.SetDetails,
                                        aliasVerificationTask = Fail(result.failure.aliasError),
                                        creationResult = Uninitialized
                                )
                            }
                            _viewEvents.post(CreateSpaceEvents.HideModalLoading)
                            _viewEvents.post(CreateSpaceEvents.NavigateToDetails)
                        } else {
                            setState {
                                copy(creationResult = Fail(result.failure))
                            }
                            _viewEvents.post(CreateSpaceEvents.ShowModalError(errorFormatter.toHumanReadable(result.failure)))
                        }
                    }
                }
            } catch (failure: Throwable) {
                setState {
                    copy(creationResult = Fail(failure))
                }
                _viewEvents.post(CreateSpaceEvents.ShowModalError(errorFormatter.toHumanReadable(failure)))
            }
        }
    }
}
