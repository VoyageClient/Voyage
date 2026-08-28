/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.spaces.create

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.airbnb.mvrx.activityViewModel
import com.airbnb.mvrx.withState
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.dialogs.GalleryOrCameraDialogHelper
import im.vector.app.core.dialogs.GalleryOrCameraDialogHelperFactory
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.extensions.hideKeyboard
import im.vector.app.core.platform.OnBackPressed
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.databinding.FragmentSpaceCreateGenericEpoxyFormBinding
import im.vector.app.features.roomdirectory.createroom.showInitialStateDialog
import im.vector.app.features.roomdirectory.createroom.showMyPowerLevelDialog
import im.vector.app.features.roomdirectory.createroom.showRoomVersionDialog
import im.vector.app.features.roomprofile.settings.joinrule.RoomJoinRuleBottomSheet
import im.vector.app.features.roomprofile.settings.joinrule.RoomJoinRuleSharedActionViewModel
import im.vector.app.features.roomprofile.settings.joinrule.toOption
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import javax.inject.Inject

@AndroidEntryPoint
class CreateSpaceDetailsFragment :
        VectorBaseFragment<FragmentSpaceCreateGenericEpoxyFormBinding>(),
        SpaceDetailEpoxyController.Listener,
        GalleryOrCameraDialogHelper.Listener,
        OnBackPressed {

    @Inject lateinit var epoxyController: SpaceDetailEpoxyController
    @Inject lateinit var galleryOrCameraDialogHelperFactory: GalleryOrCameraDialogHelperFactory

    private val sharedViewModel: CreateSpaceViewModel by activityViewModel()

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?) =
            FragmentSpaceCreateGenericEpoxyFormBinding.inflate(layoutInflater, container, false)

    private lateinit var galleryOrCameraDialogHelper: GalleryOrCameraDialogHelper
    private lateinit var roomJoinRuleSharedActionViewModel: RoomJoinRuleSharedActionViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        galleryOrCameraDialogHelper = galleryOrCameraDialogHelperFactory.create(this)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        roomJoinRuleSharedActionViewModel = activityViewModelProvider.get(RoomJoinRuleSharedActionViewModel::class.java)
        roomJoinRuleSharedActionViewModel
                .stream()
                .onEach { sharedViewModel.handle(CreateSpaceAction.SetJoinRule(it.roomJoinRule)) }
                .launchIn(viewLifecycleOwner.lifecycleScope)

        views.recyclerView.configureWith(epoxyController)
        epoxyController.listener = this

        sharedViewModel.onEach {
            epoxyController.setData(it)
        }

        views.nextButton.debouncedClicks {
            view.hideKeyboard()
            sharedViewModel.handle(CreateSpaceAction.NextFromDetails)
        }
    }

    override fun onImageReady(uri: Uri?) {
        sharedViewModel.handle(CreateSpaceAction.SetAvatar(uri))
    }

    // -----------------------------
    // Epoxy controller listener methods
    // -----------------------------

    override fun onAvatarDelete() {
        sharedViewModel.handle(CreateSpaceAction.SetAvatar(null))
    }

    override fun onAvatarChange() {
        galleryOrCameraDialogHelper.show()
    }

    override fun onNameChange(newName: String) {
        sharedViewModel.handle(CreateSpaceAction.NameChanged(newName))
    }

    override fun onTopicChange(newTopic: String) {
        sharedViewModel.handle(CreateSpaceAction.TopicChanged(newTopic))
    }

    override fun setAliasLocalPart(aliasLocalPart: String) {
        sharedViewModel.handle(CreateSpaceAction.SpaceAliasChanged(aliasLocalPart))
    }

    override fun selectJoinRule() = withState(sharedViewModel) { state ->
        val allowed = buildList {
            add(RoomJoinRules.INVITE)
            if (state.supportsKnock) add(RoomJoinRules.KNOCK)
            add(RoomJoinRules.PUBLIC)
        }
        RoomJoinRuleBottomSheet.newInstance(
                currentRoomJoinRule = state.joinRule,
                allowedJoinedRules = allowed.map { it.toOption(false) },
                isSpace = true,
                parentSpaceName = null
        )
                .show(childFragmentManager, "RoomJoinRuleBottomSheet")
    }

    override fun setIsEncrypted(isEncrypted: Boolean) {
        sharedViewModel.handle(CreateSpaceAction.SetIsEncrypted(isEncrypted))
    }

    override fun toggleShowAdvanced() {
        sharedViewModel.handle(CreateSpaceAction.ToggleShowAdvanced)
    }

    override fun setDisableFederation(disableFederation: Boolean) {
        sharedViewModel.handle(CreateSpaceAction.SetDisableFederation(disableFederation))
    }

    override fun selectRoomVersion() {
        withState(sharedViewModel) { state ->
            showRoomVersionDialog(state) { sharedViewModel.handle(CreateSpaceAction.SetRoomVersion(it)) }
        }
    }

    override fun selectMyPowerLevel() {
        withState(sharedViewModel) { state ->
            showMyPowerLevelDialog(state) { sharedViewModel.handle(CreateSpaceAction.SetMyPowerLevel(it)) }
        }
    }

    override fun editInitialState() {
        withState(sharedViewModel) { state ->
            showInitialStateDialog(state) { sharedViewModel.handle(CreateSpaceAction.SetInitialStateJson(it)) }
        }
    }

    override fun onBackPressed(toolbarButton: Boolean): Boolean {
        sharedViewModel.handle(CreateSpaceAction.OnBackPressed)
        return true
    }
}
