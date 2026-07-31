/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile

import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.view.isVisible
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import com.airbnb.mvrx.args
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.animations.AppBarStateChangeListener
import im.vector.app.core.animations.MatrixItemAppBarStateChangeListener
import im.vector.app.core.extensions.applyThemeShapeColorCompat
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.extensions.copyOnLongClick
import im.vector.app.core.extensions.setCopySource
import im.vector.app.core.extensions.setTextOrHide
import im.vector.lib.core.utils.text.neutralizeDirectionOverrides
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.platform.VectorMenuProvider
import im.vector.app.core.ui.views.ProfileBannerUiHelper
import im.vector.app.core.utils.copyToClipboard
import im.vector.app.core.utils.startSharePlainTextIntent
import im.vector.app.databinding.FragmentMatrixProfileBinding
import im.vector.app.databinding.ViewStubRoomProfileHeaderBinding
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.BannerRenderer
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.imagepack.edit.ImagePackListActivity
import im.vector.app.features.home.room.detail.RoomDetailPendingAction
import im.vector.app.features.home.room.detail.RoomDetailPendingActionStore
import im.vector.app.features.home.room.detail.upgrade.MigrateRoomBottomSheet
import im.vector.app.features.home.room.list.actions.RoomListQuickActionsSharedAction
import im.vector.app.features.home.room.list.actions.RoomListQuickActionsSharedActionViewModel
import im.vector.app.features.navigation.SettingsActivityPayload
import im.vector.app.features.room.LeaveRoomPrompt
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.parcelize.Parcelize
import org.matrix.android.sdk.api.session.room.notification.RoomNotificationState
import org.matrix.android.sdk.api.util.toDisplayMatrixItem
import timber.log.Timber
import javax.inject.Inject

@Parcelize
data class RoomProfileArgs(
        val roomId: String,
) : Parcelable

