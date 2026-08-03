/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roommemberprofile

import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.Uninitialized
import com.airbnb.mvrx.args
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.animations.AppBarStateChangeListener
import im.vector.app.core.animations.MatrixItemAppBarStateChangeListener
import im.vector.app.core.dialogs.ConfirmationDialogBuilder
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.extensions.copyOnLongClick
import im.vector.app.core.extensions.setCopySource
import im.vector.app.core.extensions.setTextOrHide
import im.vector.lib.core.utils.text.neutralizeDirectionOverrides
import im.vector.app.core.platform.StateView
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.ui.views.ProfileBannerUiHelper
import im.vector.app.databinding.DialogBaseEditTextBinding
import im.vector.app.databinding.FragmentMatrixProfileBinding
import im.vector.app.databinding.ViewStubRoomMemberProfileHeaderBinding
import im.vector.app.features.crypto.verification.user.UserVerificationBottomSheet
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.BannerRenderer
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.home.room.detail.RoomDetailPendingAction
import im.vector.app.features.home.room.detail.RoomDetailPendingActionStore
import im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider
import im.vector.app.features.media.shouldHideAvatars
import im.vector.app.features.roommemberprofile.devices.DeviceListBottomSheet
import im.vector.app.features.roommemberprofile.mutualrooms.MutualRoomsActivity
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.roommemberprofile.powerlevel.EditPowerLevelDialogs
import im.vector.lib.strings.CommonStrings
import kotlinx.parcelize.Parcelize
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.crypto.model.UserVerificationLevel
import org.matrix.android.sdk.api.session.room.powerlevels.UserPowerLevel
import org.matrix.android.sdk.api.util.MatrixItem
import javax.inject.Inject

@Parcelize
data class RoomMemberProfileArgs(
        val userId: String,
        val roomId: String? = null
) : Parcelable

