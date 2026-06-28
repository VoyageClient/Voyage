/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomdirectory.createroom

import androidx.core.net.toFile
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.Uninitialized
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.SpaceStateHandler
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.platform.VectorViewModel
import com.squareup.moshi.Types
import im.vector.app.features.raw.wellknown.getElementWellknown
import im.vector.app.features.raw.wellknown.isE2EByDefault
import im.vector.app.features.settings.VectorPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.MatrixPatterns.getServerName
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.raw.RawService
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoomSummary
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilities
import org.matrix.android.sdk.api.session.room.alias.RoomAliasError
import org.matrix.android.sdk.api.session.room.failure.CreateRoomFailure
import org.matrix.android.sdk.api.session.room.model.PowerLevelsContent
import org.matrix.android.sdk.api.session.room.model.RoomDirectoryVisibility
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import org.matrix.android.sdk.api.session.room.model.RoomJoinRulesAllowEntry
import org.matrix.android.sdk.api.session.room.model.RoomType
import org.matrix.android.sdk.api.session.room.model.create.CreateRoomParams
import org.matrix.android.sdk.api.session.room.model.create.CreateRoomPreset
import org.matrix.android.sdk.api.session.room.model.create.CreateRoomStateEvent
import org.matrix.android.sdk.api.session.room.model.create.RestrictedRoomPreset
import org.matrix.android.sdk.api.util.MatrixJsonParser
import timber.log.Timber

