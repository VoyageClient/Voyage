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
import im.vector.app.features.imagepack.EmoteShortcodeProcessor
import im.vector.app.features.redaction.MassRedactionManager
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.themes.ThemeProvider
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.android.sdk.api.Matrix
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.accountdata.EncryptedAccountDataService
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.profile.ColorPreference
import org.matrix.android.sdk.api.session.profile.ProfileKeys
import org.matrix.android.sdk.api.session.profile.ProfileOverrides
import org.matrix.android.sdk.api.session.profile.ProfileService
import org.matrix.android.sdk.api.session.profile.UserBio
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
import org.matrix.android.sdk.api.util.fromBase64
import org.matrix.android.sdk.api.util.toMatrixItem
import org.matrix.android.sdk.flow.flow
import org.matrix.android.sdk.flow.unwrap

private const val PROFILE_FETCH_TIMEOUT_MS = 5_000L

class RoomMemberProfileViewModel @AssistedInject constructor(
        @Assisted private val initialState: RoomMemberProfileViewState,
        private val stringProvider: StringProvider,
        private val themeProvider: ThemeProvider,
        private val matrixItemColorProvider: MatrixItemColorProvider,
        private val directRoomHelper: DirectRoomHelper,
        private val massRedactionManager: MassRedactionManager,
        private val profileFieldsFormatter: ProfileFieldsFormatter,
        private val emoteShortcodeProcessor: EmoteShortcodeProcessor,
        private val vectorPreferences: VectorPreferences,
        private val session: Session,
        private val matrix: Matrix
) : VectorViewModel<RoomMemberProfileViewState, RoomMemberProfileAction, RoomMemberProfileViewEvents>(initialState) {

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<RoomMemberProfileViewModel, RoomMemberProfileViewState> {
        override fun create(initialState: RoomMemberProfileViewState): RoomMemberProfileViewModel
    }

    companion object : MavericksViewModelFactory<RoomMemberProfileViewModel, RoomMemberProfileViewState> by hiltMavericksViewModelFactory()

    private var profileColorSameInitialized = false

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
                    // Members render instantly. Anyone else stays Loading until fetchProfileInfo's single
                    // final setState, so the page reveals once and complete rather than field by field;
                    // that setState always lands (bare-mxid fallback), so it cannot get stuck.
                    userMatrixItem = initialRoomMember?.let { Success(it.toMatrixItem()) } ?: Loading(),
                    hasReadReceipt = room?.readService()?.getUserReadReceipt(initialState.userId) != null,
                    isSpace = initialRoomSummary?.roomType == RoomType.SPACE,
                    isHistoricalOrWatchedRoom = initialRoomSummary?.let { it.isRemovedFromRoom || it.isWatched } == true,
                    isRoomEncrypted = initialRoomSummary?.isEncrypted == true,
                    isAlgorithmSupported = initialRoomSummary?.roomEncryptionAlgorithm is RoomEncryptionAlgorithm.SupportedAlgorithm,
                    roomPowerLevels = initialRoomPowerLevels,
                    actionPermissions = initialPermissions,
                    userPowerLevelString = initialUserPowerLevelString?.let { Success(it) } ?: Uninitialized,
                    asyncMembership = initialRoomMember?.membership?.let { Success(it) } ?: Uninitialized,
                    // Seeded synchronously so the banner doesn't pop in a frame late
                    profileJson = session.profileService().getCachedProfile(initialState.userId),
                    globalBannerUrl = session.profileService().getCachedBannerUrl(initialState.userId),
                    status = session.profileService().getCachedStatus(initialState.userId),
                    bio = session.profileService().getCachedBio(initialState.userId),
                    profileFieldsLine = cachedProfileFieldsLine(),
            )
        }
        // No prefetchProfileFields: the paths below already end in getProfile(), and a second
        // concurrent fetch splits the reveal into staggered partial updates.
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

        observeProfileFields()

        // Combined + onEach, not execute: execute's initial Loading would reset the trust state
        // and flash a wrong shield; the shield stays hidden until userCryptoInfoLoaded instead.
        combine(
                session.flow().liveUserCryptoDevices(initialState.userId),
                session.flow().liveCrossSigningInfo(initialState.userId)
        ) { devices, crossSigningInfo ->
            setState {
                copy(
                        userCryptoInfoLoaded = true,
                        userMXCrossSigningInfo = crossSigningInfo.getOrNull(),
                        allDevicesAreTrusted = devices.all { it.isVerified },
                        allDevicesAreCrossSignedTrusted = devices.all { it.trustLevel?.crossSigningVerified == true }
                )
            }
        }.launchIn(viewModelScope)
    }

    private fun observeAccountData() {
        session.flow()
                .liveUserAccountData(UserAccountDataTypes.TYPES_PROFILE_ANNOTATIONS.toSet())
                .onEach { refreshPersonalNote() }
                .launchIn(viewModelScope)

        matrixItemColorProvider.changes
                .onEach { generation -> setState { copy(colorGeneration = generation) } }
                .launchIn(viewModelScope)

        // The in-flight guards: all user account data shares one table, so each of our sequential
        // writes re-notifies every query below, and a mid-sequence read would resurrect the entries
        // the remaining writes are about to clear. trackingAccountDataWrite refreshes once at the end.
        session.flow()
                .liveUserAccountData(UserAccountDataTypes.TYPE_OVERRIDE_COLORS)
                // No unwrap(): removing the last override deletes the event, and that empty
                // emission must still clear the state.
                .onEach { if (accountDataWritesInFlight == 0) refreshLegacyOverrideColor() }
                .launchIn(viewModelScope)

        session.flow()
                .liveUserAccountData(ProfileOverrides.ACCOUNT_DATA_TYPES.toSet())
                .onEach { if (accountDataWritesInFlight == 0) refreshProfileOverrides() }
                .launchIn(viewModelScope)

        // The account data itself does not change when the ADK arrives and encrypted overrides
        // become readable, so also refresh whenever the applied override map swaps.
        ProfileOverrides.changes
                .onEach { if (accountDataWritesInFlight == 0) refreshProfileOverrides() }
                .launchIn(viewModelScope)
    }

    /** The stored profile-overrides content, stable type preferred, decrypted when MSC4483-encrypted. */
    private fun overridesClearContent(): Content? {
        val service = session.encryptedAccountDataService()
        return ProfileOverrides.ACCOUNT_DATA_TYPES.firstNotNullOfOrNull { type ->
            val content = session.accountDataService().getUserAccountDataEvent(type)?.content ?: return@firstNotNullOfOrNull null
            if (service.isEncrypted(content)) {
                service.decryptOrNull(type, content) ?: run {
                    ensureAdkSilently()
                    null
                }
            } else {
                content
            }
        }
    }

    private fun refreshProfileOverrides() {
        applyProfileOverrideFieldsToState(ProfileOverrides.parse(overridesClearContent())[initialState.userId])
    }

    private fun applyProfileOverrideFieldsToState(fields: Map<String, Any?>?) {
        val overrideName = (fields?.get(ProfileOverrides.FIELD_DISPLAY_NAME) as? String)?.takeIf { it.isNotBlank() }
        val overrideAvatar = (fields?.get(ProfileOverrides.FIELD_AVATAR_URL) as? String)?.takeIf { it.isNotBlank() }
        val overrideColor = ColorPreference.parse(fields?.get(ProfileKeys.COLOR_PREFERENCE))
        val base = bestKnownMatrixItem()
        val sameForThemes = if (profileColorSameInitialized) {
            null
        } else {
            profileColorSameInitialized = true
            overrideColor == null || overrideColor.onLight == null || overrideColor.onDark == null ||
                    overrideColor.onLight == overrideColor.onDark
        }
        setState {
            copy(
                    profileOverrideDisplayName = overrideName,
                    profileOverrideAvatarUrl = overrideAvatar,
                    profileOverrideColor = overrideColor,
                    profileColorSameForThemes = sameForThemes ?: profileColorSameForThemes,
                    hasProfileOverrides = !fields.isNullOrEmpty(),
                    // Only rebuild an already-resolved item: while it is Loading, forcing
                    // Success here would dismiss the spinner with stale store data, and
                    // fetchProfileInfo already merges the overrides into its own result.
                    userMatrixItem = if (userMatrixItem is Success) {
                        Success(
                                MatrixItem.UserItem(
                                        initialState.userId,
                                        overrideName ?: base.displayName,
                                        overrideAvatar ?: base.avatarUrl,
                                        colorPreference = (base as? MatrixItem.UserItem)?.colorPreference,
                                )
                        )
                    } else {
                        userMatrixItem
                    },
            )
        }
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
            is RoomMemberProfileAction.SetProfileOverrideColor -> handleSetProfileOverrideColor(action)
            is RoomMemberProfileAction.SetProfileColorSameForThemes -> setState { copy(profileColorSameForThemes = action.same) }
            is RoomMemberProfileAction.SetProfileOverrideDisplayName -> handleSetProfileOverrideDisplayName(action)
            is RoomMemberProfileAction.SetProfileOverrideAvatar -> handleSetProfileOverrideAvatar(action)
            RoomMemberProfileAction.ResetProfileOverrides -> handleResetProfileOverrides()
            is RoomMemberProfileAction.OpenOrCreateDm -> handleOpenOrCreateDm(action)
            is RoomMemberProfileAction.SetPersonalNote -> handleSetPersonalNote(action)
            RoomMemberProfileAction.RevertPersonalNote -> {
                setState { copy(personalNoteGeneration = personalNoteGeneration + 1) }
                refreshPersonalNote()
            }
            is RoomMemberProfileAction.GotAdkFromSsss -> handleGotAdkFromSsss(action)
            RoomMemberProfileAction.AbortPendingOverrideUpdate -> {
                pendingOverridesMutate = null
                matrixItemColorProvider.clearOptimisticOverride(initialState.userId)
                refreshProfileOverrides()
            }
        }
    }

    // ==================== MSC4441 personal notes ====================

    /** The stored profile-annotations account data, stable type preferred. */
    private fun annotationsEvent(): Pair<String, Content>? {
        return UserAccountDataTypes.TYPES_PROFILE_ANNOTATIONS.firstNotNullOfOrNull { type ->
            session.accountDataService().getUserAccountDataEvent(type)?.let { type to it.content }
        }
    }

    private var adkSilentAttempted = false

    /** The 4S key may be cached from an earlier unlock: try to fetch the ADK silently, once. */
    private fun ensureAdkSilently() {
        if (adkSilentAttempted) return
        adkSilentAttempted = true
        viewModelScope.launch {
            if (session.encryptedAccountDataService().ensureAccountDataKey()) {
                refreshPersonalNote()
                refreshProfileOverrides()
            }
        }
    }

    private fun refreshPersonalNote() {
        val service = session.encryptedAccountDataService()
        val (type, content) = annotationsEvent() ?: run {
            setState { copy(personalNote = null, personalNoteLocked = false) }
            return
        }
        val clear = if (service.isEncrypted(content)) {
            service.takeIf { it.hasAccountDataKey() }?.decryptOrNull(type, content) ?: run {
                setState { copy(personalNote = null, personalNoteLocked = true) }
                ensureAdkSilently()
                return
            }
        } else {
            content
        }
        setState { copy(personalNote = parsePersonalNote(clear[initialState.userId]), personalNoteLocked = false) }
    }

    /** The user's annotation entry holds an MSC1767 m.text array; pick the plain and html representations. */
    private fun parsePersonalNote(entry: Any?): UserBio? {
        val texts = (entry as? Map<*, *>)?.get("m.text") as? List<*> ?: return null
        var plain: String? = null
        var html: String? = null
        texts.forEach { representation ->
            val body = (representation as? Map<*, *>)?.get("body") as? String ?: return@forEach
            when (representation["mimetype"] as? String ?: MimeTypes.PlainText) {
                MimeTypes.Html -> if (html == null) html = body
                MimeTypes.PlainText -> if (plain == null) plain = body
            }
        }
        return UserBio(plain ?: html ?: return null, html).takeIf { !it.isEmpty() }
    }

    private fun handleSetPersonalNote(action: RoomMemberProfileAction.SetPersonalNote) {
        viewModelScope.launch {
            accountDataWriteMutex.withLock {
                try {
                    val service = session.encryptedAccountDataService()
                    val existing = annotationsEvent()
                    val existingEncrypted = existing != null && service.isEncrypted(existing.second)
                    val encryptWrites = vectorPreferences.encryptAccountData()
                    // The ADK is needed to write encrypted, or to rewrite an already-encrypted store;
                    // when it cannot be acquired silently, the UI runs the recovery-key flow.
                    if ((encryptWrites || existingEncrypted) && !service.ensureAccountDataKey()) {
                        _viewEvents.post(RoomMemberProfileViewEvents.RequirePersonalNoteAdk(action.note))
                        return@withLock
                    }
                    val annotations = when {
                        existing == null -> emptyMap()
                        existingEncrypted -> service.decrypt(existing.first, existing.second)
                        else -> existing.second
                    }.toMutableMap()
                    val note = action.note?.trim()?.takeIf { it.isNotEmpty() }
                    if (note == null) {
                        annotations.remove(initialState.userId)
                    } else {
                        // Preserve any other annotation fields stored on this user
                        val entry = (annotations[initialState.userId] as? Map<*, *>)
                                .orEmpty()
                                .entries
                                .mapNotNull { (key, value) -> (key as? String)?.let { it to value } }
                                .toMap()
                                .toMutableMap()
                        entry["m.text"] = noteRepresentations(note)
                        annotations[initialState.userId] = entry
                    }
                    // Optimistic: render the new note now rather than after the server round-trip
                    setState { copy(personalNote = parsePersonalNote(annotations[initialState.userId]), personalNoteLocked = false) }
                    UserAccountDataTypes.TYPES_PROFILE_ANNOTATIONS.forEach { type ->
                        val payload = if (encryptWrites) service.encrypt(type, annotations) else annotations
                        session.accountDataService().updateUserAccountData(type, payload)
                    }
                    refreshPersonalNote()
                } catch (failure: Throwable) {
                    // Roll back the optimistic rendering to what is actually stored
                    refreshPersonalNote()
                    _viewEvents.post(RoomMemberProfileViewEvents.Failure(failure))
                }
            }
        }
    }

    // Commonmark folds any run of blank lines into a single paragraph break, which would silently
    // flatten the spacing someone laid their note out with. Each blank line past the first becomes a
    // raw <br /> block, which the parser passes through untouched. Code fences are left alone: their
    // blank lines are content. (Same treatment as the biography editor.)
    private fun String.withBlankLinesKept(): String {
        if (contains("```")) return this
        return replace(Regex("\n{3,}")) { match -> "\n\n" + "<br />\n\n".repeat(match.value.length - 2) }
    }

    /**
     * MSC1767 representations for the note: the typed source as plain text, plus HTML when it
     * actually carries formatting — markdown and `:shortcode:` emotes resolved exactly as a
     * message's (and a biography's) are.
     */
    private suspend fun noteRepresentations(source: String): List<Map<String, String>> {
        val plain = mapOf("body" to source)
        val withEmotes = withContext(Dispatchers.IO) {
            emoteShortcodeProcessor.process(roomId = null, text = source.withBlankLinesKept())
        }
        val html = tryOrNull { session.roomService().computeFormattedHtml(withEmotes, autoMarkdown = true) }
                ?: return listOf(plain)
        return listOf(mapOf("mimetype" to MimeTypes.Html, "body" to html), plain)
    }

    private fun handleGotAdkFromSsss(action: RoomMemberProfileAction.GotAdkFromSsss) {
        try {
            val secrets = action.cipher.fromBase64().inputStream().use {
                matrix.secureStorageService().loadSecureSecret<Map<String, String>>(it, action.alias)
            }
            val adk = EncryptedAccountDataService.ADK_SECRET_NAMES.firstNotNullOfOrNull { secrets?.get(it) }
                    ?: throw IllegalStateException(stringProvider.getString(CommonStrings.failed_to_access_secure_storage))
            session.encryptedAccountDataService().setAccountDataKey(adk)
            refreshPersonalNote()
            if (action.pendingNote != null) {
                handleSetPersonalNote(RoomMemberProfileAction.SetPersonalNote(action.pendingNote))
            }
            pendingOverridesMutate?.let {
                pendingOverridesMutate = null
                updateProfileOverrideFields(it)
            }
        } catch (failure: Throwable) {
            _viewEvents.post(RoomMemberProfileViewEvents.Failure(failure))
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
        matrixItemColorProvider.setOptimisticOverride(initialState.userId, null)
        updateProfileOverrideFields { it.clear() }
        writeLegacyOverrideColor(null)
    }

    private fun handleSetProfileOverrideColor(action: RoomMemberProfileAction.SetProfileOverrideColor) {
        val color = action.color?.takeIf { !it.isEmpty() }
        // Optimistic in the provider so name, avatar, reply headers and this row recolor in one pass,
        // rather than waiting on the two account-data writes below (which land async and out of order).
        matrixItemColorProvider.setOptimisticOverride(initialState.userId, color)
        updateProfileOverrideFields { fields ->
            val json = color?.toJson()?.filterValues { it != null }
            if (json != null) fields[ProfileKeys.COLOR_PREFERENCE] = json else fields.remove(ProfileKeys.COLOR_PREFERENCE)
        }
        writeLegacyOverrideColor(color?.forTheme(themeProvider.isLightTheme()))
    }

    // Both account-data writers read-modify-write a whole-object PUT, so rapid changes must run one at
    // a time and read the freshest state inside the lock — otherwise a stale in-flight write from an
    // earlier change lands last and resurrects the value it captured.
    private val accountDataWriteMutex = Mutex()

    // Main-thread only, like the flow collectors that read it.
    private var accountDataWritesInFlight = 0

    private suspend fun trackingAccountDataWrite(block: suspend () -> Unit) {
        accountDataWritesInFlight++
        try {
            block()
        } finally {
            if (--accountDataWritesInFlight == 0) {
                refreshProfileOverrides()
                refreshLegacyOverrideColor()
            }
        }
    }

    private fun refreshLegacyOverrideColor() {
        val stored = session.accountDataService().getUserAccountDataEvent(UserAccountDataTypes.TYPE_OVERRIDE_COLORS)
                ?.content?.toModel<Map<String, String>>()?.get(initialState.userId)
        setState { copy(userColorOverride = stored) }
    }

    // Mirror the override into the legacy per-user color account data, for older clients that only read that.
    private fun writeLegacyOverrideColor(hex: String?) {
        // Before the lock: the profile-overrides writer holds the mutex across its network PUTs,
        // and disabling the reset action needs this cleared alongside hasProfileOverrides.
        setState { copy(userColorOverride = hex) }
        viewModelScope.launch {
            trackingAccountDataWrite {
                accountDataWriteMutex.withLock {
                    val specs = session.accountDataService()
                            .getUserAccountDataEvent(UserAccountDataTypes.TYPE_OVERRIDE_COLORS)
                            ?.content
                            ?.toModel<Map<String, String>>()
                            .orEmpty()
                            .toMutableMap()
                    val changed = if (hex != null) specs.put(initialState.userId, hex) != hex else specs.remove(initialState.userId) != null
                    if (!changed) return@withLock
                    try {
                        session.accountDataService().updateUserAccountData(UserAccountDataTypes.TYPE_OVERRIDE_COLORS, specs)
                    } catch (failure: Throwable) {
                        _viewEvents.post(RoomMemberProfileViewEvents.Failure(failure))
                    }
                }
            }
        }
    }

    private var pendingOverridesMutate: ((MutableMap<String, Any?>) -> Unit)? = null

    /**
     * Rewrites this user's entry of the profile-overrides account data; an emptied entry is removed.
     * Written MSC4483-encrypted when the labs toggle is on, plaintext when it is off — either way
     * the stored event is converted to the target form on the way through.
     */
    private fun updateProfileOverrideFields(mutate: (MutableMap<String, Any?>) -> Unit) {
        viewModelScope.launch {
            trackingAccountDataWrite {
                accountDataWriteMutex.withLock {
                    val service = session.encryptedAccountDataService()
                    val existing = ProfileOverrides.ACCOUNT_DATA_TYPES
                            .firstNotNullOfOrNull { type -> session.accountDataService().getUserAccountDataEvent(type)?.content?.let { type to it } }
                    val existingEncrypted = existing != null && service.isEncrypted(existing.second)
                    val encryptWrites = vectorPreferences.encryptAccountData()
                    val adkAvailable = (existingEncrypted || encryptWrites) && service.ensureAccountDataKey()
                    // Without the ADK an encrypted store cannot be read-modified at all: run the
                    // recovery-key flow and replay this update once the key is in. A plaintext store
                    // just skips the upgrade for this write.
                    if (existingEncrypted && !adkAvailable) {
                        pendingOverridesMutate = mutate
                        matrixItemColorProvider.clearOptimisticOverride(initialState.userId)
                        _viewEvents.post(RoomMemberProfileViewEvents.StopLoading)
                        _viewEvents.post(RoomMemberProfileViewEvents.RequireProfileOverridesAdk)
                        return@withLock
                    }
                    val content = try {
                        when {
                            existing == null -> emptyMap()
                            existingEncrypted -> service.decrypt(existing.first, existing.second)
                            else -> existing.second
                        }.toMutableMap()
                    } catch (failure: Throwable) {
                        // Undecryptable despite the ADK: bail out rather than clobber the stored map
                        matrixItemColorProvider.clearOptimisticOverride(initialState.userId)
                        _viewEvents.post(RoomMemberProfileViewEvents.StopLoading)
                        _viewEvents.post(RoomMemberProfileViewEvents.Failure(failure))
                        return@withLock
                    }
                    val fields = (content[initialState.userId] as? Map<*, *>)
                            .orEmpty()
                            .entries
                            .mapNotNull { (key, value) -> (key as? String)?.let { it to value } }
                            .toMap()
                            .toMutableMap()
                    mutate(fields)
                    if (fields.isEmpty()) content.remove(initialState.userId) else content[initialState.userId] = fields
                    // Optimistic: the SDK only persists the local echo after the server PUT lands,
                    // so waiting on the account-data flow would leave the UI stale for the round-trip.
                    applyProfileOverrideFieldsToState(fields.takeIf { it.isNotEmpty() })
                    try {
                        ProfileOverrides.ACCOUNT_DATA_TYPES.forEach { type ->
                            val payload = if (content.isNotEmpty() && encryptWrites && adkAvailable) service.encrypt(type, content) else content
                            session.accountDataService().updateUserAccountData(type, payload)
                        }
                        _viewEvents.post(RoomMemberProfileViewEvents.StopLoading)
                    } catch (failure: Throwable) {
                        // The optimistic color and state assumed this write would land; the end-of-write
                        // refresh in trackingAccountDataWrite reverts the state to what is stored.
                        matrixItemColorProvider.clearOptimisticOverride(initialState.userId)
                        _viewEvents.post(RoomMemberProfileViewEvents.StopLoading)
                        _viewEvents.post(RoomMemberProfileViewEvents.Failure(failure))
                    }
                }
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
            // A dead homeserver otherwise rides the full connect-timeout ladder while the spinner
            // spins; give up and reveal the blank fallback profile instead.
            withTimeoutOrNull(PROFILE_FETCH_TIMEOUT_MS) {
                session.profileService().getProfile(initialState.userId)
            }
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

    /**
     * The profile fields are seeded from the cache when the screen opens, which on a first visit is before
     * anything has been fetched. Whichever request fills the cache — this screen's, or a prefetch started
     * elsewhere — says so here, and the screen picks the fields up instead of waiting to be reopened.
     */
    private fun observeProfileFields() {
        session.profileService().getProfileUpdateFlow()
                .filter { it == initialState.userId }
                .onEach {
                    setState {
                        copy(
                                globalBannerUrl = session.profileService().getCachedBannerUrl(initialState.userId) ?: globalBannerUrl,
                                profileJson = session.profileService().getCachedProfile(initialState.userId) ?: profileJson,
                                status = session.profileService().getCachedStatus(initialState.userId),
                                bio = session.profileService().getCachedBio(initialState.userId),
                                profileFieldsLine = cachedProfileFieldsLine(),
                        )
                    }
                }
                .launchIn(viewModelScope)
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
            val withRoomState = copy(isHistoricalOrWatchedRoom = summary.isRemovedFromRoom || summary.isWatched)
            if (summary.isEncrypted) {
                withRoomState.copy(
                        isRoomEncrypted = true,
                        isAlgorithmSupported = summary.roomEncryptionAlgorithm is RoomEncryptionAlgorithm.SupportedAlgorithm
                )
            } else {
                withRoomState.copy(isRoomEncrypted = false)
            }
        }
        // onEach, not execute: execute's initial Loading would wipe the synchronously seeded
        // value and blank the header line until the DB flow's first emission arrives.
        roomSummaryLive.combine(powerLevelsFlow) { roomSummary, roomPowerLevels ->
            computeUserPowerLevelString(roomPowerLevels, roomSummary)
        }.onEach {
            setState { copy(userPowerLevelString = Success(it)) }
        }.launchIn(viewModelScope)
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
