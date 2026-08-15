/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roommemberprofile

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
import im.vector.app.core.profile.ProfileFieldsFormatter
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.utils.PerfTrace
import im.vector.app.features.createdirect.DirectRoomHelper
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider
import im.vector.app.features.redaction.MassRedactionManager
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.profile.ProfileOverrides
import org.matrix.android.sdk.api.session.profile.ProfileService
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.members.roomMemberQueryParams
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.PowerLevelsContent
import org.matrix.android.sdk.api.session.room.model.RoomEncryptionAlgorithm
import org.matrix.android.sdk.api.session.room.model.RoomType
import org.matrix.android.sdk.api.session.room.powerlevels.Role
import org.matrix.android.sdk.api.session.room.powerlevels.UserPowerLevel
import org.matrix.android.sdk.api.session.user.model.User
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.api.util.MatrixItem
import org.matrix.android.sdk.api.util.MimeTypes
import org.matrix.android.sdk.api.util.toMatrixItem
import org.matrix.android.sdk.flow.flow
import org.matrix.android.sdk.flow.unwrap

class RoomMemberProfileViewModel @AssistedInject constructor(
        @Assisted private val initialState: RoomMemberProfileViewState,
        private val stringProvider: StringProvider,
        private val matrixItemColorProvider: MatrixItemColorProvider,
        private val directRoomHelper: DirectRoomHelper,
        private val massRedactionManager: MassRedactionManager,
        private val profileFieldsFormatter: ProfileFieldsFormatter,
        private val session: Session
) : VectorViewModel<RoomMemberProfileViewState, RoomMemberProfileAction, RoomMemberProfileViewEvents>(initialState) {

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<RoomMemberProfileViewModel, RoomMemberProfileViewState> {
        override fun create(initialState: RoomMemberProfileViewState): RoomMemberProfileViewModel
    }

    companion object : MavericksViewModelFactory<RoomMemberProfileViewModel, RoomMemberProfileViewState> by hiltMavericksViewModelFactory()

    private val room = if (initialState.roomId != null) {
        session.getRoom(initialState.roomId)
    } else {
        null
    }

    init {
        PerfTrace.time("member.profile.vm.init") {
            initInner()
        }
    }

    private fun initInner() {
        // Seed the room power levels and the user-power-level label synchronously, so the
        // controller can render the admin section / "Role" subtitle on the very first frame
        // instead of waiting 1-2s for liveRoomPowerLevels' LiveData to round-trip through
        // the main thread. The live observers below still update these as state changes.
        val initialRoomPowerLevels = room?.stateService()?.getRoomPowerLevels()
        val initialRoomSummary = room?.roomSummary()
        val initialRoomMember = room?.membershipService()?.getRoomMember(initialState.userId)
        val initialUserPowerLevelString = if (initialRoomPowerLevels != null && initialRoomSummary != null && initialRoomMember != null) {
            computeUserPowerLevelString(initialRoomPowerLevels, initialRoomSummary)
        } else {
            null
        }
        val initialPermissions = if (initialRoomPowerLevels != null) {
            ActionPermissions(
                    canKick = initialRoomPowerLevels.isUserAbleToKick(session.myUserId),
                    canBan = initialRoomPowerLevels.isUserAbleToBan(session.myUserId),
                    canInvite = initialRoomPowerLevels.isUserAbleToInvite(session.myUserId),
                    canEditPowerLevel = initialRoomPowerLevels.isUserAllowedToSend(session.myUserId, true, EventType.STATE_ROOM_POWER_LEVELS),
                    canRedact = initialRoomPowerLevels.isUserAbleToRedact(session.myUserId),
            )
        } else {
            ActionPermissions()
        }
        setState {
            copy(
                    isMine = session.myUserId == this.userId,
                    // Always resolve to at least the mxid item, so a redacted/absent membership shows the
                    // user tag as the display name instead of leaving the profile stuck on loading.
                    userMatrixItem = Success(bestKnownMatrixItem()),
                    hasReadReceipt = room?.readService()?.getUserReadReceipt(initialState.userId) != null,
                    isSpace = initialRoomSummary?.roomType == RoomType.SPACE,
                    roomPowerLevels = initialRoomPowerLevels,
                    actionPermissions = initialPermissions,
                    userPowerLevelString = initialUserPowerLevelString?.let { Success(it) } ?: Uninitialized,
                    asyncMembership = initialRoomMember?.membership?.let { Success(it) } ?: Uninitialized,
                    // Seeded synchronously so the banner doesn't pop in a frame late
                    globalBannerUrl = session.profileService().getCachedBannerUrl(initialState.userId),
                    status = session.profileService().getCachedStatus(initialState.userId),
                    bio = session.profileService().getCachedBio(initialState.userId),
                    profileFieldsLine = cachedProfileFieldsLine(),
            )
        }
        session.profileService().prefetchProfileFields(initialState.userId)
        observeIgnoredState()
        observeAccountData()
        viewModelScope.launch(Dispatchers.Main) {
            // Do we have a room member for this id.
            val roomMember = withContext(Dispatchers.Default) {
                room?.membershipService()?.getRoomMember(initialState.userId)
            }
            // If not, we look for profile info on the server
            if (room == null || roomMember == null) {
                fetchProfileInfo()
            } else {
                // otherwise we just start listening to db
                setState { copy(showAsMember = true) }
                observeRoomMemberSummary(room)
                observeRoomSummaryAndPowerLevels(room)
                fetchGlobalProfile()
            }
        }

        session.flow().liveUserCryptoDevices(initialState.userId)
                .map {
                    Pair(
                            it.fold(true) { prev, dev -> prev && dev.isVerified },
                            it.fold(true) { prev, dev -> prev && (dev.trustLevel?.crossSigningVerified == true) }
                    )
                }
                .execute {
                    copy(
                            allDevicesAreTrusted = it()?.first == true,
                            allDevicesAreCrossSignedTrusted = it()?.second == true
                    )
                }

        session.flow().liveCrossSigningInfo(initialState.userId)
                .execute {
                    copy(userMXCrossSigningInfo = it.invoke()?.getOrNull())
                }
    }

    private fun observeAccountData() {
        session.flow()
                .liveUserAccountData(UserAccountDataTypes.TYPE_OVERRIDE_COLORS)
                .unwrap()
                .onEach {
                    val newUserColor = it.content.toModel<Map<String, String>>()?.get(initialState.userId)
                    setState {
                        copy(
                                userColorOverride = newUserColor
                        )
                    }
                }
                .launchIn(viewModelScope)

        session.flow()
                .liveUserAccountData(UserAccountDataTypes.TYPE_PROFILE_OVERRIDES)
                .unwrap()
                .onEach { event ->
                    val fields = ProfileOverrides.parse(event.content)[initialState.userId]
                    val overrideName = (fields?.get(ProfileOverrides.FIELD_DISPLAY_NAME) as? String)?.takeIf { it.isNotBlank() }
                    val overrideAvatar = (fields?.get(ProfileOverrides.FIELD_AVATAR_URL) as? String)?.takeIf { it.isNotBlank() }
                    val base = bestKnownMatrixItem()
                    setState {
                        copy(
                                profileOverrideDisplayName = overrideName,
                                profileOverrideAvatarUrl = overrideAvatar,
                                hasProfileOverrides = !fields.isNullOrEmpty(),
                                userMatrixItem = Success(
                                        MatrixItem.UserItem(
                                                initialState.userId,
                                                overrideName ?: base.displayName,
                                                overrideAvatar ?: base.avatarUrl,
                                        )
                                ),
                        )
                    }
                }
                .launchIn(viewModelScope)
    }

    private fun observeIgnoredState() {
        session.flow().liveIgnoredUsers()
                .map { ignored ->
                    ignored.find {
                        it.userId == initialState.userId
                    } != null
                }
                .execute {
                    copy(isIgnored = it)
                }
    }

    override fun handle(action: RoomMemberProfileAction) {
        when (action) {
            is RoomMemberProfileAction.RetryFetchingInfo -> handleRetryFetchProfileInfo()
            is RoomMemberProfileAction.IgnoreUser -> handleIgnoreAction()
            is RoomMemberProfileAction.VerifyUser -> prepareVerification()
            is RoomMemberProfileAction.SetPowerLevel -> handleSetPowerLevel(action)
            is RoomMemberProfileAction.BanOrUnbanUser -> handleBanOrUnbanAction(action)
            is RoomMemberProfileAction.KickUser -> handleKickAction(action)
            RoomMemberProfileAction.RedactAllMessages -> handleRedactAllMessages()
            RoomMemberProfileAction.InviteUser -> handleInviteAction()
            is RoomMemberProfileAction.SetUserColorOverride -> handleSetUserColorOverride(action)
            is RoomMemberProfileAction.SetProfileOverrideDisplayName -> handleSetProfileOverrideDisplayName(action)
            is RoomMemberProfileAction.SetProfileOverrideAvatar -> handleSetProfileOverrideAvatar(action)
            RoomMemberProfileAction.ResetProfileOverrides -> handleResetProfileOverrides()
            is RoomMemberProfileAction.OpenOrCreateDm -> handleOpenOrCreateDm(action)
        }
    }

    private fun handleSetProfileOverrideDisplayName(action: RoomMemberProfileAction.SetProfileOverrideDisplayName) {
        updateProfileOverrideFields { fields ->
            val name = action.displayName?.trim()?.takeIf { it.isNotBlank() }
            if (name != null) fields[ProfileOverrides.FIELD_DISPLAY_NAME] = name else fields.remove(ProfileOverrides.FIELD_DISPLAY_NAME)
        }
    }

    private fun handleSetProfileOverrideAvatar(action: RoomMemberProfileAction.SetProfileOverrideAvatar) {
        viewModelScope.launch {
            val url = if (action.avatarUri == null) {
                null
            } else {
                _viewEvents.post(RoomMemberProfileViewEvents.Loading())
                try {
                    session.fileService().uploadFile(action.avatarUri.toString(), action.avatarUri.lastPathSegment, MimeTypes.Jpeg)
                } catch (failure: Throwable) {
                    _viewEvents.post(RoomMemberProfileViewEvents.StopLoading)
                    _viewEvents.post(RoomMemberProfileViewEvents.Failure(failure))
                    return@launch
                }
            }
            updateProfileOverrideFields { fields ->
                if (url != null) fields[ProfileOverrides.FIELD_AVATAR_URL] = url else fields.remove(ProfileOverrides.FIELD_AVATAR_URL)
            }
        }
    }

    private fun handleResetProfileOverrides() {
        updateProfileOverrideFields { it.clear() }
    }

    /** Rewrites this user's entry of the profile-overrides account data; an emptied entry is removed. */
    private fun updateProfileOverrideFields(mutate: (MutableMap<String, Any?>) -> Unit) {
        val content = session.accountDataService()
                .getUserAccountDataEvent(UserAccountDataTypes.TYPE_PROFILE_OVERRIDES)
                ?.content
                .orEmpty()
                .toMutableMap()
        val fields = (content[initialState.userId] as? Map<*, *>)
                .orEmpty()
                .entries
                .mapNotNull { (key, value) -> (key as? String)?.let { it to value } }
                .toMap()
                .toMutableMap()
        mutate(fields)
        if (fields.isEmpty()) content.remove(initialState.userId) else content[initialState.userId] = fields
        viewModelScope.launch {
            try {
                session.accountDataService().updateUserAccountData(UserAccountDataTypes.TYPE_PROFILE_OVERRIDES, content)
                _viewEvents.post(RoomMemberProfileViewEvents.StopLoading)
            } catch (failure: Throwable) {
                _viewEvents.post(RoomMemberProfileViewEvents.StopLoading)
                _viewEvents.post(RoomMemberProfileViewEvents.Failure(failure))
            }
        }
    }

    private fun handleOpenOrCreateDm(action: RoomMemberProfileAction.OpenOrCreateDm) {
        viewModelScope.launch {
            _viewEvents.post(RoomMemberProfileViewEvents.Loading())
            val roomId = try {
                directRoomHelper.ensureDMExists(action.userId)
            } catch (failure: Throwable) {
                _viewEvents.post(RoomMemberProfileViewEvents.Failure(failure))
                return@launch
            }
            if (roomId != initialState.roomId) {
                _viewEvents.post(RoomMemberProfileViewEvents.OpenRoom(roomId = roomId))
            } else {
                // Just go back to the previous screen (timeline)
                _viewEvents.post(RoomMemberProfileViewEvents.GoBack)
            }
        }
    }

    private fun handleSetUserColorOverride(action: RoomMemberProfileAction.SetUserColorOverride) {
        val newOverrideColorSpecs = session.accountDataService()
                .getUserAccountDataEvent(UserAccountDataTypes.TYPE_OVERRIDE_COLORS)
                ?.content
                ?.toModel<Map<String, String>>()
                .orEmpty()
                .toMutableMap()
        if (matrixItemColorProvider.setOverrideColor(initialState.userId, action.newColorSpec)) {
            newOverrideColorSpecs[initialState.userId] = action.newColorSpec
        } else {
            newOverrideColorSpecs.remove(initialState.userId)
        }
        viewModelScope.launch {
            try {
                session.accountDataService().updateUserAccountData(
                        type = UserAccountDataTypes.TYPE_OVERRIDE_COLORS,
                        content = newOverrideColorSpecs
                )
            } catch (failure: Throwable) {
                _viewEvents.post(RoomMemberProfileViewEvents.Failure(failure))
            }
        }
    }

    private fun handleSetPowerLevel(action: RoomMemberProfileAction.SetPowerLevel) = withState { state ->
        if (room == null || action.previousValue == action.newValue) {
            return@withState
        }
        val roomPowerLevels = state.roomPowerLevels ?: return@withState
        val myPowerLevel = roomPowerLevels.getUserPowerLevel(session.myUserId)
        if (action.askForValidation && action.newValue >= myPowerLevel) {
            _viewEvents.post(RoomMemberProfileViewEvents.ShowPowerLevelValidation(action.previousValue, action.newValue))
        } else if (action.askForValidation && state.isMine) {
            _viewEvents.post(RoomMemberProfileViewEvents.ShowPowerLevelDemoteWarning(action.previousValue, action.newValue))
        } else {
            val newPowerLevelsContent = (roomPowerLevels.powerLevelsContent ?: PowerLevelsContent())
                    .setUserPowerLevel(state.userId, action.newValue.value)
                    .toContent()
            viewModelScope.launch {
                _viewEvents.post(RoomMemberProfileViewEvents.Loading())
                try {
                    room.stateService().sendStateEvent(EventType.STATE_ROOM_POWER_LEVELS, stateKey = "", newPowerLevelsContent)
                    _viewEvents.post(RoomMemberProfileViewEvents.OnSetPowerLevelSuccess)
                } catch (failure: Throwable) {
                    _viewEvents.post(RoomMemberProfileViewEvents.Failure(failure))
                }
            }
        }
    }

    private fun prepareVerification() = withState { state ->
        // Sanity
        if (state.isRoomEncrypted) {
            if (!state.isMine && state.userMXCrossSigningInfo?.isTrusted() == false) {
                // ok, let's find or create the DM room
                _viewEvents.post(
                        RoomMemberProfileViewEvents.StartVerification(
                                userId = state.userId,
                                canCrossSign = session.cryptoService().crossSigningService().canCrossSign()
                        )
                )
            }
        }
    }

    private fun handleInviteAction() {
        if (room == null) {
            return
        }
        viewModelScope.launch {
            try {
                _viewEvents.post(RoomMemberProfileViewEvents.Loading())
                room.membershipService().invite(initialState.userId)
                _viewEvents.post(RoomMemberProfileViewEvents.OnInviteActionSuccess)
            } catch (failure: Throwable) {
                _viewEvents.post(RoomMemberProfileViewEvents.Failure(failure))
            }
        }
    }

    private fun handleKickAction(action: RoomMemberProfileAction.KickUser) {
        if (room == null) {
            return
        }
        viewModelScope.launch {
            try {
                _viewEvents.post(RoomMemberProfileViewEvents.Loading())
                room.membershipService().kick(initialState.userId, action.reason)
                _viewEvents.post(RoomMemberProfileViewEvents.OnKickActionSuccess)
            } catch (failure: Throwable) {
                _viewEvents.post(RoomMemberProfileViewEvents.Failure(failure))
            }
        }
    }

    private fun handleRedactAllMessages() = withState { state ->
        val roomId = state.roomId ?: return@withState
        val displayName = state.userMatrixItem()?.getBestName() ?: state.userId
        val result = massRedactionManager.start(roomId, state.userId, displayName, delayMs = 0L)
        if (result == MassRedactionManager.StartResult.AlreadyRunning) {
            _viewEvents.post(RoomMemberProfileViewEvents.MassRedactionAlreadyRunning)
        }
    }

    private fun handleBanOrUnbanAction(action: RoomMemberProfileAction.BanOrUnbanUser) = withState { state ->
        if (room == null) {
            return@withState
        }
        val membership = state.asyncMembership() ?: return@withState
        viewModelScope.launch {
            try {
                _viewEvents.post(RoomMemberProfileViewEvents.Loading())
                if (membership == Membership.BAN) {
                    room.membershipService().unban(initialState.userId, action.reason)
                } else {
                    room.membershipService().ban(initialState.userId, action.reason)
                }
                _viewEvents.post(RoomMemberProfileViewEvents.OnBanActionSuccess)
            } catch (failure: Throwable) {
                _viewEvents.post(RoomMemberProfileViewEvents.Failure(failure))
            }
        }
    }

    private fun observeRoomMemberSummary(room: Room) {
        val queryParams = roomMemberQueryParams {
            this.userId = QueryStringValue.Equals(initialState.userId, QueryStringValue.Case.SENSITIVE)
            // The builder defaults displayName to IsNotEmpty; a redacted/empty-profile member has none, so
            // that default would drop them from the live query — leaving asyncMembership unresolved and
            // every membership-dependent action (ignore/invite/kick/ban/role) hidden. Query unconditionally.
            displayName = QueryStringValue.NoCondition
        }
        room.flow().liveRoomMembers(queryParams)
                .map { it.firstOrNull() }
                .execute {
                    val member = it()
                    when {
                        it is Fail -> copy(userMatrixItem = Fail(it.error), asyncMembership = Fail(it.error))
                        member != null -> copy(
                                userMatrixItem = Success(member.toMatrixItem()),
                                asyncMembership = Success(member.membership)
                        )
                        // No summary (e.g. membership event redacted): keep the mxid fallback rather than
                        // regressing to Loading, which would leave the profile stuck.
                        it is Success -> copy(userMatrixItem = Success(MatrixItem.UserItem(initialState.userId)))
                        else -> this
                    }
                }
    }

    private fun handleRetryFetchProfileInfo() {
        viewModelScope.launch {
            fetchProfileInfo()
        }
    }

    /** Room member, then the cached global profile, then the bare mxid so the profile never opens empty. */
    private fun bestKnownMatrixItem(): MatrixItem {
        return room?.membershipService()?.getRoomMember(initialState.userId)?.toMatrixItem()
                ?: session.userService().getUser(initialState.userId)?.toMatrixItem()
                ?: MatrixItem.UserItem(initialState.userId)
    }

    private suspend fun fetchProfileInfo() {
        val profile = try {
            session.profileService().getProfile(initialState.userId)
        } catch (throwable: Throwable) {
            null
        }
        val item = profile?.let { User.fromJson(initialState.userId, it).toMatrixItem() }
                ?: bestKnownMatrixItem()
        setState {
            copy(
                    userMatrixItem = Success(item),
                    // On fetch failure keep the seeded cache value rather than blanking the banner
                    globalBannerUrl = if (profile != null) profile.bannerUrl() else globalBannerUrl,
                    profileJson = profile ?: profileJson,
                    status = session.profileService().getCachedStatus(initialState.userId),
                    bio = session.profileService().getCachedBio(initialState.userId),
                    profileFieldsLine = cachedProfileFieldsLine(),
            )
        }
    }

    private fun fetchGlobalProfile() {
        viewModelScope.launch {
            // 403 (profiles limited to shared-room users) just means no banner
            val profile = tryOrNull { session.profileService().getProfile(initialState.userId) } ?: return@launch
            setState {
                copy(
                        globalBannerUrl = profile.bannerUrl(),
                        profileJson = profile,
                        status = session.profileService().getCachedStatus(initialState.userId),
                        bio = session.profileService().getCachedBio(initialState.userId),
                        profileFieldsLine = cachedProfileFieldsLine(),
                )
            }
        }
    }

    // getProfile() populates the extended-field cache before returning, so this reads the fresh values.
    private fun cachedProfileFieldsLine(): String? {
        return profileFieldsFormatter.format(
                session.profileService().getCachedPronouns(initialState.userId),
                session.profileService().getCachedTimezone(initialState.userId),
        )
    }

    private fun JsonDict.bannerUrl(): String? {
        return (this[ProfileService.BANNER_URL_KEY] as? String)
                ?: (this[ProfileService.BANNER_URL_KEY_UNSTABLE] as? String)
    }

    private fun observeRoomSummaryAndPowerLevels(room: Room) {
        val roomSummaryLive = room.flow().liveRoomSummary().unwrap()
        val powerLevelsFlow = room.flow().liveRoomPowerLevels()
        powerLevelsFlow
                .onEach { roomPowerLevels ->
                    val permissions = ActionPermissions(
                            canKick = roomPowerLevels.isUserAbleToKick(session.myUserId),
                            canBan = roomPowerLevels.isUserAbleToBan(session.myUserId),
                            canInvite = roomPowerLevels.isUserAbleToInvite(session.myUserId),
                            canEditPowerLevel = roomPowerLevels.isUserAllowedToSend(session.myUserId, true, EventType.STATE_ROOM_POWER_LEVELS),
                            canRedact = roomPowerLevels.isUserAbleToRedact(session.myUserId)
                    )
                    setState {
                        copy(roomPowerLevels = roomPowerLevels, actionPermissions = permissions)
                    }
                }.launchIn(viewModelScope)

        roomSummaryLive.execute {
            val summary = it.invoke() ?: return@execute this
            if (summary.isEncrypted) {
                copy(
                        isRoomEncrypted = true,
                        isAlgorithmSupported = summary.roomEncryptionAlgorithm is RoomEncryptionAlgorithm.SupportedAlgorithm
                )
            } else {
                copy(isRoomEncrypted = false)
            }
        }
        roomSummaryLive.combine(powerLevelsFlow) { roomSummary, roomPowerLevels ->
            computeUserPowerLevelString(roomPowerLevels, roomSummary)
        }.execute {
            copy(userPowerLevelString = it)
        }
    }

    private fun computeUserPowerLevelString(
            roomPowerLevels: org.matrix.android.sdk.api.session.room.powerlevels.RoomPowerLevels,
            roomSummary: org.matrix.android.sdk.api.session.room.model.RoomSummary,
    ): String {
        val roomName = roomSummary.toMatrixItem().getBestName()
        val userPowerLevel = roomPowerLevels.getUserPowerLevel(initialState.userId)
        val role = roomPowerLevels.getSuggestedRole(initialState.userId)
        // Preserve the numeric value when it doesn't match a preset, otherwise a user with
        // e.g. PL 45 would be shown as "Default in <room>" rather than "Custom (45) in <room>".
        val matchesPreset = userPowerLevel is UserPowerLevel.Value && when (role) {
            Role.Creator -> true
            Role.SuperAdmin -> userPowerLevel.value == UserPowerLevel.SuperAdmin.value
            Role.Admin -> userPowerLevel.value == UserPowerLevel.Admin.value
            Role.Moderator -> userPowerLevel.value == UserPowerLevel.Moderator.value
            Role.User -> userPowerLevel.value == UserPowerLevel.User.value
        } || userPowerLevel == UserPowerLevel.Infinite
        return if (!matchesPreset && userPowerLevel is UserPowerLevel.Value) {
            stringProvider.getString(CommonStrings.room_member_power_level_custom_in, userPowerLevel.value, roomName)
        } else {
            when (role) {
                Role.SuperAdmin,
                Role.Creator -> stringProvider.getString(CommonStrings.room_member_power_level_owner_in, roomName)
                Role.Admin -> stringProvider.getString(CommonStrings.room_member_power_level_admin_in, roomName)
                Role.Moderator -> stringProvider.getString(CommonStrings.room_member_power_level_moderator_in, roomName)
                Role.User -> stringProvider.getString(CommonStrings.room_member_power_level_default_in, roomName)
            }
        }
    }

    private fun handleIgnoreAction() = withState { state ->
        val isIgnored = state.isIgnored() ?: return@withState
        _viewEvents.post(RoomMemberProfileViewEvents.Loading())
        viewModelScope.launch {
            val event = try {
                if (isIgnored) {
                    session.userService().unIgnoreUserIds(listOf(state.userId))
                } else {
                    session.userService().ignoreUserIds(listOf(state.userId))
                }
                RoomMemberProfileViewEvents.OnIgnoreActionSuccess
            } catch (failure: Throwable) {
                RoomMemberProfileViewEvents.Failure(failure)
            }
            _viewEvents.post(event)
        }
    }
}