class CreateRoomViewModel @AssistedInject constructor(
        @Assisted private val initialState: CreateRoomViewState,
        private val session: Session,
        private val rawService: RawService,
        spaceStateHandler: SpaceStateHandler,
        private val vectorPreferences: VectorPreferences,
) : VectorViewModel<CreateRoomViewState, CreateRoomAction, CreateRoomViewEvents>(initialState) {

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<CreateRoomViewModel, CreateRoomViewState> {
        override fun create(initialState: CreateRoomViewState): CreateRoomViewModel
    }

    companion object : MavericksViewModelFactory<CreateRoomViewModel, CreateRoomViewState> by hiltMavericksViewModelFactory()

    init {
        initHomeServerName()
        initAdminE2eByDefault()

        val parentSpaceId = initialState.parentSpaceId ?: spaceStateHandler.getSafeActiveSpaceId()

        val homeServerCapabilities = session.homeServerCapabilitiesService().getHomeServerCapabilities()
        val createDefaultRoomVersion = homeServerCapabilities.roomVersions?.defaultRoomVersion
        val restrictedSupport = homeServerCapabilities.isFeatureSupported(HomeServerCapabilities.ROOM_CAP_RESTRICTED)
        val createRestricted = restrictedSupport == HomeServerCapabilities.RoomCapabilitySupport.SUPPORTED ||
                HomeServerCapabilities.roomVersionAtLeast(createDefaultRoomVersion, HomeServerCapabilities.ROOM_VERSION_RESTRICTED)

        val createKnock = (createDefaultRoomVersion?.let {
            homeServerCapabilities.isFeatureSupported(HomeServerCapabilities.ROOM_CAP_KNOCK, it)
        } ?: false) ||
                HomeServerCapabilities.roomVersionAtLeast(createDefaultRoomVersion, HomeServerCapabilities.ROOM_VERSION_KNOCK)

        val defaultJoinRules = if (parentSpaceId != null && createRestricted) {
            RoomJoinRules.RESTRICTED
        } else {
            RoomJoinRules.INVITE
        }

        val roomVersions = session.homeServerCapabilitiesService().getHomeServerCapabilities().roomVersions
        val defaultRoomVersion = roomVersions?.defaultRoomVersion
        // Offer every integer version from 1 up to the highest the server supports; never offer a newer one.
        val maxVersion = roomVersions?.supportedVersion.orEmpty()
                .mapNotNull { it.version.toIntOrNull() }
                .plusElement(defaultRoomVersion?.toIntOrNull() ?: 0)
                .maxOrNull()
                ?.coerceAtLeast(1)
                ?: 1
        val availableRoomVersions = (1..maxVersion).map { it.toString() }

        setState {
            copy(
                    parentSpaceId = parentSpaceId,
                    supportsRestricted = createRestricted,
                    supportsKnock = createKnock,
                    roomJoinRules = defaultJoinRules,
                    parentSpaceSummary = parentSpaceId?.let { session.getRoomSummary(it) },
                    defaultRoomVersion = defaultRoomVersion,
                    roomVersion = defaultRoomVersion,
                    availableRoomVersions = availableRoomVersions,
                    isDeveloperMode = vectorPreferences.developerMode()
            )
        }
    }

    private fun initHomeServerName() {
        setState {
            copy(
                    homeServerName = session.myUserId.getServerName()
            )
        }
    }

    private var adminE2EByDefault = true

    private fun initAdminE2eByDefault() {
        viewModelScope.launch(Dispatchers.IO) {
            adminE2EByDefault = tryOrNull {
                rawService.getElementWellknown(session.sessionParams)
                        ?.isE2EByDefault()
                        ?: true
            } ?: true

            setState {
                copy(
                        hsAdminHasDisabledE2E = !adminE2EByDefault,
                        defaultEncrypted = mapOf(
                                RoomJoinRules.INVITE to adminE2EByDefault,
                                RoomJoinRules.PUBLIC to false,
                                RoomJoinRules.RESTRICTED to adminE2EByDefault
                        )

                )
            }
        }
    }

    override fun handle(action: CreateRoomAction) {
        when (action) {
            is CreateRoomAction.SetAvatar -> setAvatar(action)
            is CreateRoomAction.SetName -> setName(action)
            is CreateRoomAction.SetTopic -> setTopic(action)
            is CreateRoomAction.SetVisibility -> setVisibility(action)
            is CreateRoomAction.SetRoomAliasLocalPart -> setRoomAliasLocalPart(action)
            is CreateRoomAction.SetIsEncrypted -> setIsEncrypted(action)
            is CreateRoomAction.Create -> doCreateRoom()
            CreateRoomAction.Reset -> doReset()
            CreateRoomAction.ToggleShowAdvanced -> toggleShowAdvanced()
            is CreateRoomAction.DisableFederation -> disableFederation(action)
            is CreateRoomAction.SetRoomVersion -> setState {
                val newState = copy(roomVersion = action.version)
                // Drop any self power level override if the new version no longer allows it (v12+ immutable creators)
                if (newState.canOverrideOwnPowerLevel) newState else newState.copy(myPowerLevelOverride = null)
            }
            is CreateRoomAction.SetMyPowerLevel -> setState { copy(myPowerLevelOverride = action.powerLevel) }
            is CreateRoomAction.SetInitialStateJson -> setState {
                copy(initialStateJson = action.json, initialStateJsonInvalid = false)
            }
        }
    }

    private fun disableFederation(action: CreateRoomAction.DisableFederation) {
        setState {
            copy(disableFederation = action.disableFederation)
        }
    }

    private fun toggleShowAdvanced() {
        setState {
            val hiding = showAdvanced
            copy(
                    showAdvanced = !showAdvanced,
                    // Reset advanced options when the section is hidden
                    disableFederation = disableFederation && !hiding,
                    roomVersion = if (hiding) defaultRoomVersion else roomVersion,
                    myPowerLevelOverride = if (hiding) null else myPowerLevelOverride,
                    initialStateJson = if (hiding) "" else initialStateJson,
                    initialStateJsonInvalid = false
            )
        }
    }

    private fun doReset() {
        setState {
            // Delete temporary file with the avatar
            avatarUri?.let { tryOrNull { it.toFile().delete() } }

            CreateRoomViewState(
                    isEncrypted = adminE2EByDefault,
                    hsAdminHasDisabledE2E = !adminE2EByDefault,
                    parentSpaceId = this.parentSpaceId
            )
        }

        _viewEvents.post(CreateRoomViewEvents.Quit)
    }

    private fun setAvatar(action: CreateRoomAction.SetAvatar) = setState { copy(avatarUri = action.imageUri) }

    private fun setName(action: CreateRoomAction.SetName) = setState { copy(roomName = action.name) }

    private fun setTopic(action: CreateRoomAction.SetTopic) = setState { copy(roomTopic = action.topic) }

    private fun setVisibility(action: CreateRoomAction.SetVisibility) = setState {
        when (action.rule) {
            RoomJoinRules.PUBLIC -> {
                copy(
                        roomJoinRules = RoomJoinRules.PUBLIC,
                        // Reset any error in the form about alias
                        asyncCreateRoomRequest = Uninitialized,
                        isEncrypted = false
                )
            }
            RoomJoinRules.RESTRICTED -> {
                copy(
                        roomJoinRules = RoomJoinRules.RESTRICTED,
                        // Reset any error in the form about alias
                        asyncCreateRoomRequest = Uninitialized,
                        isEncrypted = adminE2EByDefault
                )
            }
            RoomJoinRules.KNOCK -> {
                copy(
                        roomJoinRules = RoomJoinRules.KNOCK,
                        // Reset any error in the form about alias
                        asyncCreateRoomRequest = Uninitialized,
                        isEncrypted = adminE2EByDefault
                )
            }
//            RoomJoinRules.INVITE,
//            RoomJoinRules.PRIVATE,
            else -> {
                // default to invite
                copy(
                        roomJoinRules = RoomJoinRules.INVITE,
                        isEncrypted = adminE2EByDefault
                )
            }
        }
    }

    private fun setRoomAliasLocalPart(action: CreateRoomAction.SetRoomAliasLocalPart) {
        setState {
            copy(
                    aliasLocalPart = action.aliasLocalPart,
                    // Reset any error in the form about alias
                    asyncCreateRoomRequest = Uninitialized
            )
        }
    }

    private fun setIsEncrypted(action: CreateRoomAction.SetIsEncrypted) = setState { copy(isEncrypted = action.isEncrypted) }

    private fun doCreateRoom() = withState { state ->
        if (state.asyncCreateRoomRequest is Loading || state.asyncCreateRoomRequest is Success) {
            return@withState
        }

        if (state.roomJoinRules == RoomJoinRules.PUBLIC && state.aliasLocalPart.isNullOrBlank()) {
            // we require an alias for public rooms
            setState {
                copy(asyncCreateRoomRequest = Fail(CreateRoomFailure.AliasError(RoomAliasError.AliasIsBlank)))
            }
            return@withState
        }

        val customInitialStates = if (state.isDeveloperMode && state.initialStateJson.isNotBlank()) {
            parseInitialStateJson(state.initialStateJson) ?: run {
                setState { copy(initialStateJsonInvalid = true) }
                return@withState
            }
        } else {
            emptyList()
        }

        setState {
            copy(asyncCreateRoomRequest = Loading())
        }

        val createRoomParams = CreateRoomParams()
                .apply {
                    name = state.roomName.takeIf { it.isNotBlank() }
                    topic = state.roomTopic.takeIf { it.isNotBlank() }
                    avatarUri = state.avatarUri

                    if (state.isSubSpace) {
                        // Space-rooms are distinguished from regular messaging rooms by the m.room.type of m.space
                        roomType = RoomType.SPACE

                        // Space-rooms should be created with a power level for events_default of 100,
                        // to prevent the rooms accidentally/maliciously clogging up with messages from random members of the space.
                        powerLevelContentOverride = PowerLevelsContent(
                                eventsDefault = 100
                        )
                    }

                    when (state.roomJoinRules) {
                        RoomJoinRules.PUBLIC -> {
                            // Directory visibility
                            visibility = RoomDirectoryVisibility.PUBLIC
                            // Preset
                            preset = CreateRoomPreset.PRESET_PUBLIC_CHAT
                            roomAliasName = state.aliasLocalPart
                        }
                        RoomJoinRules.RESTRICTED -> {
                            state.parentSpaceId?.let {
                                featurePreset = RestrictedRoomPreset(
                                        session.homeServerCapabilitiesService().getHomeServerCapabilities(),
                                        listOf(RoomJoinRulesAllowEntry.restrictedToRoom(state.parentSpaceId))
                                )
                            }
                        }
                        RoomJoinRules.KNOCK -> {
                            // No dedicated preset for knock; start from a private chat and override the join rule.
                            visibility = RoomDirectoryVisibility.PRIVATE
                            preset = CreateRoomPreset.PRESET_PRIVATE_CHAT
                            initialStates.add(
                                    CreateRoomStateEvent(
                                            type = EventType.STATE_ROOM_JOIN_RULES,
                                            content = mapOf("join_rule" to RoomJoinRules.KNOCK.value)
                                    )
                            )
                        }
//                        RoomJoinRules.PRIVATE    ->
//                        RoomJoinRules.INVITE
                        else -> {
                            // by default create invite only
                            // Directory visibility
                            visibility = RoomDirectoryVisibility.PRIVATE
                            // Preset
                            preset = CreateRoomPreset.PRESET_PRIVATE_CHAT
                        }
                    }
                    // Disabling federation
                    disableFederation = state.disableFederation

                    // Encryption
                    val shouldEncrypt = when (state.roomJoinRules) {
                        // we ignore the isEncrypted for public room as the switch is hidden in this case
                        RoomJoinRules.PUBLIC -> false
                        else -> state.isEncrypted ?: state.defaultEncrypted[state.roomJoinRules].orFalse()
                    }
                    if (shouldEncrypt) {
                        enableEncryption()
                    }

                    // Advanced: room version
                    state.roomVersion?.takeIf { it != state.defaultRoomVersion }?.let {
                        roomVersion = it
                    }

                    // Advanced (developer): arbitrary initial state events.
                    // If the user also set their own power level, merge it into a power_levels event present in the
                    // custom initial state (our value wins), otherwise apply it via powerLevelContentOverride.
                    val myLevel = state.myPowerLevelOverride?.takeIf { state.canOverrideOwnPowerLevel }
                    val hasCustomPowerLevels = customInitialStates.any { it.type == EventType.STATE_ROOM_POWER_LEVELS }

                    if (myLevel != null && !hasCustomPowerLevels) {
                        powerLevelContentOverride = (powerLevelContentOverride ?: PowerLevelsContent())
                                .setUserPowerLevel(session.myUserId, myLevel)
                    }

                    if (customInitialStates.isNotEmpty()) {
                        initialStates.addAll(
                                if (myLevel != null && hasCustomPowerLevels) {
                                    customInitialStates.map { it.withMyPowerLevelMerged(session.myUserId, myLevel) }
                                } else {
                                    customInitialStates
                                }
                        )
                    }
                }

        // TODO Should this be non-cancellable?
        viewModelScope.launch {
            runCatching { session.roomService().createRoom(createRoomParams) }.fold(
                    { roomId ->
                        if (state.parentSpaceId != null) {
                            // add it as a child
                            try {
                                session.spaceService()
                                        .getSpace(state.parentSpaceId)
                                        ?.addChildren(roomId, viaServers = null, order = null)
                            } catch (failure: Throwable) {
                                Timber.w(failure, "Failed to add as a child")
                            }
                        }

                        setState {
                            copy(asyncCreateRoomRequest = Success(roomId))
                        }
                    },
                    { failure ->
                        setState {
                            copy(asyncCreateRoomRequest = Fail(failure))
                        }
                        _viewEvents.post(CreateRoomViewEvents.Failure(failure))
                    }
            )
        }
    }

    private fun CreateRoomStateEvent.withMyPowerLevelMerged(userId: String, level: Int): CreateRoomStateEvent {
        if (type != EventType.STATE_ROOM_POWER_LEVELS) return this
        @Suppress("UNCHECKED_CAST")
        val users = (content["users"] as? Map<String, Any>).orEmpty().toMutableMap().apply { put(userId, level) }
        return copy(content = content.toMutableMap().apply { put("users", users) })
    }

    private fun parseInitialStateJson(json: String): List<CreateRoomStateEvent>? {
        return tryOrNull {
            val type = Types.newParameterizedType(List::class.java, CreateRoomStateEvent::class.java)
            MatrixJsonParser.getMoshi().adapter<List<CreateRoomStateEvent>>(type).fromJson(json)
        }
    }
}
