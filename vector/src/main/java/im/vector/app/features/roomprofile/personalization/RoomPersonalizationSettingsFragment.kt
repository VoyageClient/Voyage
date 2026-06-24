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
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.dialogs.GalleryOrCameraDialogHelper
import im.vector.app.core.dialogs.GalleryOrCameraDialogHelperFactory
import im.vector.app.core.intent.getFilenameFromUri
import im.vector.app.core.preference.UserAvatarPreference
import im.vector.app.core.preference.VectorEditTextPreference
import im.vector.app.features.settings.VectorSettingsBaseFragment
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

    private val roomId: String by lazy { requireArguments().getString(ARG_ROOM_ID)!! }
    private val room: Room by lazy { session.getRoom(roomId)!! }

    private var currentAvatarUrl: String? = null
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
        galleryOrCameraDialogHelper = galleryOrCameraDialogHelperFactory.create(this)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadAccountProfile()
        observeMyMembership()
    }

    private fun loadAccountProfile() {
        lifecycleScope.launch {
            val user = tryOrNull { session.userService().resolveUser(session.myUserId) }
                    ?: session.userService().getUser(session.myUserId)
            accountDisplayName = user?.displayName
            accountAvatarUrl = user?.avatarUrl
            displayNamePreference.setOnBindEditTextListener { editText ->
                editText.hint = accountDisplayName
            }
        }
    }

    private fun observeMyMembership() {
        room.flow()
                .liveStateEvent(EventType.STATE_ROOM_MEMBER, QueryStringValue.Equals(session.myUserId))
                .mapOptional { it.content.toModel<RoomMemberContent>() }
                .unwrap()
                .distinctUntilChanged()
                .onEach { content ->
                    currentAvatarUrl = content.avatarUrl
                    avatarPreference.refreshAvatar(
                            User(session.myUserId, content.displayName ?: accountDisplayName, content.avatarUrl)
                    )
                    displayNamePreference.let {
                        it.text = content.displayName
                        it.summary = content.displayName?.takeIf { name -> name.isNotBlank() } ?: accountDisplayName
                    }
                }
                .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    override fun bindPref() {
        avatarPreference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            galleryOrCameraDialogHelper.show(
                    withDeleteOption = currentAvatarUrl != accountAvatarUrl,
                    deleteActionTitle = CommonStrings.room_personalization_reset
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
        displayLoadingView()
        lifecycleScope.launch {
            val result = runCatching { room.stateService().resetMyRoomAvatar(accountAvatarUrl) }
            if (!isAdded) return@launch
            hideLoadingView()
            result.onFailure { displayErrorDialog(it) }
        }
    }

    private fun onDisplayNameChanged(value: String) {
        // An empty value removes the personalization and falls back to the account-wide display name.
        val newDisplayName = value.takeIf { it.isNotBlank() } ?: accountDisplayName
        displayLoadingView()
        lifecycleScope.launch {
            val result = runCatching { room.stateService().updateMyRoomDisplayName(newDisplayName) }
            if (!isAdded) return@launch
            hideLoadingView()
            result.onFailure { displayErrorDialog(it) }
        }
    }

    companion object {
        private const val ARG_ROOM_ID = "ARG_ROOM_ID"

        fun newInstance(roomId: String) = RoomPersonalizationSettingsFragment().apply {
            arguments = Bundle().apply { putString(ARG_ROOM_ID, roomId) }
        }
    }
}
