/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.settings.joinrule.advanced

import android.graphics.Typeface
import androidx.core.text.toSpannable
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.Uninitialized
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.utils.styleMatchingText
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.roomprofile.settings.joinrule.toOption
import im.vector.app.features.settings.VectorPreferences
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.getRoomSummary
import org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilities
import org.matrix.android.sdk.api.session.room.getStateEvent
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import org.matrix.android.sdk.api.session.room.model.RoomJoinRulesContent
import org.matrix.android.sdk.api.session.room.model.RoomType
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import org.matrix.android.sdk.api.util.MatrixItem
import org.matrix.android.sdk.api.util.toMatrixItem

class RoomJoinRuleChooseRestrictedViewModel @AssistedInject constructor(
        @Assisted initialState: RoomJoinRuleChooseRestrictedState,
        private val session: Session,
        private val vectorPreferences: VectorPreferences,
        private val stringProvider: StringProvider
) : VectorViewModel<RoomJoinRuleChooseRestrictedState, RoomJoinRuleChooseRestrictedActions, RoomJoinRuleChooseRestrictedEvents>(initialState) {

    var room = session.getRoom(initialState.roomId)!!

    init {
        viewModelScope.launch {
            initializeForRoom(initialState.roomId)
        }
    }

    private fun initializeForRoom(roomId: String) {
        room = session.getRoom(roomId)!!
        session.getRoomSummary(roomId)?.let { roomSummary ->
            val joinRulesContent = room.getStateEvent(EventType.STATE_ROOM_JOIN_RULES, QueryStringValue.IsEmpty)
                    ?.content
                    ?.toModel<RoomJoinRulesContent>()
            val initialAllowList = joinRulesContent?.allowList

            val knownParentSpacesAllowed = mutableListOf<MatrixItem>()
            val unknownAllowedOrRooms = mutableListOf<MatrixItem>()
            initialAllowList.orEmpty().forEach { entry ->
                val summary = entry.roomId?.let { session.getRoomSummary(it) }
                if (summary == null || // it's not known by me
                        summary.roomType != RoomType.SPACE || // it's not a space
                        !roomSummary.flattenParentIds.contains(summary.roomId) // it's not a parent space
                ) {
                    (summary?.toMatrixItem() ?: entry.roomId?.let { MatrixItem.RoomItem(it, null, null) })?.let {
                        unknownAllowedOrRooms.add(it)
                    }
                } else {
                    knownParentSpacesAllowed.add(summary.toMatrixItem())
                }
            }

            val possibleSpaceCandidate = knownParentSpacesAllowed.toMutableList()
            roomSummary.flattenParentIds.mapNotNull {
                session.getRoomSummary(it)?.toMatrixItem()
            }.forEach {
                if (!possibleSpaceCandidate.contains(it)) {
                    possibleSpaceCandidate.add(it)
                }
            }

            val homeServerCapabilities = session.homeServerCapabilitiesService().getHomeServerCapabilities()
            val roomVersion = room.roomVersionService().getRoomVersion()
            var safeRule: RoomJoinRules = joinRulesContent?.joinRules ?: RoomJoinRules.INVITE
            // server is not really checking that, just to be sure let's check
            val restrictedSupportedByThisVersion = homeServerCapabilities
                    .isFeatureSupported(HomeServerCapabilities.ROOM_CAP_RESTRICTED, roomVersion) ||
                    HomeServerCapabilities.roomVersionAtLeast(roomVersion, HomeServerCapabilities.ROOM_VERSION_RESTRICTED)
            if (safeRule == RoomJoinRules.RESTRICTED &&
                    !restrictedSupportedByThisVersion) {
                safeRule = RoomJoinRules.INVITE
            }

            val restrictedSupport = homeServerCapabilities.isFeatureSupported(HomeServerCapabilities.ROOM_CAP_RESTRICTED)
            val couldUpgradeToRestricted = restrictedSupport == HomeServerCapabilities.RoomCapabilitySupport.SUPPORTED

            val knockSupportedByThisVersion = homeServerCapabilities
                    .isFeatureSupported(HomeServerCapabilities.ROOM_CAP_KNOCK, roomVersion) ||
                    HomeServerCapabilities.roomVersionAtLeast(roomVersion, HomeServerCapabilities.ROOM_VERSION_KNOCK)

            val knockRestrictedSupportedByThisVersion = homeServerCapabilities
                    .isFeatureSupported(HomeServerCapabilities.ROOM_CAP_KNOCK_RESTRICTED, roomVersion) ||
                    HomeServerCapabilities.roomVersionAtLeast(roomVersion, HomeServerCapabilities.ROOM_VERSION_KNOCK_RESTRICTED)

            if (safeRule == RoomJoinRules.KNOCK_RESTRICTED && !knockRestrictedSupportedByThisVersion) {
                safeRule = RoomJoinRules.INVITE
            }

            val choices = buildList {
                add(RoomJoinRules.INVITE.toOption(false))
                if (restrictedSupportedByThisVersion || couldUpgradeToRestricted) {
                    add(RoomJoinRules.RESTRICTED.toOption(!restrictedSupportedByThisVersion))
                }
                if (knockSupportedByThisVersion) {
                    add(RoomJoinRules.KNOCK.toOption(false))
                }
                if (knockRestrictedSupportedByThisVersion) {
                    add(RoomJoinRules.KNOCK_RESTRICTED.toOption(false))
                }
                add(RoomJoinRules.PUBLIC.toOption(false))
            }

            setState {
                copy(
                        roomSummary = Success(roomSummary),
                        initialRoomJoinRules = safeRule,
                        currentRoomJoinRules = safeRule,
                        choices = choices,
                        initialAllowList = initialAllowList.orEmpty(),
                        updatedAllowList = initialAllowList.orEmpty().mapNotNull {
                            it.roomId?.let { roomId ->
                                session.getRoomSummary(roomId)?.toMatrixItem()
                                        ?: MatrixItem.RoomItem(roomId, null, null)
                            }
                        },
                        possibleSpaceCandidate = possibleSpaceCandidate,
                        unknownRestricted = unknownAllowedOrRooms,
                        restrictedSupportedByThisVersion = restrictedSupportedByThisVersion,
                        upgradeNeededForRestricted = !restrictedSupportedByThisVersion && couldUpgradeToRestricted,
                        restrictedVersionNeeded = homeServerCapabilities.versionOverrideForFeature(HomeServerCapabilities.ROOM_CAP_RESTRICTED),
                        knockSupportedByThisVersion = knockSupportedByThisVersion,
                        isSpace = roomSummary.roomType == RoomType.SPACE
                )
            }
        }
    }

    fun checkForChanges() = withState { state ->
        if (state.initialRoomJoinRules != state.currentRoomJoinRules) {
            setState {
                copy(hasUnsavedChanges = true)
            }
            return@withState
        }

        if (state.currentRoomJoinRules == RoomJoinRules.RESTRICTED || state.currentRoomJoinRules == RoomJoinRules.KNOCK_RESTRICTED) {
            val allowDidChange = state.initialAllowList.map { it.roomId } != state.updatedAllowList.map { it.id }
            setState {
                copy(hasUnsavedChanges = allowDidChange)
            }
            return@withState
        }

        setState {
            copy(hasUnsavedChanges = false)
        }
    }

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<RoomJoinRuleChooseRestrictedViewModel, RoomJoinRuleChooseRestrictedState> {
        override fun create(initialState: RoomJoinRuleChooseRestrictedState): RoomJoinRuleChooseRestrictedViewModel
    }

    override fun handle(action: RoomJoinRuleChooseRestrictedActions) {
        when (action) {
            is RoomJoinRuleChooseRestrictedActions.FilterWith -> handleFilter(action)
            is RoomJoinRuleChooseRestrictedActions.ToggleSelection -> handleToggleSelection(action)
            is RoomJoinRuleChooseRestrictedActions.SelectJoinRules -> handleSelectRule(action)
            is RoomJoinRuleChooseRestrictedActions.SwitchToRoomAfterMigration -> handleSwitchToRoom(action)
            RoomJoinRuleChooseRestrictedActions.DoUpdateJoinRules -> handleSubmit()
        }
        checkForChanges()
    }

    fun handleSubmit() = withState { state ->
        setState { copy(updatingStatus = Loading()) }

        viewModelScope.launch {
            try {
                when (state.currentRoomJoinRules) {
                    RoomJoinRules.PUBLIC -> room.stateService().setJoinRulePublic()
                    RoomJoinRules.INVITE -> room.stateService().setJoinRuleInviteOnly()
                    RoomJoinRules.KNOCK -> room.stateService().setJoinRuleKnock()
                    RoomJoinRules.RESTRICTED -> room.stateService().setJoinRuleRestricted(state.updatedAllowList.map { it.id })
                    RoomJoinRules.KNOCK_RESTRICTED -> room.stateService().setJoinRuleKnockRestricted(state.updatedAllowList.map { it.id })
                    RoomJoinRules.PRIVATE,
                    null -> {
                        throw UnsupportedOperationException()
                    }
                }
                setState { copy(updatingStatus = Success(Unit)) }
            } catch (failure: Throwable) {
                setState { copy(updatingStatus = Fail(failure)) }
            }
        }
    }

    fun handleSelectRule(action: RoomJoinRuleChooseRestrictedActions.SelectJoinRules) = withState { state ->
        val currentRoomJoinRules = state.currentRoomJoinRules

        val candidate = session.getRoomSummary(state.roomId)
                ?.flattenParentIds
                ?.filter {
                    session.getRoomSummary(it)?.spaceChildren?.firstOrNull { it.childRoomId == state.roomId } != null
                }?.mapNotNull {
                    session.getRoomSummary(it)?.toMatrixItem()
                }?.firstOrNull()
        val description = if (candidate != null) {
            stringProvider.getString(CommonStrings.upgrade_room_for_restricted, candidate.getBestName()).toSpannable().let {
                it.styleMatchingText(candidate.getBestName(), Typeface.BOLD)
            }
        } else {
            stringProvider.getString(CommonStrings.upgrade_room_for_restricted_no_param)
        }

        if (action.rules == RoomJoinRules.RESTRICTED && state.upgradeNeededForRestricted) {
            // let's show the room upgrade bottom sheet
            _viewEvents.post(
                    RoomJoinRuleChooseRestrictedEvents.NavigateToUpgradeRoom(
                            state.roomId,
                            state.restrictedVersionNeeded ?: "",
                            description
                    )
            )
            return@withState
        }

        val usesAllowList = action.rules in ALLOW_LIST_JOIN_RULES

        if (usesAllowList && currentRoomJoinRules !in ALLOW_LIST_JOIN_RULES) {
            // switching to restricted
            // if allow list is empty, then default to current space parents
            if (state.updatedAllowList.isEmpty()) {
                val candidates = session.getRoomSummary(state.roomId)
                        ?.flattenParentIds
                        ?.filter {
                            session.getRoomSummary(it)?.spaceChildren?.firstOrNull { it.childRoomId == state.roomId } != null
                        }?.mapNotNull {
                            session.getRoomSummary(it)?.toMatrixItem()
                        }.orEmpty()
                setState {
                    copy(updatedAllowList = candidates)
                }
            }
        }

        setState {
            copy(
                    currentRoomJoinRules = action.rules
            )
        }

        if (usesAllowList && action.rules == currentRoomJoinRules) {
            _viewEvents.post(RoomJoinRuleChooseRestrictedEvents.NavigateToChooseRestricted)
        }
    }

    private fun handleSwitchToRoom(action: RoomJoinRuleChooseRestrictedActions.SwitchToRoomAfterMigration) = withState { state ->
        viewModelScope.launch {
            val oldRoomSummary = session.getRoomSummary(state.roomId)
            val replacementRoomSummary = session.getRoomSummary(action.roomId)
            setState {
                copy(
                        roomId = action.roomId,
                        roomSummary = replacementRoomSummary?.let { Success(it) } ?: Uninitialized,
                        didSwitchToReplacementRoom = true
                )
            }
            initializeForRoom(action.roomId)
            // set as restricted now
            val candidates = oldRoomSummary
                    ?.flattenParentIds
                    ?.filter {
                        session.getRoomSummary(it)?.spaceChildren?.firstOrNull { it.childRoomId == state.roomId } != null
                    }?.mapNotNull {
                        session.getRoomSummary(it)?.toMatrixItem()
                    }.orEmpty()
            setState {
                copy(
                        currentRoomJoinRules = RoomJoinRules.RESTRICTED,
                        updatedAllowList = candidates
                )
            }
            setState { copy(updatingStatus = Loading()) }
            viewModelScope.launch {
                try {
                    room.stateService().setJoinRuleRestricted(candidates.map { it.id })
                    setState { copy(updatingStatus = Success(Unit)) }
                } catch (failure: Throwable) {
                    setState { copy(updatingStatus = Fail(failure)) }
                }
            }
        }
    }

    private fun handleToggleSelection(action: RoomJoinRuleChooseRestrictedActions.ToggleSelection) = withState { state ->
        val selection = state.updatedAllowList.toMutableList()
        if (selection.any { action.matrixItem.id == it.id }) {
            selection.removeAll { it.id == action.matrixItem.id }
        } else {
            selection.add(action.matrixItem)
        }
        val unknownAllowedOrRooms = mutableListOf<MatrixItem>()

        // we would like to keep initial allowed here to show them unchecked
        // to make it easier for users to spot the changes
        val union = mutableListOf<MatrixItem>().apply {
            addAll(
                    state.initialAllowList.mapNotNull {
                        it.roomId?.let { roomId ->
                            session.getRoomSummary(roomId)?.toMatrixItem()
                                    ?: MatrixItem.RoomItem(roomId, null, null)
                        }
                    }
            )
            addAll(selection)
        }.distinctBy { it.id }.sortedBy { it.id }

        union.forEach { entry ->
            val summary = session.getRoomSummary(entry.id)
            if (summary == null) {
                unknownAllowedOrRooms.add(
                        entry
                )
            } else if (summary.roomType != RoomType.SPACE) {
                unknownAllowedOrRooms.add(entry)
            } else if (!state.roomSummary.invoke()!!.flattenParentIds.contains(entry.id)) {
                // it's a space but not a direct parent
                unknownAllowedOrRooms.add(entry)
            } else {
                // nop
            }
        }

        setState {
            copy(
                    updatedAllowList = selection.toList(),
                    unknownRestricted = unknownAllowedOrRooms
            )
        }
    }

    private fun handleFilter(action: RoomJoinRuleChooseRestrictedActions.FilterWith) = withState { state ->
        setState {
            copy(filter = action.filter, filteredResults = Loading())
        }
        viewModelScope.launch {
            if (vectorPreferences.developerMode()) {
                // in developer mode we let you choose any room or space to restrict to
                val filteredCandidates = session.roomService().getRoomSummaries(
                        roomSummaryQueryParams {
                            excludeType = null
                            displayName = QueryStringValue.Contains(action.filter, QueryStringValue.Case.INSENSITIVE)
                            memberships = listOf(Membership.JOIN)
                        }
                ).map { it.toMatrixItem() }
                setState {
                    copy(
                            filteredResults = Success(filteredCandidates)
                    )
                }
            } else {
                // in normal mode you can only restrict to space parents
                setState {
                    copy(
                            filteredResults = Success(
                                    session.getRoomSummary(state.roomId)?.flattenParentIds?.mapNotNull {
                                        session.getRoomSummary(it)?.toMatrixItem()
                                    }?.filter {
                                        it.displayName?.contains(filter, true) == true
                                    }.orEmpty()
                            )
                    )
                }
            }
        }
    }

    companion object : MavericksViewModelFactory<RoomJoinRuleChooseRestrictedViewModel, RoomJoinRuleChooseRestrictedState> by hiltMavericksViewModelFactory() {
        private val ALLOW_LIST_JOIN_RULES = setOf(RoomJoinRules.RESTRICTED, RoomJoinRules.KNOCK_RESTRICTED)
    }
}
