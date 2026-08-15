/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.personalization

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreferenceDialogFragmentCompat
import androidx.preference.Preference
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.dialogs.GalleryOrCameraDialogHelper
import im.vector.app.core.dialogs.GalleryOrCameraDialogHelperFactory
import im.vector.app.core.intent.getFilenameFromUri
import im.vector.app.core.preference.UserAvatarPreference
import im.vector.app.core.preference.VectorEditTextPreference
import im.vector.app.core.preference.VectorListPreference
import im.vector.app.core.preference.VectorPreference
import im.vector.app.core.preference.VectorPreferenceCategory
import im.vector.app.core.preference.VectorPreferenceCategoryWithAction
import im.vector.app.features.home.room.detail.timeline.tools.messageEmojiSpanify
import im.vector.app.features.home.room.detail.timeline.tools.setupLiveEmojiInput
import im.vector.app.features.redaction.preservation.RedactedContentRevealManager
import im.vector.app.features.redaction.preservation.RedactionCacheCleaner
import im.vector.app.features.redaction.preservation.RedactionPreservationSettings
import im.vector.app.features.settings.MediaPreviewMode
import im.vector.app.features.settings.PrivacyMode
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.settings.VectorSettingsBaseFragment
import im.vector.app.features.settings.admin.ServerAdminStatusDataSource
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.session.user.model.User
import org.matrix.android.sdk.flow.flow
import org.matrix.android.sdk.flow.mapOptional
import org.matrix.android.sdk.flow.unwrap
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class RoomPersonalizationSettingsFragment :
        VectorSettingsBaseFragment(),
        GalleryOrCameraDialogHelper.Listener {

    @Inject lateinit var galleryOrCameraDialogHelperFactory: GalleryOrCameraDialogHelperFactory
    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var redactionSettings: RedactionPreservationSettings
    @Inject lateinit var serverAdminStatusDataSource: ServerAdminStatusDataSource
    @Inject lateinit var redactedContentRevealManager: RedactedContentRevealManager
    @Inject lateinit var redactionCacheCleaner: RedactionCacheCleaner

    override var titleRes = CommonStrings.room_profile_section_more_personalization
    override val preferenceXmlRes = R.xml.vector_settings_room_personalization

    private lateinit var galleryOrCameraDialogHelper: GalleryOrCameraDialogHelper

    private val roomId: String by lazy { requireArguments().getString(ARG_ROOM_ID)!! }
    private val room: Room by lazy { session.getRoom(roomId)!! }

    private var memberContentLoaded = false
    private var currentAvatarUrl: String? = null
    private var currentDisplayName: String? = null
    private var accountDisplayName: String? = null
    private var accountAvatarUrl: String? = null

    private val avatarPreference by lazy {
        findPreference<UserAvatarPreference>("SETTINGS_ROOM_PERSONALIZATION_AVATAR_KEY")!!
    }
    private val displayNamePreference by lazy {
        findPreference<VectorEditTextPreference>("SETTINGS_ROOM_PERSONALIZATION_DISPLAY_NAME_KEY")!!
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fixed construction order: launcher registration must be deterministic across process death.
        galleryOrCameraDialogHelper = galleryOrCameraDialogHelperFactory.create(this)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadAccountProfile()
        observeMyMembership()
        setUpProfileReset()
        setUpMediaOverrides()
        setUpRedactionOverrides()
        setUpRoomRedactionCacheClear()
    }

    private val profileCategory
        get() = findPreference<VectorPreferenceCategoryWithAction>("SETTINGS_ROOM_PERSONALIZATION_CATEGORY_KEY")

    private fun setUpProfileReset() {
        profileCategory?.apply {
            isActionEnabled = isPersonalized()
            actionClickListener = {
                AlertDialog.Builder(requireContext())
                        .setTitle(CommonStrings.action_reset)
                        .setMessage(CommonStrings.room_personalization_reset_confirmation)
                        .setPositiveButton(CommonStrings.action_reset) { _, _ -> resetToAccountProfile() }
                        .setNegativeButton(CommonStrings.action_cancel, null)
                        .show()
            }
        }
    }

    private fun refreshProfileResetState() {
        profileCategory?.isActionEnabled = isPersonalized()
    }

    private fun setUpMediaOverrides() {
        val modePref = findPreference<VectorListPreference>(SETTINGS_ROOM_MEDIA_PREVIEW_KEY)
        modePref?.value = vectorPreferences.getRoomMediaPreviewOverride(roomId)?.value ?: INHERIT
        modePref?.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue as? String
            vectorPreferences.setRoomMediaPreviewOverride(
                    roomId,
                    if (value == INHERIT) null else MediaPreviewMode.fromValue(value)
            )
            true
        }

        val randomizePref = findPreference<VectorListPreference>(SETTINGS_ROOM_RANDOMIZE_FILENAMES_KEY)
        randomizePref?.value = vectorPreferences.getRoomRandomizeFilenamesOverride(roomId).toValue()
        randomizePref?.setOnPreferenceChangeListener { _, newValue ->
            vectorPreferences.setRoomRandomizeFilenamesOverride(roomId, (newValue as? String).toOverride())
            true
        }

        val stripPref = findPreference<VectorListPreference>(SETTINGS_ROOM_STRIP_METADATA_KEY)
        stripPref?.value = vectorPreferences.getRoomStripMetadataOverride(roomId).toValue()
        stripPref?.setOnPreferenceChangeListener { _, newValue ->
            vectorPreferences.setRoomStripMetadataOverride(roomId, (newValue as? String).toOverride())
            true
        }
    }

    private fun Boolean?.toValue() = when (this) {
        true -> PrivacyMode.ALWAYS.value
        false -> PrivacyMode.NEVER.value
        null -> INHERIT
    }

    private fun String?.toOverride() = when (this) {
        PrivacyMode.ALWAYS.value -> true
        PrivacyMode.NEVER.value -> false
        else -> null
    }

    private fun setUpRedactionOverrides() {
        // Preserving anything here means asking the server, which needs both a privileged user and a
        // server that implements MSC2815. Without either there is nothing to configure.
        val canUseMsc2815 = session.homeServerCapabilitiesService().getHomeServerCapabilities().canViewUnredactedContent &&
                (room.stateService().getRoomPowerLevels().isUserAbleToRedact(session.myUserId) ||
                        serverAdminStatusDataSource.cachedStatus().mayBeAdmin)
        if (!canUseMsc2815) {
            findPreference<VectorPreferenceCategory>(SETTINGS_ROOM_REDACTION_CATEGORY_KEY)?.isVisible = false
            return
        }

        bindTriState(SETTINGS_ROOM_REDACTION_PRESERVE_KEY,
                current = redactionSettings.roomPreserveRedactedOverride(roomId)) {
            redactionSettings.setRoomPreserveRedactedOverride(roomId, it)
        }

        bindTriState(SETTINGS_ROOM_REDACTION_CLEAR_KEY,
                current = redactionSettings.roomClearWithAppCacheOverride(roomId)) {
            redactionSettings.setRoomClearWithAppCacheOverride(roomId, it)
        }

        bindTriState(SETTINGS_ROOM_REDACTION_WIFI_ONLY_KEY,
                current = redactionSettings.roomWifiOnlyOverride(roomId)) {
            redactionSettings.setRoomWifiOnlyOverride(roomId, it)
        }

        bindTriState(SETTINGS_ROOM_REDACTION_PRESERVE_MEDIA_KEY,
                current = redactionSettings.roomPreserveMediaOverride(roomId)) {
            redactionSettings.setRoomPreserveMediaOverride(roomId, it)
        }

        findPreference<VectorListPreference>(SETTINGS_ROOM_REDACTION_MAX_MEDIA_SIZE_KEY)?.let { pref ->
            pref.value = redactionSettings.roomMaxMediaSizeOverride(roomId)?.toString() ?: INHERIT
            pref.setOnPreferenceChangeListener { _, newValue ->
                val value = newValue as? String
                redactionSettings.setRoomMaxMediaSizeOverride(roomId, if (value == INHERIT) null else value?.toLongOrNull())
                true
            }
        }
    }

    private fun setUpRoomRedactionCacheClear() {
        findPreference<VectorPreference>(SETTINGS_ROOM_REDACTION_CLEAR_NOW_KEY)?.onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    AlertDialog.Builder(requireContext())
                            .setTitle(CommonStrings.room_personalization_redaction_clear_media_now)
                            .setMessage(CommonStrings.room_personalization_redaction_clear_now_confirmation)
                            .setPositiveButton(CommonStrings.action_delete) { _, _ ->
                                lifecycleScope.launch {
                                    displayLoadingView()
                                    redactionCacheCleaner.clearRoomMediaCache(roomId)
                                    if (!isAdded) return@launch
                                    hideLoadingView()
                                }
                            }
                            .setNegativeButton(CommonStrings.action_cancel, null)
                            .show()
                            .also { dialog ->
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                                        ?.setTextColor(ThemeUtils.getColor(requireContext(), com.google.android.material.R.attr.colorError))
                            }
                    false
                }
    }

    /** A yes/no/inherit list backed by a nullable override, where null means inherit. */
    private fun bindTriState(key: String, current: Boolean?, save: (Boolean?) -> Unit) {
        val pref = findPreference<VectorListPreference>(key) ?: return
        pref.value = current?.toString() ?: INHERIT
        pref.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue as? String
            save(if (value == INHERIT) null else value.toBoolean())
            true
        }
    }

    private fun loadAccountProfile() {
        lifecycleScope.launch {
            val user = tryOrNull { session.userService().resolveUser(session.myUserId) }
                    ?: session.userService().getUser(session.myUserId)
            accountDisplayName = user?.displayName?.takeIf { it.isNotBlank() }
            accountAvatarUrl = user?.avatarUrl
            displayNamePreference.setOnBindEditTextListener { editText ->
                // An empty field means no display name at all in this room, i.e. the Matrix ID is shown.
                editText.hint = session.myUserId
                editText.setupLiveEmojiInput()
                messageEmojiSpanify?.applyLive(editText.text)
            }
            refreshProfileResetState()
        }
    }

    private fun observeMyMembership() {
        room.flow()
                .liveStateEvent(EventType.STATE_ROOM_MEMBER, QueryStringValue.Equals(session.myUserId))
                .mapOptional { it.content.toModel<RoomMemberContent>() }
                .unwrap()
                .distinctUntilChanged()
                .onEach { content ->
                    memberContentLoaded = true
                    // "" means explicitly blanked (see DefaultStateService), same as absent for display purposes.
                    currentAvatarUrl = content.avatarUrl?.takeIf { it.isNotEmpty() }
                    currentDisplayName = content.displayName?.takeIf { it.isNotBlank() }
                    avatarPreference.refreshAvatar(
                            User(session.myUserId, currentDisplayName, currentAvatarUrl)
                    )
                    displayNamePreference.let {
                        it.text = currentDisplayName
                        it.summary = currentDisplayName ?: session.myUserId
                    }
                    refreshProfileResetState()
                }
                .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    override fun bindPref() {
        avatarPreference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            galleryOrCameraDialogHelper.show(
                    withDeleteOption = !currentAvatarUrl.isNullOrEmpty(),
                    withResetOption = currentAvatarUrl != accountAvatarUrl,
                    resetActionTitle = CommonStrings.room_personalization_reset
            )
            false
        }

        displayNamePreference.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            (newValue as? String)?.trim()?.let { onDisplayNameChanged(it) }
            false
        }
    }

    override fun onImageReady(uri: Uri?) {
        if (uri == null) {
            Toast.makeText(requireContext(), CommonStrings.error_handling_incoming_share, Toast.LENGTH_SHORT).show()
            return
        }
        displayLoadingView()
        lifecycleScope.launch {
            val result = runCatching {
                room.stateService().updateMyRoomAvatar(uri.toString(), getFilenameFromUri(context, uri) ?: UUID.randomUUID().toString())
            }
            if (!isAdded) return@launch
            hideLoadingView()
            result.onFailure { displayErrorDialog(it) }
        }
    }

    override fun onImageDeleted() {
        // Remove the avatar in this room entirely (others see the placeholder).
        applyAvatar("")
    }

    override fun onImageReset() {
        applyAvatar(null)
    }

    // null resets to the account-wide avatar (field omitted, the server re-fills it); "" removes it.
    private fun applyAvatar(avatarUrl: String?) {
        displayLoadingView()
        lifecycleScope.launch {
            val result = runCatching { room.stateService().resetMyRoomAvatar(avatarUrl) }
            if (!isAdded) return@launch
            hideLoadingView()
            result.onFailure { displayErrorDialog(it) }
        }
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference.key == displayNamePreference.key) {
            if (parentFragmentManager.findFragmentByTag(DISPLAY_NAME_DIALOG_TAG) != null) return
            DisplayNameDialogFragment.newInstance(preference.key, withResetOption = currentDisplayName != accountDisplayName).apply {
                @Suppress("DEPRECATION")
                setTargetFragment(this@RoomPersonalizationSettingsFragment, 0)
            }.show(parentFragmentManager, DISPLAY_NAME_DIALOG_TAG)
        } else {
            super.onDisplayPreferenceDialog(preference)
        }
    }

    private fun onDisplayNameChanged(value: String) {
        // An empty value blanks the display name in this room (the Matrix ID is shown instead).
        applyDisplayName(value)
    }

    fun onDisplayNameReset() {
        applyDisplayName(null)
    }

    // null resets to the account-wide name (field omitted, the server re-fills it); "" blanks it.
    private fun applyDisplayName(newDisplayName: String?) {
        val expected = if (newDisplayName == null) accountDisplayName else newDisplayName.takeIf { it.isNotBlank() }
        if (memberContentLoaded && expected == currentDisplayName) return
        displayLoadingView()
        lifecycleScope.launch {
            val result = runCatching { room.stateService().updateMyRoomDisplayName(newDisplayName) }
            if (!isAdded) return@launch
            hideLoadingView()
            result.onFailure { displayErrorDialog(it) }
        }
    }

    fun isPersonalized() = currentDisplayName != accountDisplayName || currentAvatarUrl != accountAvatarUrl

    fun resetToAccountProfile() {
        displayLoadingView()
        lifecycleScope.launch {
            val result = runCatching { room.stateService().updateMyRoomProfile(null, null) }
            if (!isAdded) return@launch
            hideLoadingView()
            result.onFailure { displayErrorDialog(it) }
        }
    }

    class DisplayNameDialogFragment : EditTextPreferenceDialogFragmentCompat() {

        override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
            super.onPrepareDialogBuilder(builder)
            if (requireArguments().getBoolean(ARG_WITH_RESET)) {
                builder.setNeutralButton(CommonStrings.room_personalization_reset) { _, _ ->
                    @Suppress("DEPRECATION")
                    (targetFragment as? RoomPersonalizationSettingsFragment)?.onDisplayNameReset()
                }
            }
        }

        override fun onStart() {
            super.onStart()
            (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(
                    ThemeUtils.getColor(requireContext(), com.google.android.material.R.attr.colorError)
            )
        }

        companion object {
            private const val ARG_WITH_RESET = "ARG_WITH_RESET"

            fun newInstance(key: String, withResetOption: Boolean) = DisplayNameDialogFragment().apply {
                arguments = Bundle().apply {
                    putString("key", key)
                    putBoolean(ARG_WITH_RESET, withResetOption)
                }
            }
        }
    }

    companion object {
        private const val ARG_ROOM_ID = "ARG_ROOM_ID"
        private const val DISPLAY_NAME_DIALOG_TAG = "DISPLAY_NAME_DIALOG_TAG"

        // Selected when a room defers to the account-wide value; never persisted as an override.
        private const val INHERIT = "inherit"

        private const val SETTINGS_ROOM_MEDIA_PREVIEW_KEY = "SETTINGS_ROOM_MEDIA_PREVIEW_KEY"
        private const val SETTINGS_ROOM_RANDOMIZE_FILENAMES_KEY = "SETTINGS_ROOM_RANDOMIZE_FILENAMES_KEY"
        private const val SETTINGS_ROOM_STRIP_METADATA_KEY = "SETTINGS_ROOM_STRIP_METADATA_KEY"
        private const val SETTINGS_ROOM_REDACTION_CATEGORY_KEY = "SETTINGS_ROOM_REDACTION_CATEGORY_KEY"
        private const val SETTINGS_ROOM_REDACTION_PRESERVE_KEY = "SETTINGS_ROOM_REDACTION_PRESERVE_KEY"
        private const val SETTINGS_ROOM_REDACTION_CLEAR_KEY = "SETTINGS_ROOM_REDACTION_CLEAR_KEY"
        private const val SETTINGS_ROOM_REDACTION_CLEAR_NOW_KEY = "SETTINGS_ROOM_REDACTION_CLEAR_NOW_KEY"
        private const val SETTINGS_ROOM_REDACTION_PRESERVE_MEDIA_KEY = "SETTINGS_ROOM_REDACTION_PRESERVE_MEDIA_KEY"
        private const val SETTINGS_ROOM_REDACTION_MAX_MEDIA_SIZE_KEY = "SETTINGS_ROOM_REDACTION_MAX_MEDIA_SIZE_KEY"
        private const val SETTINGS_ROOM_REDACTION_WIFI_ONLY_KEY = "SETTINGS_ROOM_REDACTION_WIFI_ONLY_KEY"

        fun newInstance(roomId: String) = RoomPersonalizationSettingsFragment().apply {
            arguments = Bundle().apply { putString(ARG_ROOM_ID, roomId) }
        }
    }
}
