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
import im.vector.app.features.home.room.detail.timeline.tools.messageEmojiSpanify
import im.vector.app.features.home.room.detail.timeline.tools.setupLiveEmojiInput
import im.vector.app.R
import im.vector.app.core.dialogs.GalleryOrCameraDialogHelper
import im.vector.app.core.dialogs.GalleryOrCameraDialogHelperFactory
import im.vector.app.core.intent.getFilenameFromUri
import im.vector.app.core.preference.UserAvatarPreference
import im.vector.app.core.preference.UserBannerPreference
import im.vector.app.core.preference.VectorEditTextPreference
import im.vector.app.features.settings.VectorSettingsBaseFragment
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
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

    override var titleRes = CommonStrings.room_profile_section_more_personalization
    override val preferenceXmlRes = R.xml.vector_settings_room_personalization

    private lateinit var galleryOrCameraDialogHelper: GalleryOrCameraDialogHelper
    private lateinit var bannerGalleryOrCameraDialogHelper: GalleryOrCameraDialogHelper

    private val roomId: String by lazy { requireArguments().getString(ARG_ROOM_ID)!! }
    private val room: Room by lazy { session.getRoom(roomId)!! }

    private var memberContentLoaded = false
    private var currentAvatarUrl: String? = null
    private var currentDisplayName: String? = null
    // Raw member-event banner field: "" = explicitly hidden in this room. Global banner changes are
    // propagated into every member event (like avatars), so a value merely equal to the account
    // banner is not a personalization — only a differing one is.
    private var currentBannerOverride: String? = null
    private var accountDisplayName: String? = null
    private var accountAvatarUrl: String? = null
    private var accountBannerUrl: String? = null

    private val avatarPreference by lazy {
        findPreference<UserAvatarPreference>("SETTINGS_ROOM_PERSONALIZATION_AVATAR_KEY")!!
    }
    private val bannerPreference by lazy {
        findPreference<UserBannerPreference>("SETTINGS_ROOM_PERSONALIZATION_BANNER_KEY")!!
    }
    private val displayNamePreference by lazy {
        findPreference<VectorEditTextPreference>("SETTINGS_ROOM_PERSONALIZATION_DISPLAY_NAME_KEY")!!
    }

    private val bannerListener = object : GalleryOrCameraDialogHelper.Listener {
        override fun onImageReady(uri: Uri?) {
            if (uri == null) {
                Toast.makeText(requireContext(), CommonStrings.error_handling_incoming_share, Toast.LENGTH_SHORT).show()
                return
            }
            displayLoadingView()
            lifecycleScope.launch {
                val result = runCatching {
                    room.stateService().updateMyRoomBanner(uri, getFilenameFromUri(context, uri) ?: UUID.randomUUID().toString())
                }
                if (!isAdded) return@launch
                hideLoadingView()
                result.onFailure { displayErrorDialog(it) }
            }
        }

        override fun onImageDeleted() {
            // Hide the banner in this room entirely
            applyBanner("")
        }

        override fun onImageReset() {
            applyBanner(null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fixed construction order: launcher registration must be deterministic across process death.
        galleryOrCameraDialogHelper = galleryOrCameraDialogHelperFactory.create(this)
        bannerGalleryOrCameraDialogHelper = galleryOrCameraDialogHelperFactory.create(
                this,
                GalleryOrCameraDialogHelper.Aspect.BANNER,
                bannerListener
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadAccountProfile()
        observeMyMembership()
    }

    private fun loadAccountProfile() {
        accountBannerUrl = session.profileService().getCachedBannerUrl(session.myUserId)
        refreshBannerPreference()
        lifecycleScope.launch {
            val user = tryOrNull { session.userService().resolveUser(session.myUserId) }
                    ?: session.userService().getUser(session.myUserId)
            accountDisplayName = user?.displayName?.takeIf { it.isNotBlank() }
            accountAvatarUrl = user?.avatarUrl
            accountBannerUrl = tryOrNull { session.profileService().getBannerUrl(session.myUserId).getOrNull() }
            refreshBannerPreference()
            displayNamePreference.setOnBindEditTextListener { editText ->
                // An empty field means no display name at all in this room, i.e. the Matrix ID is shown.
                editText.hint = session.myUserId
                editText.setupLiveEmojiInput()
                messageEmojiSpanify?.applyLive(editText.text)
            }
            activity?.invalidateOptionsMenu()
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
                    currentBannerOverride = content.bannerUrl
                    avatarPreference.refreshAvatar(
                            User(session.myUserId, currentDisplayName, currentAvatarUrl)
                    )
                    refreshBannerPreference()
                    displayNamePreference.let {
                        it.text = currentDisplayName
                        it.summary = currentDisplayName ?: session.myUserId
                    }
                    activity?.invalidateOptionsMenu()
                }
                .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    // The override wins if present (even ""), else the account-wide banner shows.
    private fun displayedBannerUrl(): String? = when {
        currentBannerOverride != null -> currentBannerOverride?.takeIf { it.isNotEmpty() }
        else -> accountBannerUrl
    }

    private fun isBannerPersonalized(): Boolean {
        return currentBannerOverride != null &&
                currentBannerOverride?.takeIf { it.isNotEmpty() } != accountBannerUrl?.takeIf { it.isNotEmpty() }
    }

    private fun refreshBannerPreference() {
        bannerPreference.refreshBanner(displayedBannerUrl())
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

        bannerPreference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            bannerGalleryOrCameraDialogHelper.show(
                    withDeleteOption = !displayedBannerUrl().isNullOrEmpty(),
                    withResetOption = isBannerPersonalized(),
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
                room.stateService().updateMyRoomAvatar(uri, getFilenameFromUri(context, uri) ?: UUID.randomUUID().toString())
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

    // null drops the override (the account-wide banner applies); "" hides the banner in this room.
    private fun applyBanner(bannerUrl: String?) {
        displayLoadingView()
        lifecycleScope.launch {
            val result = runCatching { room.stateService().resetMyRoomBanner(bannerUrl) }
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

    fun isPersonalized() = currentDisplayName != accountDisplayName ||
            currentAvatarUrl != accountAvatarUrl ||
            isBannerPersonalized()

    fun resetToAccountProfile() {
        displayLoadingView()
        lifecycleScope.launch {
            val result = runCatching { room.stateService().updateMyRoomProfile(null, null, null) }
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

        fun newInstance(roomId: String) = RoomPersonalizationSettingsFragment().apply {
            arguments = Bundle().apply { putString(ARG_ROOM_ID, roomId) }
        }
    }
}