@AndroidEntryPoint
class RoomMemberProfileFragment :
        VectorBaseFragment<FragmentMatrixProfileBinding>(),
        RoomMemberProfileController.Callback {

    @Inject lateinit var roomMemberProfileController: RoomMemberProfileController
    @Inject lateinit var avatarRenderer: AvatarRenderer
    @Inject lateinit var bannerRenderer: BannerRenderer
    @Inject lateinit var roomDetailPendingActionStore: RoomDetailPendingActionStore
    @Inject lateinit var matrixItemColorProvider: MatrixItemColorProvider
    @Inject lateinit var session: Session
    @Inject lateinit var vectorPreferences: VectorPreferences

    private lateinit var headerViews: ViewStubRoomMemberProfileHeaderBinding

    private val fragmentArgs: RoomMemberProfileArgs by args()
    private val viewModel: RoomMemberProfileViewModel by fragmentViewModel()

    private var appBarStateChangeListener: AppBarStateChangeListener? = null
    private var bannerAppBarStateChangeListener: AppBarStateChangeListener? = null
    private var bannerUiHelper: ProfileBannerUiHelper? = null
    private var currentBannerUrl: String? = null

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentMatrixProfileBinding {
        return FragmentMatrixProfileBinding.inflate(inflater, container, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar(views.matrixProfileToolbar)
                .allowBack()
        val headerView = views.matrixProfileHeaderView.let {
            it.layoutResource = R.layout.view_stub_room_member_profile_header
            it.inflate()
        }
        headerViews = ViewStubRoomMemberProfileHeaderBinding.bind(headerView)
        headerViews.memberProfileStateView.eventCallback = object : StateView.EventCallback {
            override fun onRetryClicked() {
                viewModel.handle(RoomMemberProfileAction.RetryFetchingInfo)
            }
        }
        headerViews.memberProfileStateView.contentView = headerViews.memberProfileInfoContainer
        views.matrixProfileRecyclerView.configureWith(roomMemberProfileController, hasFixedSize = true, disableItemAnimation = true)
        roomMemberProfileController.callback = this
        appBarStateChangeListener = MatrixItemAppBarStateChangeListener(
                headerView,
                listOf(
                        views.matrixProfileToolbarAvatarImageView,
                        views.matrixProfileToolbarTitleView,
                        views.matrixProfileDecorationToolbarAvatarImageView
                )
        )
        views.matrixProfileAppBarLayout.addOnOffsetChangedListener(appBarStateChangeListener)
        bannerUiHelper = ProfileBannerUiHelper(
                vectorBaseActivity,
                views.matrixProfileToolbar,
                views.matrixProfileCollapsingToolbarLayout,
                headerViews.memberProfileBannerScrim
        )
        bannerAppBarStateChangeListener = object : AppBarStateChangeListener() {
            override fun onStateChanged(appBarLayout: AppBarLayout, state: State) {
                bannerUiHelper?.update(currentBannerUrl != null, state == State.COLLAPSED)
            }
        }
        views.matrixProfileAppBarLayout.addOnOffsetChangedListener(bannerAppBarStateChangeListener)
        headerViews.memberProfileBannerView.debouncedClicks { onBannerClicked() }
        viewModel.observeViewEvents {
            when (it) {
                is RoomMemberProfileViewEvents.Loading -> showLoading(it.message)
                is RoomMemberProfileViewEvents.Failure -> showFailure(it.throwable)
                is RoomMemberProfileViewEvents.StartVerification -> handleStartVerification(it)
                is RoomMemberProfileViewEvents.ShowPowerLevelValidation -> handleShowPowerLevelAdminWarning(it)
                is RoomMemberProfileViewEvents.ShowPowerLevelDemoteWarning -> handleShowPowerLevelDemoteWarning(it)
                is RoomMemberProfileViewEvents.OpenRoom -> handleOpenRoom(it)
                is RoomMemberProfileViewEvents.OnKickActionSuccess -> Unit
                RoomMemberProfileViewEvents.MassRedactionAlreadyRunning ->
                    MaterialAlertDialogBuilder(requireActivity())
                            .setTitle(CommonStrings.dialog_title_error)
                            .setMessage(CommonStrings.mass_redaction_already_running)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                is RoomMemberProfileViewEvents.OnSetPowerLevelSuccess -> Unit
                is RoomMemberProfileViewEvents.OnBanActionSuccess -> Unit
                is RoomMemberProfileViewEvents.OnIgnoreActionSuccess -> Unit
                is RoomMemberProfileViewEvents.OnInviteActionSuccess -> Unit
                RoomMemberProfileViewEvents.GoBack -> handleGoBack()
            }
        }
        setupLongClicks()
    }

    private fun setupLongClicks() {
        headerViews.memberProfileNameView.copyOnLongClick()
        headerViews.memberProfileIdView.copyOnLongClick()
    }

    private fun handleOpenRoom(event: RoomMemberProfileViewEvents.OpenRoom) {
        navigator.openRoom(requireContext(), event.roomId, null)
    }

    private fun handleShowPowerLevelDemoteWarning(event: RoomMemberProfileViewEvents.ShowPowerLevelDemoteWarning) {
        EditPowerLevelDialogs.showDemoteWarning(requireActivity()) {
            viewModel.handle(RoomMemberProfileAction.SetPowerLevel(event.currentValue, event.newValue, false))
        }
    }

    private fun handleShowPowerLevelAdminWarning(event: RoomMemberProfileViewEvents.ShowPowerLevelValidation) {
        EditPowerLevelDialogs.showValidation(requireActivity()) {
            viewModel.handle(RoomMemberProfileAction.SetPowerLevel(event.currentValue, event.newValue, false))
        }
    }

    private fun handleStartVerification(startVerification: RoomMemberProfileViewEvents.StartVerification) {
        if (startVerification.canCrossSign) {
            UserVerificationBottomSheet
                    .verifyUser(otherUserId = startVerification.userId)
                    .show(parentFragmentManager, "VERIF")
        } else {
            MaterialAlertDialogBuilder(requireContext())
                    .setTitle(CommonStrings.dialog_title_warning)
                    .setMessage(CommonStrings.verify_cannot_cross_sign)
                    .setPositiveButton(CommonStrings.verification_profile_verify) { _, _ ->
                        UserVerificationBottomSheet
                                .verifyUser(otherUserId = startVerification.userId)
                                .show(parentFragmentManager, "VERIF")
                    }
                    .setNegativeButton(CommonStrings.action_cancel, null)
                    .show()
        }
    }

    override fun onDestroyView() {
        views.matrixProfileAppBarLayout.removeOnOffsetChangedListener(appBarStateChangeListener)
        views.matrixProfileAppBarLayout.removeOnOffsetChangedListener(bannerAppBarStateChangeListener)
        roomMemberProfileController.callback = null
        appBarStateChangeListener = null
        bannerAppBarStateChangeListener = null
        bannerUiHelper?.restore()
        bannerUiHelper = null
        views.matrixProfileRecyclerView.cleanup()
        super.onDestroyView()
    }

    override fun invalidate() = withState(viewModel) { state ->
        when (val asyncUserMatrixItem = state.userMatrixItem) {
            Uninitialized,
            is Loading -> {
                views.matrixProfileToolbarTitleView.text = state.userId.neutralizeDirectionOverrides()
                avatarRenderer.render(MatrixItem.UserItem(state.userId, null, null), views.matrixProfileToolbarAvatarImageView)
                headerViews.memberProfileStateView.state = StateView.State.Loading
            }
            is Fail -> {
                avatarRenderer.render(MatrixItem.UserItem(state.userId, null, null), views.matrixProfileToolbarAvatarImageView)
                views.matrixProfileToolbarTitleView.text = state.userId.neutralizeDirectionOverrides()
                val failureMessage = errorFormatter.toHumanReadable(asyncUserMatrixItem.error)
                headerViews.memberProfileStateView.state = StateView.State.Error(failureMessage)
            }
            is Success -> {
                val userMatrixItem = asyncUserMatrixItem()
                headerViews.memberProfileStateView.state = StateView.State.Content
                headerViews.memberProfileIdView.text = userMatrixItem.id.neutralizeDirectionOverrides()
                headerViews.memberProfileIdView.setCopySource(userMatrixItem.id)
                val bestName = userMatrixItem.getBestName()
                headerViews.memberProfileNameView.text = bestName.prepareForDisplay()
                headerViews.memberProfileNameView.setCopySource(bestName)
                headerViews.memberProfileNameView.setTextColor(matrixItemColorProvider.getColor(userMatrixItem))
                views.matrixProfileToolbarTitleView.text = bestName.prepareForDisplay()
                // In rooms that hide avatars, show the default placeholder here, but keep the real avatar
                // available when the user taps it to open the full-screen viewer (see onAvatarClicked).
                val displayedMatrixItem = if (state.userId != session.myUserId && shouldHideAvatars(state.roomId, session, vectorPreferences)) {
                    userMatrixItem.updateAvatar(null)
                } else {
                    userMatrixItem
                }
                avatarRenderer.render(displayedMatrixItem, headerViews.memberProfileAvatarView)
                avatarRenderer.render(displayedMatrixItem, views.matrixProfileToolbarAvatarImageView)

                // Follow the same hiding rule as the avatar
                currentBannerUrl = state.resolvedBannerUrl()
                        ?.takeUnless { state.userId != session.myUserId && shouldHideAvatars(state.roomId, session, vectorPreferences) }
                val hasBanner = currentBannerUrl != null
                headerViews.memberProfileBannerView.isVisible = hasBanner
                headerViews.memberProfileBannerScrim.isVisible = hasBanner
                headerViews.memberProfileBannerOverlap.isVisible = hasBanner
                bannerRenderer.render(currentBannerUrl, headerViews.memberProfileBannerView)
                bannerRenderer.applyAvatarStroke(headerViews.memberProfileAvatarView, displayedMatrixItem, hasBanner)
                bannerUiHelper?.update(hasBanner, bannerAppBarStateChangeListener?.currentState == AppBarStateChangeListener.State.COLLAPSED)

                if (state.isRoomEncrypted) {
                    headerViews.memberProfileDecorationImageView.isVisible = true
                    val trustLevel = if (state.userMXCrossSigningInfo != null) {
                        // Cross signing is enabled for this user
                        if (state.userMXCrossSigningInfo.isTrusted()) {
                            // User is trusted
                            if (state.allDevicesAreCrossSignedTrusted) {
                                UserVerificationLevel.VERIFIED_ALL_DEVICES_TRUSTED
                            } else {
                                UserVerificationLevel.VERIFIED_WITH_DEVICES_UNTRUSTED
                            }
                        } else {
                            if (state.userMXCrossSigningInfo.wasTrustedOnce) {
                                UserVerificationLevel.UNVERIFIED_BUT_WAS_PREVIOUSLY
                            } else {
                                UserVerificationLevel.WAS_NEVER_VERIFIED
                            }
                        }
                    } else {
                        // Legacy
                        if (state.allDevicesAreTrusted) {
                            UserVerificationLevel.VERIFIED_ALL_DEVICES_TRUSTED
                        } else {
                            UserVerificationLevel.VERIFIED_WITH_DEVICES_UNTRUSTED
                        }
                    }
                    headerViews.memberProfileDecorationImageView.renderUser(trustLevel)
                    views.matrixProfileDecorationToolbarAvatarImageView.renderUser(trustLevel)
                } else {
                    headerViews.memberProfileDecorationImageView.isVisible = false
                }

                headerViews.memberProfileAvatarView.setOnClickListener {
                    onAvatarClicked(userMatrixItem)
                }
                views.matrixProfileToolbarAvatarImageView.setOnClickListener {
                    onAvatarClicked(userMatrixItem)
                }
            }
        }
        headerViews.memberProfilePowerLevelView.setTextOrHide(state.userPowerLevelString())
        roomMemberProfileController.setData(state)
    }

    // RoomMemberProfileController.Callback

    override fun onIgnoreClicked() = withState(viewModel) { state ->
        val isIgnored = state.isIgnored() ?: false
        val titleRes: Int
        val positiveButtonRes: Int
        val confirmationRes: Int
        if (isIgnored) {
            confirmationRes = CommonStrings.room_participants_action_unignore_prompt_msg
            titleRes = CommonStrings.room_participants_action_unignore_title
            positiveButtonRes = CommonStrings.room_participants_action_unignore
        } else {
            confirmationRes = CommonStrings.room_participants_action_ignore_prompt_msg
            titleRes = CommonStrings.room_participants_action_ignore_title
            positiveButtonRes = CommonStrings.room_participants_action_ignore
        }
        ConfirmationDialogBuilder
                .show(
                        activity = requireActivity(),
                        askForReason = false,
                        confirmationRes = confirmationRes,
                        positiveRes = positiveButtonRes,
                        reasonHintRes = 0,
                        titleRes = titleRes
                ) {
                    viewModel.handle(RoomMemberProfileAction.IgnoreUser)
                }
    }

    override fun onTapVerify() {
        viewModel.handle(RoomMemberProfileAction.VerifyUser)
    }

    override fun onShowDeviceList() = withState(viewModel) {
        DeviceListBottomSheet.newInstance(it.userId).show(parentFragmentManager, "DEV_LIST")
    }

    override fun onShowDeviceListNoCrossSigning() = withState(viewModel) {
        DeviceListBottomSheet.newInstance(it.userId).show(parentFragmentManager, "DEV_LIST")
    }

    override fun onOpenDmClicked() {
        viewModel.handle(RoomMemberProfileAction.OpenOrCreateDm(fragmentArgs.userId))
    }

    private fun handleGoBack() {
        roomDetailPendingActionStore.data = RoomDetailPendingAction.DoNothing
        vectorBaseActivity.finish()
    }

    override fun onJumpToReadReceiptClicked() {
        roomDetailPendingActionStore.data = RoomDetailPendingAction.JumpToReadReceipt(fragmentArgs.userId)
        vectorBaseActivity.finish()
    }

    override fun onMentionClicked() {
        roomDetailPendingActionStore.data = RoomDetailPendingAction.MentionUser(fragmentArgs.userId)
        vectorBaseActivity.finish()
    }

    private fun onAvatarClicked(userMatrixItem: MatrixItem) {
        navigator.openBigImageViewer(requireActivity(), userMatrixItem)
    }

    private fun onBannerClicked() = withState(viewModel) { state ->
        currentBannerUrl?.let { bannerUrl ->
            navigator.openBigImageViewer(requireActivity(), null, bannerUrl, state.userMatrixItem()?.getBestName() ?: state.userId)
        }
    }

    override fun onOverrideColorClicked(): Unit = withState(viewModel) { state ->
        val inflater = requireActivity().layoutInflater
        val layout = inflater.inflate(R.layout.dialog_base_edit_text, null)
        val views = DialogBaseEditTextBinding.bind(layout)
        views.editText.setText(state.userColorOverride)
        views.editText.hint = "#000000"

        MaterialAlertDialogBuilder(requireContext())
                .setTitle(CommonStrings.room_member_override_nick_color)
                .setView(layout)
                .setPositiveButton(CommonStrings.ok) { _, _ ->
                    val newColor = views.editText.text.toString()
                    if (newColor != state.userColorOverride) {
                        viewModel.handle(RoomMemberProfileAction.SetUserColorOverride(newColor))
                    }
                }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
    }

    override fun onEditPowerLevel(userPowerLevel: UserPowerLevel.Value) {
        EditPowerLevelDialogs.showChoice(requireActivity(), CommonStrings.power_level_edit_title, userPowerLevel) { newPowerLevel ->
            viewModel.handle(RoomMemberProfileAction.SetPowerLevel(userPowerLevel, newPowerLevel, true))
        }
    }

    override fun onKickClicked(isSpace: Boolean) {
        ConfirmationDialogBuilder
                .show(
                        activity = requireActivity(),
                        askForReason = true,
                        confirmationRes = if (isSpace) CommonStrings.space_participants_kick_prompt_msg
                        else CommonStrings.room_participants_kick_prompt_msg,
                        positiveRes = CommonStrings.room_participants_action_kick,
                        reasonHintRes = CommonStrings.room_participants_kick_reason,
                        titleRes = CommonStrings.room_participants_kick_title
                ) { reason ->
                    viewModel.handle(RoomMemberProfileAction.KickUser(reason))
                }
    }

    override fun onRedactAllClicked() {
        withState(viewModel) { state ->
            val bestName = state.userMatrixItem()?.getBestName()
            val target = if (bestName != null && bestName != state.userId) "$bestName (${state.userId})" else state.userId
            MaterialAlertDialogBuilder(requireActivity())
                    .setTitle(CommonStrings.mass_redaction_confirmation_title)
                    .setMessage(getString(CommonStrings.mass_redaction_confirmation_message, target))
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        viewModel.handle(RoomMemberProfileAction.RedactAllMessages)
                    }
                    .setNegativeButton(CommonStrings.action_cancel, null)
                    .show()
        }
    }

    override fun onBanClicked(isSpace: Boolean, isUserBanned: Boolean) {
        val titleRes: Int
        val positiveButtonRes: Int
        val confirmationRes: Int
        if (isUserBanned) {
            confirmationRes = if (isSpace) CommonStrings.space_participants_unban_prompt_msg
            else CommonStrings.room_participants_unban_prompt_msg
            titleRes = CommonStrings.room_participants_unban_title
            positiveButtonRes = CommonStrings.room_participants_action_unban
        } else {
            confirmationRes = if (isSpace) CommonStrings.space_participants_ban_prompt_msg
            else CommonStrings.room_participants_ban_prompt_msg
            titleRes = CommonStrings.room_participants_ban_title
            positiveButtonRes = CommonStrings.room_participants_action_ban
        }
        ConfirmationDialogBuilder
                .show(
                        activity = requireActivity(),
                        askForReason = !isUserBanned,
                        confirmationRes = confirmationRes,
                        positiveRes = positiveButtonRes,
                        reasonHintRes = CommonStrings.room_participants_ban_reason,
                        titleRes = titleRes
                ) { reason ->
                    viewModel.handle(RoomMemberProfileAction.BanOrUnbanUser(reason))
                }
    }

    override fun onCancelInviteClicked() {
        ConfirmationDialogBuilder
                .show(
                        activity = requireActivity(),
                        askForReason = false,
                        confirmationRes = CommonStrings.room_participants_action_cancel_invite_prompt_msg,
                        positiveRes = CommonStrings.room_participants_action_cancel_invite,
                        reasonHintRes = 0,
                        titleRes = CommonStrings.room_participants_action_cancel_invite_title
                ) {
                    viewModel.handle(RoomMemberProfileAction.KickUser(null))
                }
    }

    override fun onInviteClicked() {
        viewModel.handle(RoomMemberProfileAction.InviteUser)
    }

    override fun onMutualRoomsClicked() {
        startActivity(MutualRoomsActivity.newIntent(requireContext(), fragmentArgs.userId))
    }
}
