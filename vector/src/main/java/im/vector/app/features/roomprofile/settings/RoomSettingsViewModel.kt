/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.settings

import androidx.core.net.toFile
import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.Success
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.features.home.resolveRoomBannerUrl
import im.vector.app.features.settings.VectorPreferences
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilities
import org.matrix.android.sdk.api.session.room.members.roomMemberQueryParams
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomAvatarContent
import org.matrix.android.sdk.api.session.room.model.RoomGuestAccessContent
import org.matrix.android.sdk.api.session.room.model.RoomHistoryVisibilityContent
import org.matrix.android.sdk.api.session.room.model.RoomJoinRulesContent
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.flow.flow
import org.matrix.android.sdk.flow.mapOptional
import org.matrix.android.sdk.flow.unwrap

class RoomSettingsViewModel @AssistedInject constructor(
        @Assisted initialState: RoomSettingsViewState,
        private val vectorPreferences: VectorPreferences,
        private val session: Session
) :
        VectorViewModel<RoomSettingsViewState, RoomSettingsAction, RoomSettingsViewEvents>(initialState) {

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<RoomSettingsViewModel, RoomSettingsViewState> {
        override fun create(initialState: RoomSettingsViewState): RoomSettingsViewModel
    }

    companion object : MavericksViewModelFactory<RoomSettingsViewModel, RoomSettingsViewState> by hiltMavericksViewModelFactory()

    private val room = session.getRoom(initialState.roomId)!!

    init {
        // Seed synchronously so the editable header shows the banner on the first frame
        setState {
            copy(currentRoomBannerUrl = room.stateService().getStateEvents(EventType.STATE_ROOM_BANNER.values.toSet(), QueryStringValue.IsEmpty).resolveRoomBannerUrl())
        }
        observeRoomSummary()
        observeRoomHistoryVisibility()
        observeJoinRule()
        observeGuestAccess()
        observeRoomAvatar()
        observeDirectUserAvatar()
        observeRoomBanner()
        observeState()

        val homeServerCapabilities = session.homeServerCapabilitiesService().getHomeServerCapabilities()
        val canUseRestricted = homeServerCapabilities
                .isFeatureSupported(HomeServerCapabilities.ROOM_CAP_RESTRICTED, room.roomVersionService().getRoomVersion())

        val restrictedSupport = homeServerCapabilities.isFeatureSupported(HomeServerCapabilities.ROOM_CAP_RESTRICTED)
        val couldUpgradeToRestricted = restrictedSupport == HomeServerCapabilities.RoomCapabilitySupport.SUPPORTED

        setState {
            copy(
                    supportsRestricted = canUseRestricted,
                    canUpgradeToRestricted = couldUpgradeToRestricted
            )
        }
    }

    private fun observeState() {
        onEach(
                RoomSettingsViewState::avatarAction,
                RoomSettingsViewState::bannerAction,
                RoomSettingsViewState::newName,
                RoomSettingsViewState::newTopic,
                RoomSettingsViewState::newHistoryVisibility,
                RoomSettingsViewState::newRoomJoinRules,
                RoomSettingsViewState::roomSummary
        ) { avatarAction,
            bannerAction,
            newName,
            newTopic,
            newHistoryVisibility,
            newJoinRule,
            asyncSummary ->
            val summary = asyncSummary()
            setState {
                copy(
                        showSaveAction = avatarAction !is RoomSettingsViewState.AvatarAction.None ||
                                bannerAction !is RoomSettingsViewState.BannerAction.None ||
                                summary?.name != newName ||
                                summary?.topic != newTopic ||
                                (newHistoryVisibility != null && newHistoryVisibility != currentHistoryVisibility) ||
                                newJoinRule.hasChanged()
                )
            }
        }
    }

    private fun observeRoomSummary() {
        room.flow().liveRoomSummary()
                .unwrap()
                .execute { async ->
                    val roomSummary = async.invoke()
                    // Seed the editable fields on first load only; later summary updates (from sync) must not
                    // clobber in-progress edits, which would silently hide the Save button.
                    val alreadyLoaded = this.roomSummary is Success
                    copy(
                            roomSummary = async,
                            newName = if (alreadyLoaded) newName else roomSummary?.name,
                            newTopic = if (alreadyLoaded) newTopic else roomSummary?.topic
                    )
                }

        val powerLevelsFlow = room.flow().liveRoomPowerLevels()
        powerLevelsFlow
                .onEach { roomPowerLevels ->
                    val permissions = RoomSettingsViewState.ActionPermissions(
                            canChangeAvatar = roomPowerLevels.isUserAllowedToSend(session.myUserId, true, EventType.STATE_ROOM_AVATAR),
                            canChangeBanner = EventType.STATE_ROOM_BANNER.values.any {
                                roomPowerLevels.isUserAllowedToSend(session.myUserId, true, it)
                            },
                            canChangeName = roomPowerLevels.isUserAllowedToSend(session.myUserId, true, EventType.STATE_ROOM_NAME),
                            canChangeTopic = roomPowerLevels.isUserAllowedToSend(session.myUserId, true, EventType.STATE_ROOM_TOPIC),
                            canChangeHistoryVisibility = roomPowerLevels.isUserAllowedToSend(
                                    session.myUserId, true,
                                    EventType.STATE_ROOM_HISTORY_VISIBILITY
                            ),
                            canChangeJoinRule = roomPowerLevels.isUserAllowedToSend(
                                    session.myUserId, true,
                                    EventType.STATE_ROOM_JOIN_RULES
                            ) &&
                                    roomPowerLevels.isUserAllowedToSend(
                                            session.myUserId, true,
                                            EventType.STATE_ROOM_GUEST_ACCESS
                                    ),
                            canAddChildren = roomPowerLevels.isUserAllowedToSend(
                                    session.myUserId, true,
                                    EventType.STATE_SPACE_CHILD
                            )
                    )
                    setState {
                        copy(actionPermissions = permissions)
                    }
                }.launchIn(viewModelScope)
    }

    private fun observeRoomHistoryVisibility() {
        room.flow()
                .liveStateEvent(EventType.STATE_ROOM_HISTORY_VISIBILITY, QueryStringValue.IsEmpty)
                .mapOptional { it.content.toModel<RoomHistoryVisibilityContent>() }
                .unwrap()
                .mapNotNull { it.historyVisibility }
                .setOnEach {
                    copy(currentHistoryVisibility = it)
                }
    }

    private fun observeJoinRule() {
        room.flow()
                .liveStateEvent(EventType.STATE_ROOM_JOIN_RULES, QueryStringValue.IsEmpty)
                .mapOptional { it.content.toModel<RoomJoinRulesContent>() }
                .unwrap()
                .mapNotNull { it.joinRules }
                .setOnEach {
                    copy(currentRoomJoinRules = it)
                }
    }

    private fun observeGuestAccess() {
        room.flow()
                .liveStateEvent(EventType.STATE_ROOM_GUEST_ACCESS, QueryStringValue.IsEmpty)
                .mapOptional { it.content.toModel<RoomGuestAccessContent>() }
                .unwrap()
                .mapNotNull { it.guestAccess }
                .setOnEach {
                    copy(currentGuestAccess = it)
                }
    }

    /**
     * The room's own avatar, read from the state event rather than from the summary's resolved avatar url, so that
     * editing acts on m.room.avatar only. The DM fallback is tracked separately by [observeDirectUserAvatar].
     */
    private fun observeRoomAvatar() {
        room.flow()
                .liveStateEvent(EventType.STATE_ROOM_AVATAR, QueryStringValue.IsEmpty)
                .mapOptional { it.content.toModel<RoomAvatarContent>() }
                .unwrap()
                .setOnEach {
                    copy(currentRoomAvatarUrl = it.avatarUrl)
                }
    }

    // The peer avatar a DM falls back to when it has no m.room.avatar, so the header can show it and
    // preview it. Approximates SqlRoomAvatarResolver: the direct member, else the other member of a
    // two-person room, else (peer already left) whoever left with an avatar.
    private fun observeDirectUserAvatar() {
        room.flow().liveRoomSummary().unwrap()
                .flatMapLatest { summary ->
                    if (!summary.isDirect) {
                        flowOf(null)
                    } else {
                        room.flow().liveRoomMembers(roomMemberQueryParams { memberships = Membership.all() })
                                .map { members -> members.resolveDirectUserAvatarUrl(summary.directUserId) }
                    }
                }
                .setOnEach { copy(directUserAvatarUrl = it) }
    }

    private fun List<RoomMemberSummary>.resolveDirectUserAvatarUrl(directUserId: String?): String? {
        val active = filter { it.membership.isActive() }
        val avatarUrl = active.firstOrNull { it.userId == directUserId }?.avatarUrl
                ?: active.takeIf { it.size == 2 }?.firstOrNull { it.userId != session.myUserId }?.avatarUrl
                ?: takeIf { active.size <= 1 }
                        ?.firstOrNull { it.membership == Membership.LEAVE && !it.avatarUrl.isNullOrEmpty() }
                        ?.avatarUrl
        return avatarUrl?.takeIf { it.isNotEmpty() }
    }

    private fun observeRoomBanner() {
        room.flow()
                .liveStateEvents(EventType.STATE_ROOM_BANNER.values.toSet(), QueryStringValue.IsEmpty)
                .setOnEach {
                    copy(currentRoomBannerUrl = it.resolveRoomBannerUrl())
                }
    }

    override fun handle(action: RoomSettingsAction) {
        when (action) {
            is RoomSettingsAction.SetAvatarAction -> handleSetAvatarAction(action)
            is RoomSettingsAction.SetBannerAction -> handleSetBannerAction(action)
            is RoomSettingsAction.SetRoomName -> setState { copy(newName = action.newName) }
            is RoomSettingsAction.SetRoomTopic -> setState { copy(newTopic = action.newTopic) }
            is RoomSettingsAction.SetRoomHistoryVisibility -> setState { copy(newHistoryVisibility = action.visibility) }
            is RoomSettingsAction.SetRoomJoinRule -> handleSetRoomJoinRule(action)
            is RoomSettingsAction.SetRoomGuestAccess -> handleSetGuestAccess(action)
            is RoomSettingsAction.Save -> saveSettings()
            is RoomSettingsAction.Cancel -> cancel()
        }
    }

    private fun handleSetRoomJoinRule(action: RoomSettingsAction.SetRoomJoinRule) = withState { state ->
        setState {
            copy(newRoomJoinRules = RoomSettingsViewState.NewJoinRule(
                    newJoinRules = action.roomJoinRule.takeIf { it != state.currentRoomJoinRules },
                    newGuestAccess = state.newRoomJoinRules.newGuestAccess.takeIf { it != state.currentGuestAccess }
            ))
        }
    }

    private fun handleSetGuestAccess(action: RoomSettingsAction.SetRoomGuestAccess) = withState { state ->
        setState {
            copy(newRoomJoinRules = RoomSettingsViewState.NewJoinRule(
                    newJoinRules = state.newRoomJoinRules.newJoinRules.takeIf { it != state.currentRoomJoinRules },
                    newGuestAccess = action.guestAccess.takeIf { it != state.currentGuestAccess }
            ))
        }
    }

    private fun handleSetAvatarAction(action: RoomSettingsAction.SetAvatarAction) {
        setState {
            deletePendingAvatar(this)
            copy(avatarAction = action.avatarAction)
        }
    }

    private fun deletePendingAvatar(state: RoomSettingsViewState) {
        // Maybe delete the pending avatar
        (state.avatarAction as? RoomSettingsViewState.AvatarAction.UpdateAvatar)
                ?.let { tryOrNull { it.newAvatarUri.toFile().delete() } }
    }

    private fun handleSetBannerAction(action: RoomSettingsAction.SetBannerAction) {
        setState {
            deletePendingBanner(this)
            copy(bannerAction = action.bannerAction)
        }
    }

    private fun deletePendingBanner(state: RoomSettingsViewState) {
        (state.bannerAction as? RoomSettingsViewState.BannerAction.UpdateBanner)
                ?.let { tryOrNull { it.newBannerUri.toFile().delete() } }
    }

    private fun cancel() {
        withState {
            deletePendingAvatar(it)
            deletePendingBanner(it)
        }

        _viewEvents.post(RoomSettingsViewEvents.GoBack)
    }

    private fun saveSettings() = withState { state ->
        val operationList = mutableListOf<suspend () -> Unit>()

        val summary = state.roomSummary.invoke()

        when (val avatarAction = state.avatarAction) {
            RoomSettingsViewState.AvatarAction.None -> Unit
            RoomSettingsViewState.AvatarAction.DeleteAvatar -> {
                operationList.add { room.stateService().deleteAvatar() }
            }
            is RoomSettingsViewState.AvatarAction.UpdateAvatar -> {
                operationList.add { room.stateService().updateAvatar(avatarAction.newAvatarUri.toString(), avatarAction.newAvatarFileName) }
            }
        }
        when (val bannerAction = state.bannerAction) {
            RoomSettingsViewState.BannerAction.None -> Unit
            RoomSettingsViewState.BannerAction.DeleteBanner -> {
                operationList.add { room.stateService().deleteBanner() }
            }
            is RoomSettingsViewState.BannerAction.UpdateBanner -> {
                operationList.add { room.stateService().updateBanner(bannerAction.newBannerUri.toString(), bannerAction.newBannerFileName) }
            }
        }
        if (summary?.name != state.newName) {
            operationList.add { room.stateService().updateName(state.newName ?: "") }
        }
        if (summary?.topic != state.newTopic) {
            val newTopic = state.newTopic ?: ""
            val formattedTopic = newTopic.takeIf { it.isNotEmpty() }?.let { room.sendService().computeFormattedHtml(it, autoMarkdown = true) }
            operationList.add { room.stateService().updateTopic(newTopic, formattedTopic) }
        }

        if (state.newHistoryVisibility != null) {
            operationList.add { room.stateService().updateHistoryReadability(state.newHistoryVisibility) }
        }

        if (state.newRoomJoinRules.hasChanged()) {
            operationList.add { room.stateService().updateJoinRule(state.newRoomJoinRules.newJoinRules, state.newRoomJoinRules.newGuestAccess) }
        }
        viewModelScope.launch {
            updateLoadingState(isLoading = true)
            try {
                for (operation in operationList) {
                    operation.invoke()
                }
                setState {
                    deletePendingAvatar(this)
                    deletePendingBanner(this)
                    copy(
                            avatarAction = RoomSettingsViewState.AvatarAction.None,
                            bannerAction = RoomSettingsViewState.BannerAction.None,
                            newHistoryVisibility = null,
                            newRoomJoinRules = RoomSettingsViewState.NewJoinRule()
                    )
                }
                _viewEvents.post(RoomSettingsViewEvents.Success)
            } catch (failure: Throwable) {
                _viewEvents.post(RoomSettingsViewEvents.Failure(failure))
            } finally {
                updateLoadingState(isLoading = false)
            }
        }
    }

    private fun updateLoadingState(isLoading: Boolean) {
        setState {
            copy(isLoading = isLoading)
        }
    }
}