@AndroidEntryPoint
class RoomProfileFragment :
        VectorBaseFragment<FragmentMatrixProfileBinding>(),
        RoomProfileController.Callback,
        VectorMenuProvider {

    @Inject lateinit var roomProfileController: RoomProfileController
    @Inject lateinit var avatarRenderer: AvatarRenderer
    @Inject lateinit var bannerRenderer: BannerRenderer
    @Inject lateinit var pgpKeyStore: im.vector.app.features.pgp.PgpKeyStore
    @Inject lateinit var roomDetailPendingActionStore: RoomDetailPendingActionStore

    private lateinit var headerViews: ViewStubRoomProfileHeaderBinding

    private val roomProfileArgs: RoomProfileArgs by args()
    private lateinit var roomListQuickActionsSharedActionViewModel: RoomListQuickActionsSharedActionViewModel
    private lateinit var roomProfileSharedActionViewModel: RoomProfileSharedActionViewModel
    private val roomProfileViewModel: RoomProfileViewModel by fragmentViewModel()

    private var appBarStateChangeListener: AppBarStateChangeListener? = null
    private var bannerAppBarStateChangeListener: AppBarStateChangeListener? = null
    private var bannerUiHelper: ProfileBannerUiHelper? = null
    private var currentBannerUrl: String? = null

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentMatrixProfileBinding {
        return FragmentMatrixProfileBinding.inflate(inflater, container, false)
    }

    override fun getMenuRes() = R.menu.vector_room_profile

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFragmentResultListener(MigrateRoomBottomSheet.REQUEST_KEY) { _, bundle ->
            bundle.getString(MigrateRoomBottomSheet.BUNDLE_KEY_REPLACEMENT_ROOM)?.let { replacementRoomId ->
                roomDetailPendingActionStore.data = RoomDetailPendingAction.OpenRoom(replacementRoomId, closeCurrentRoom = true)
                vectorBaseActivity.finish()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        roomListQuickActionsSharedActionViewModel = activityViewModelProvider.get(RoomListQuickActionsSharedActionViewModel::class.java)
        roomProfileSharedActionViewModel = activityViewModelProvider.get(RoomProfileSharedActionViewModel::class.java)
        val headerView = views.matrixProfileHeaderView.let {
            it.layoutResource = R.layout.view_stub_room_profile_header
            it.inflate()
        }
        headerViews = ViewStubRoomProfileHeaderBinding.bind(headerView)
        // Re-render the header/toolbar shield immediately when the PGP toggle changes.
        pgpKeyStore.changes
                .onEach { invalidate() }
                .launchIn(viewLifecycleOwner.lifecycleScope)
        setupWaitingView()
        setupToolbar(views.matrixProfileToolbar)
                .allowBack()
        setupRecyclerView()
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
                headerViews.roomProfileBannerScrim
        )
        bannerAppBarStateChangeListener = object : AppBarStateChangeListener() {
            override fun onStateChanged(appBarLayout: AppBarLayout, state: State) {
                bannerUiHelper?.update(currentBannerUrl != null, state == State.COLLAPSED)
            }
        }
        views.matrixProfileAppBarLayout.addOnOffsetChangedListener(bannerAppBarStateChangeListener)
        roomProfileViewModel.observeViewEvents {
            when (it) {
                is RoomProfileViewEvents.Loading -> showLoading(it.message)
                is RoomProfileViewEvents.Failure -> showFailure(it.throwable)
                is RoomProfileViewEvents.ShareRoomProfile -> onShareRoomProfile(it.permalink)
                is RoomProfileViewEvents.OnShortcutReady -> addShortcut(it)
                RoomProfileViewEvents.DismissLoading -> dismissLoadingDialog()
                is RoomProfileViewEvents.Success -> dismissSuccessDialog(it.message)
            }
        }
        roomListQuickActionsSharedActionViewModel
                .stream()
                .onEach { handleQuickActions(it) }
                .launchIn(viewLifecycleOwner.lifecycleScope)
        setupClicks()
        setupLongClicks()
    }

    private fun dismissSuccessDialog(message: CharSequence) {
        MaterialAlertDialogBuilder(
                requireActivity(),
                im.vector.lib.ui.styles.R.style.ThemeOverlay_Vector_MaterialAlertDialog_NegativeDestructive
        )
                .setTitle(CommonStrings.room_profile_section_more_report)
                .setMessage(message)
                .setPositiveButton(CommonStrings.ok, null)
                .show()
    }

    private fun setupWaitingView() {
        views.waitingView.waitingStatusText.setText(CommonStrings.please_wait)
        views.waitingView.waitingStatusText.isVisible = true
    }

    private fun setupClicks() {
        // Shortcut to room settings
        setOf(
                headerViews.roomProfileNameView,
                views.matrixProfileToolbarTitleView
        ).forEach {
            it.debouncedClicks {
                roomProfileSharedActionViewModel.post(RoomProfileSharedAction.OpenRoomSettings)
            }
        }
        // Shortcut to room alias
        headerViews.roomProfileAliasView.debouncedClicks {
            roomProfileSharedActionViewModel.post(RoomProfileSharedAction.OpenRoomAliasesSettings)
        }
        // Open Avatar
        setOf(
                headerViews.roomProfileAvatarView,
                views.matrixProfileToolbarAvatarImageView
        ).forEach {
            it.debouncedClicks { onAvatarClicked() }
        }
        // Open Banner
        headerViews.roomProfileBannerView.debouncedClicks { onBannerClicked() }
    }

    private fun setupLongClicks() {
        headerViews.roomProfileNameView.copyOnLongClick()
        headerViews.roomProfileAliasView.copyOnLongClick()
    }

    override fun handleMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.roomProfileShareAction -> {
                roomProfileViewModel.handle(RoomProfileAction.ShareRoomProfile)
                true
            }
            else -> false
        }
    }

    private fun handleQuickActions(action: RoomListQuickActionsSharedAction) = when (action) {
        is RoomListQuickActionsSharedAction.NotificationsAllNoisy -> {
            roomProfileViewModel.handle(RoomProfileAction.ChangeRoomNotificationState(RoomNotificationState.ALL_MESSAGES_NOISY))
        }
        is RoomListQuickActionsSharedAction.NotificationsAll -> {
            roomProfileViewModel.handle(RoomProfileAction.ChangeRoomNotificationState(RoomNotificationState.ALL_MESSAGES))
        }
        is RoomListQuickActionsSharedAction.NotificationsMentionsOnly -> {
            roomProfileViewModel.handle(RoomProfileAction.ChangeRoomNotificationState(RoomNotificationState.MENTIONS_ONLY))
        }
        is RoomListQuickActionsSharedAction.NotificationsMute -> {
            roomProfileViewModel.handle(RoomProfileAction.ChangeRoomNotificationState(RoomNotificationState.MUTE))
        }
        else -> Timber.v("$action not handled")
    }

    private fun setupRecyclerView() {
        roomProfileController.callback = this
        views.matrixProfileRecyclerView.configureWith(roomProfileController, hasFixedSize = true, disableItemAnimation = true)
    }

    override fun onDestroyView() {
        roomProfileController.callback = null
        views.matrixProfileAppBarLayout.removeOnOffsetChangedListener(appBarStateChangeListener)
        views.matrixProfileAppBarLayout.removeOnOffsetChangedListener(bannerAppBarStateChangeListener)
        views.matrixProfileRecyclerView.cleanup()
        appBarStateChangeListener = null
        bannerAppBarStateChangeListener = null
        bannerUiHelper?.restore()
        bannerUiHelper = null
        super.onDestroyView()
    }

    override fun invalidate() = withState(roomProfileViewModel) { state ->
        views.waitingView.root.isVisible = state.isLoading

        currentBannerUrl = state.bannerUrl
        val hasBanner = currentBannerUrl != null
        headerViews.roomProfileBannerView.isVisible = hasBanner
        headerViews.roomProfileBannerScrim.isVisible = hasBanner
        headerViews.roomProfileBannerOverlap.isVisible = hasBanner
        bannerRenderer.render(currentBannerUrl, headerViews.roomProfileBannerView)
        bannerUiHelper?.update(hasBanner, bannerAppBarStateChangeListener?.currentState == AppBarStateChangeListener.State.COLLAPSED)

        state.roomSummary()?.let {
            if (it.membership.isLeft()) {
                Timber.w("The room has been left")
                activity?.finish()
            } else {
                headerViews.roomProfileNameView.text = it.displayName.prepareForDisplay()
                headerViews.roomProfileNameView.setCopySource(it.displayName)
                views.matrixProfileToolbarTitleView.text = it.displayName.prepareForDisplay()
                headerViews.roomProfileAliasView.setTextOrHide(it.canonicalAlias?.neutralizeDirectionOverrides())
                headerViews.roomProfileAliasView.setCopySource(it.canonicalAlias)
                val matrixItem = it.toDisplayMatrixItem()
                avatarRenderer.render(matrixItem, headerViews.roomProfileAvatarView)
                bannerRenderer.applyAvatarStroke(headerViews.roomProfileAvatarView, matrixItem, state.bannerUrl != null)
                avatarRenderer.render(matrixItem, views.matrixProfileToolbarAvatarImageView)
                val isPgp = pgpKeyStore.isEnabled && !it.isEncrypted && pgpKeyStore.isRoomPgpEnabled(it.roomId)
                headerViews.roomProfileDecorationImageView.renderRoomShield(it.roomEncryptionTrustLevel, isPgp)
                views.matrixProfileDecorationToolbarAvatarImageView.renderRoomShield(it.roomEncryptionTrustLevel, isPgp)
                headerViews.roomProfilePresenceImageView.render(it.isDirect, it.directUserPresence)
                headerViews.roomProfilePublicImageView.isVisible = it.isPublic && !it.isDirect
                headerViews.roomProfilePublicImageView.applyThemeShapeColorCompat(android.R.attr.colorBackground)
            }
        }
        roomProfileController.setData(state)
    }

    // RoomProfileController.Callback

    override fun onLearnMoreClicked() {
        vectorBaseActivity.notImplemented()
    }

    override fun onEnableEncryptionClicked() {
        MaterialAlertDialogBuilder(requireActivity())
                .setTitle(CommonStrings.room_settings_enable_encryption_dialog_title)
                .setMessage(CommonStrings.room_settings_enable_encryption_dialog_content)
                .setNegativeButton(CommonStrings.action_cancel, null)
                .setPositiveButton(CommonStrings.room_settings_enable_encryption_dialog_submit) { _, _ ->
                    roomProfileViewModel.handle(RoomProfileAction.EnableEncryption)
                }
                .show()
    }

    override fun onMemberListClicked() {
        roomProfileSharedActionViewModel.post(RoomProfileSharedAction.OpenRoomMembers)
    }

    override fun onKnockRequestsClicked() {
        roomProfileSharedActionViewModel.post(RoomProfileSharedAction.OpenKnockRequests)
    }

    override fun onBannedMemberListClicked() {
        roomProfileSharedActionViewModel.post(RoomProfileSharedAction.OpenBannedRoomMembers)
    }

    override fun onSettingsClicked() {
        roomProfileSharedActionViewModel.post(RoomProfileSharedAction.OpenRoomSettings)
    }

    override fun onNotificationsClicked() {
        roomProfileSharedActionViewModel.post(RoomProfileSharedAction.OpenRoomNotificationSettings)
    }

    override fun onPollHistoryClicked() {
        roomProfileSharedActionViewModel.post(RoomProfileSharedAction.OpenRoomPolls)
    }

    override fun onPinnedMessagesClicked() {
        roomProfileSharedActionViewModel.post(RoomProfileSharedAction.OpenPinnedMessages)
    }

    override fun onUploadsClicked() {
        roomProfileSharedActionViewModel.post(RoomProfileSharedAction.OpenRoomUploads)
    }

    override fun onImagePacksClicked() {
        startActivity(ImagePackListActivity.newIntent(requireContext(), roomProfileArgs.roomId))
    }

    override fun onPersonalizationClicked() {
        roomProfileSharedActionViewModel.post(RoomProfileSharedAction.OpenRoomPersonalization)
    }

    override fun createShortcut() {
        // Ask the view model to prepare it...
        roomProfileViewModel.handle(RoomProfileAction.CreateShortcut)
    }

    private fun addShortcut(onShortcutReady: RoomProfileViewEvents.OnShortcutReady) {
        // ... and propose the user to add it
        ShortcutManagerCompat.requestPinShortcut(requireContext(), onShortcutReady.shortcutInfo, null)
    }

    override fun onLeaveRoomClicked() {
        withState(roomProfileViewModel) { state ->
            val warning = when {
                state.isLastAdmin -> LeaveRoomPrompt.Warning.LAST_ADMIN
                state.roomSummary()?.isPublic == false -> LeaveRoomPrompt.Warning.PRIVATE_ROOM
                else -> LeaveRoomPrompt.Warning.NONE
            }
            LeaveRoomPrompt.show(requireContext(), warning) {
                roomProfileViewModel.handle(RoomProfileAction.LeaveRoom)
            }
        }
    }

    override fun onRoomAliasesClicked() {
        roomProfileSharedActionViewModel.post(RoomProfileSharedAction.OpenRoomAliasesSettings)
    }

    override fun onRoomPermissionsClicked() {
        roomProfileSharedActionViewModel.post(RoomProfileSharedAction.OpenRoomPermissionsSettings)
    }

    override fun restoreEncryptionState() {
        roomProfileViewModel.handle(RoomProfileAction.RestoreEncryptionState)
    }

    override fun onRoomIdClicked() {
        copyToClipboard(requireContext(), roomProfileArgs.roomId)
    }

    override fun onRoomDevToolsClicked() {
        navigator.openDevTools(requireContext(), roomProfileArgs.roomId)
    }

    override fun onUrlInTopicLongClicked(url: String) {
        copyToClipboard(requireContext(), url, true)
    }

    override fun doMigrateToVersion(newVersion: String) {
        MigrateRoomBottomSheet.newInstance(roomProfileArgs.roomId, newVersion)
                .show(parentFragmentManager, "migrate")
    }

    private fun onShareRoomProfile(permalink: String) {
        startSharePlainTextIntent(
                context = requireContext(),
                activityResultLauncher = null,
                chooserTitle = null,
                text = permalink
        )
    }

    override fun setEncryptedToVerifiedDevicesOnly(enabled: Boolean) {
        roomProfileViewModel.handle(RoomProfileAction.SetEncryptToVerifiedDeviceOnly(enabled))
    }

    override fun openGlobalBlockSettings() {
        navigator.openSettings(requireContext(), SettingsActivityPayload.SecurityPrivacy)
    }

    private fun onAvatarClicked() = withState(roomProfileViewModel) { state ->
        state.roomSummary()?.toDisplayMatrixItem()?.let { matrixItem ->
            navigator.openBigImageViewer(requireActivity(), matrixItem)
        }
    }

    private fun onBannerClicked() = withState(roomProfileViewModel) { state ->
        state.bannerUrl?.let { bannerUrl ->
            navigator.openBigImageViewer(requireActivity(), null, bannerUrl, state.roomSummary()?.displayName)
        }
    }
}
