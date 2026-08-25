/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roommemberprofile

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.text.Editable
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import im.vector.app.core.dialogs.GalleryOrCameraDialogHelper
import im.vector.app.core.dialogs.GalleryOrCameraDialogHelperFactory
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.extensions.copyOnLongClick
import im.vector.app.core.extensions.registerStartForActivityResult
import im.vector.app.core.extensions.setCopySource
import im.vector.app.core.extensions.setTextOrHide
import im.vector.app.core.platform.SimpleTextWatcher
import im.vector.app.core.platform.StateView
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.ui.colorpicker.ProfileColorPickerDialogFragment
import im.vector.app.core.ui.views.ProfileBannerUiHelper
import im.vector.app.core.utils.createJSonViewerStyleProvider
import im.vector.app.databinding.DialogBaseEditTextBinding
import im.vector.app.databinding.FragmentMatrixProfileBinding
import im.vector.app.databinding.ViewStubRoomMemberProfileHeaderBinding
import im.vector.app.features.crypto.quads.AdkFlows
import im.vector.app.features.crypto.quads.SharedSecureStorageActivity
import im.vector.app.features.crypto.verification.user.UserVerificationBottomSheet
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.BannerRenderer
import im.vector.app.features.home.room.detail.RoomDetailPendingAction
import im.vector.app.features.home.room.detail.RoomDetailPendingActionStore
import im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.media.shouldHideAvatars
import im.vector.app.features.roommemberprofile.devices.DeviceListBottomSheet
import im.vector.app.features.roommemberprofile.mutualrooms.MutualRoomsActivity
import im.vector.app.features.roommemberprofile.powerlevel.EditPowerLevelDialogs
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.core.utils.text.neutralizeDirectionOverrides
import im.vector.lib.strings.CommonStrings
import kotlinx.parcelize.Parcelize
import org.billcarsonfr.jsonviewer.JSonViewerDialog
import org.json.JSONObject
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.crypto.model.UserVerificationLevel
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.profile.ColorPreference
import org.matrix.android.sdk.api.session.room.getStateEvent
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
        RoomMemberProfileController.Callback,
        GalleryOrCameraDialogHelper.Listener {

    @Inject lateinit var roomMemberProfileController: RoomMemberProfileController
    @Inject lateinit var avatarRenderer: AvatarRenderer
    @Inject lateinit var bannerRenderer: BannerRenderer
    @Inject lateinit var roomDetailPendingActionStore: RoomDetailPendingActionStore
    @Inject lateinit var matrixItemColorProvider: MatrixItemColorProvider
    @Inject lateinit var session: Session
    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var colorProvider: ColorProvider
    @Inject lateinit var galleryOrCameraDialogHelperFactory: GalleryOrCameraDialogHelperFactory

    private lateinit var headerViews: ViewStubRoomMemberProfileHeaderBinding

    private val fragmentArgs: RoomMemberProfileArgs by args()
    private val viewModel: RoomMemberProfileViewModel by fragmentViewModel()

    private var appBarStateChangeListener: AppBarStateChangeListener? = null
    private var bannerAppBarStateChangeListener: AppBarStateChangeListener? = null
    private var bannerUiHelper: ProfileBannerUiHelper? = null
    private var currentBannerUrl: String? = null
    private var lastRenderedAvatarKey: List<Any?>? = null

    private var headerRevealed = true
    private var pendingReveal = false

    /** True from raising the cover until it has fully faded out — nothing may move the layout then. */
    private var coverActive = false
    private lateinit var galleryOrCameraDialogHelper: GalleryOrCameraDialogHelper

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentMatrixProfileBinding {
        return FragmentMatrixProfileBinding.inflate(inflater, container, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        galleryOrCameraDialogHelper = galleryOrCameraDialogHelperFactory.create(this)
        childFragmentManager.setFragmentResultListener(PROFILE_COLOR_REQUEST_KEY, this) { _, bundle -> onProfileColorPicked(bundle) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupNotesInputProxy()
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
                if (!coverActive) {
                    bannerUiHelper?.update(currentBannerUrl != null, state == State.COLLAPSED)
                }
            }
        }
        views.matrixProfileAppBarLayout.addOnOffsetChangedListener(bannerAppBarStateChangeListener)
        headerViews.memberProfileBannerView.debouncedClicks { onBannerClicked() }
        // Raise the cover here, before the enter animation runs: raising it in the first invalidate()
        // lets the empty page show during the slide-in.
        withState(viewModel) { state ->
            if (state.userMatrixItem is Loading) {
                headerRevealed = false
                showLoadingCover()
            }
        }
        views.matrixProfileLoadingBackButton.debouncedClicks { vectorBaseActivity.onBackPressedDispatcher.onBackPressed() }
        roomMemberProfileController.addModelBuildListener {
            if (pendingReveal) {
                pendingReveal = false
                // One more frame so the recycler actually lays the new models out before the reveal
                views.matrixProfileRecyclerView.post { revealHeader() }
            }
        }
        viewModel.observeViewEvents {
            when (it) {
                is RoomMemberProfileViewEvents.Loading -> showLoading(it.message)
                RoomMemberProfileViewEvents.StopLoading -> dismissLoadingDialog()
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
                is RoomMemberProfileViewEvents.RequirePersonalNoteAdk -> launchAdkFlow(it.note)
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

    override fun onPause() {
        super.onPause()
        // Safety net: persist a pending draft when leaving. Edit mode itself survives the app switch —
        // the editor resumes focused, and the system brings the keyboard back with it.
        val draft = roomMemberProfileController.personalNoteDraft
        if (roomMemberProfileController.personalNoteEditing && draft != null) {
            withState(viewModel) { state ->
                if (draft.trim() != state.personalNote?.body?.trim().orEmpty()) {
                    viewModel.handle(RoomMemberProfileAction.SetPersonalNote(draft))
                }
            }
        }
    }

    override fun onDestroyView() {
        notesInputProxy?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                it.viewTreeObserver.removeOnGlobalLayoutListener(proxyKeyboardListener)
            } else {
                @Suppress("DEPRECATION")
                it.viewTreeObserver.removeGlobalOnLayoutListener(proxyKeyboardListener)
            }
        }
        notesInputProxy = null
        lastRenderedAvatarKey = null
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
                headerRevealed = false
                // The header stays laid out and merely covered: hiding it would collapse the AppBar,
                // whose CollapsingToolbarLayout then animates its scrim — the toolbar "fading in".
                showLoadingCover()
            }
            is Fail -> {
                avatarRenderer.render(MatrixItem.UserItem(state.userId, null, null), views.matrixProfileToolbarAvatarImageView)
                views.matrixProfileToolbarTitleView.text = state.userId.neutralizeDirectionOverrides()
                val failureMessage = errorFormatter.toHumanReadable(asyncUserMatrixItem.error)
                revealHeader()
                headerViews.memberProfileStateView.state = StateView.State.Error(failureMessage)
            }
            is Success -> {
                val userMatrixItem = asyncUserMatrixItem()
                // Don't reveal yet: wait for the epoxy list (biography, More, admin…) to build and lay
                // out, so the whole page — header AND sheet below it — appears in one frame.
                if (!headerRevealed) pendingReveal = true
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
                // Re-issuing the (multi-attempt) avatar request on every state emission blanks the view
                // to the placeholder for a frame whenever Glide can't answer synchronously. With a real
                // avatar the name/color only reach the invisible placeholder, so they stay out of the key.
                val avatarKey = if (!displayedMatrixItem.avatarUrl.isNullOrEmpty()) {
                    listOf(displayedMatrixItem.avatarUrl)
                } else {
                    listOf(
                            null,
                            displayedMatrixItem.getBestName(),
                            matrixItemColorProvider.getColor(displayedMatrixItem),
                            state.colorGeneration,
                    )
                }
                if (avatarKey != lastRenderedAvatarKey) {
                    lastRenderedAvatarKey = avatarKey
                    avatarRenderer.render(displayedMatrixItem, headerViews.memberProfileAvatarView, crossfade = true)
                    avatarRenderer.render(displayedMatrixItem, views.matrixProfileToolbarAvatarImageView, crossfade = true)
                    // The Mention action below returns to the composer, so have the pill's avatar ready.
                    avatarRenderer.preloadAvatar(displayedMatrixItem, headerViews.memberProfileAvatarView)
                }

                // Follow the same hiding rule as the avatar
                currentBannerUrl = state.resolvedBannerUrl()
                        ?.takeUnless { state.userId != session.myUserId && shouldHideAvatars(state.roomId, session, vectorPreferences) }
                val hasBanner = currentBannerUrl != null
                headerViews.memberProfileBannerView.isVisible = hasBanner
                headerViews.memberProfileBannerScrim.isVisible = hasBanner
                headerViews.memberProfileBannerOverlap.isVisible = hasBanner
                bannerRenderer.render(currentBannerUrl, headerViews.memberProfileBannerView)
                bannerRenderer.applyAvatarStroke(headerViews.memberProfileAvatarView, displayedMatrixItem, hasBanner)
                // Deferred while the cover is up: this flips the window to draw under the status bar,
                // which drops the root's top padding and yanks the cover (and its back arrow) up
                // behind the status bar. Applied once the cover is gone instead.
                if (!coverActive) {
                    bannerUiHelper?.update(hasBanner, bannerAppBarStateChangeListener?.currentState == AppBarStateChangeListener.State.COLLAPSED)
                }

                if (state.isRoomEncrypted && state.userCryptoInfoLoaded) {
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
        headerViews.memberProfileFieldsView.setTextOrHide(state.profileFieldsLine)
        headerViews.memberProfilePowerLevelView.setTextOrHide(state.userPowerLevelString()?.prepareForDisplay())
        renderStatus(state)
        roomMemberProfileController.setData(state)
    }

    private fun revealHeader() {
        if (headerRevealed) {
            return
        }
        headerRevealed = true
        pendingReveal = false
        val cover = views.matrixProfileLoadingView
        // Settle the page in its final position BEHIND the cover, since with a banner this drops the
        // root's top padding and would otherwise jump the page up once the cover is gone. The cover
        // keeps its own contents put by taking that padding on itself.
        val hasBanner = currentBannerUrl != null
        cover.setPadding(0, if (hasBanner) vectorBaseActivity.systemBarsTopInset else 0, 0, 0)
        bannerUiHelper?.update(
                hasBanner,
                bannerAppBarStateChangeListener?.currentState == AppBarStateChangeListener.State.COLLAPSED
        )
        if (cover.isVisible) {
            cover.animate().alpha(0f).setDuration(COVER_FADE_MS).setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    cover.isVisible = false
                    cover.alpha = 1f
                    cover.setPadding(0, 0, 0, 0)
                    coverActive = false
                }
            })
        } else {
            coverActive = false
        }
    }

    /** Opaque panel over the whole page, so it builds unseen behind a plain spinner + back arrow. */
    private fun showLoadingCover() {
        val cover = views.matrixProfileLoadingView
        coverActive = true
        cover.animate().cancel()
        cover.alpha = 1f
        cover.isVisible = true
    }

    private fun renderStatus(state: RoomMemberProfileViewState) {
        // MSC4426: a status is never linkified, to keep it from luring anyone somewhere malicious.
        headerViews.memberProfileStatusView.setTextOrHide(state.status?.display()?.takeIf { it.isNotBlank() }?.prepareForDisplay())
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
        val roomId = fragmentArgs.roomId
        val memberEventId = roomId?.let {
            session.getRoom(it)
                    ?.getStateEvent(EventType.STATE_ROOM_MEMBER, QueryStringValue.Equals(fragmentArgs.userId))
                    ?.eventId
        }
        navigator.openBigImageViewer(
                requireActivity(),
                sharedElement = null,
                mxcUrl = userMatrixItem.avatarUrl,
                title = userMatrixItem.getBestName(),
                roomId = roomId.takeIf { memberEventId != null },
                eventId = memberEventId,
        )
    }

    private fun onBannerClicked() = withState(viewModel) { state ->
        currentBannerUrl?.let { bannerUrl ->
            navigator.openBigImageViewer(requireActivity(), null, bannerUrl, state.userMatrixItem()?.getBestName() ?: state.userId)
        }
    }

    override fun onOverrideColorClicked(theme: ProfileColorPickerDialogFragment.Theme): Unit = withState(viewModel) { state ->
        if (childFragmentManager.findFragmentByTag(PROFILE_COLOR_DIALOG_TAG) != null) return@withState
        val light = when (theme) {
            ProfileColorPickerDialogFragment.Theme.LIGHT -> true
            ProfileColorPickerDialogFragment.Theme.DARK -> false
            ProfileColorPickerDialogFragment.Theme.CURRENT -> ThemeUtils.isLightTheme(requireContext())
        }
        val item = state.userMatrixItem() ?: MatrixItem.UserItem(state.userId)
        val overrideHex = matrixItemColorProvider.overrideAxis(state.userId, light)
        val ownHex = matrixItemColorProvider.ownColorHex(item, light) ?: matrixItemColorProvider.defaultColorHex(state.userId, light)
        val titleRes = when (theme) {
            ProfileColorPickerDialogFragment.Theme.LIGHT -> CommonStrings.settings_profile_color_light
            ProfileColorPickerDialogFragment.Theme.DARK -> CommonStrings.settings_profile_color_dark
            ProfileColorPickerDialogFragment.Theme.CURRENT -> CommonStrings.settings_profile_color
        }
        ProfileColorPickerDialogFragment.newInstance(
                requestKey = PROFILE_COLOR_REQUEST_KEY,
                title = getString(titleRes),
                initialHex = overrideHex,
                defaultHex = ownHex,
                theme = theme,
                showReset = overrideHex != null,
        ).show(childFragmentManager, PROFILE_COLOR_DIALOG_TAG)
    }

    override fun onProfileColorPerThemeChanged(perTheme: Boolean) {
        viewModel.handle(RoomMemberProfileAction.SetProfileColorSameForThemes(!perTheme))
    }

    private fun onProfileColorPicked(bundle: Bundle) = withState(viewModel) { state ->
        val picked = ProfileColorPickerDialogFragment.resultToColorPreference(bundle)
        val current = state.profileOverrideColor
                ?: matrixItemColorProvider.overrideHex(state.userId, true)?.let { ColorPreference.fromHex(it) }
        val updated = when (ProfileColorPickerDialogFragment.themeOf(bundle)) {
            ProfileColorPickerDialogFragment.Theme.CURRENT -> picked
            ProfileColorPickerDialogFragment.Theme.LIGHT -> ColorPreference(picked?.onLight, current?.onDark)
            ProfileColorPickerDialogFragment.Theme.DARK -> ColorPreference(current?.onLight, picked?.onDark)
        }?.takeIf { !it.isEmpty() }
        viewModel.handle(RoomMemberProfileAction.SetProfileOverrideColor(updated))
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

    override fun onPersonalNoteChanged(note: String) {
        viewModel.handle(RoomMemberProfileAction.SetPersonalNote(note))
    }

    override fun onPersonalNoteUnlockClicked() {
        launchAdkFlow(note = null)
    }

    // An invisible off-list input with the note editor's exact input type. When the editor is
    // recycled mid-edit, focus moves here so the IME keeps a live same-typed connection (no layout
    // morphing) and typing keeps flowing into the draft; the editor takes focus back on reattach.
    private var notesInputProxy: PersonalNoteEditText? = null
    private var proxyKeyboardWasOpen = false

    private val proxyKeyboardListener = ViewTreeObserver.OnGlobalLayoutListener {
        val proxy = notesInputProxy ?: return@OnGlobalLayoutListener
        if (!proxy.hasFocus()) {
            proxyKeyboardWasOpen = false
            return@OnGlobalLayoutListener
        }
        val root = proxy.rootView ?: return@OnGlobalLayoutListener
        val frame = Rect()
        root.getWindowVisibleDisplayFrame(frame)
        if (frame.height() <= 0 || root.height <= 0) return@OnGlobalLayoutListener
        // App-switching closes the keyboard too; only a dismissal in the focused window is a save gesture
        if (!root.hasWindowFocus()) return@OnGlobalLayoutListener
        val keyboardOpen = root.height - frame.height() > root.height * 0.15
        if (keyboardOpen) {
            proxyKeyboardWasOpen = true
        } else if (proxyKeyboardWasOpen) {
            proxyKeyboardWasOpen = false
            commitNoteFromProxy()
        }
    }

    private fun setupNotesInputProxy() {
        val proxy = PersonalNoteEditText(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(1, 1)
            alpha = 0f
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            addTextChangedListener(object : SimpleTextWatcher() {
                override fun afterTextChanged(s: Editable) {
                    if (hasFocus()) roomMemberProfileController.stashPersonalNoteEdit(s.toString(), selectionStart to selectionEnd)
                }
            })
            onImeBack = { commitNoteFromProxy() }
        }
        (views.root as? ViewGroup)?.addView(proxy)
        proxy.viewTreeObserver.addOnGlobalLayoutListener(proxyKeyboardListener)
        notesInputProxy = proxy
    }

    override fun onPersonalNoteEditorDetached() {
        val proxy = notesInputProxy ?: return
        if (view == null || !roomMemberProfileController.personalNoteEditing) return
        val draft = roomMemberProfileController.personalNoteDraft ?: withState(viewModel) { it.personalNote?.body }.orEmpty()
        proxy.setText(draft)
        val selection = roomMemberProfileController.personalNoteSelection
        if (selection != null) {
            proxy.setSelection(selection.first.coerceIn(0, draft.length), selection.second.coerceIn(0, draft.length))
        } else {
            proxy.setSelection(draft.length)
        }
        proxy.requestFocus()
    }

    /** Keyboard dismissed while the off-list proxy held the edit: save the draft and leave edit mode. */
    private fun commitNoteFromProxy() {
        if (!roomMemberProfileController.personalNoteEditing) return
        val draft = roomMemberProfileController.personalNoteDraft
        roomMemberProfileController.clearPersonalNoteEdit()
        notesInputProxy?.clearFocus()
        withState(viewModel) { state ->
            if (draft != null && draft.trim() != state.personalNote?.body?.trim().orEmpty()) {
                viewModel.handle(RoomMemberProfileAction.SetPersonalNote(draft))
            } else {
                // Nothing to save; still force a rebuild so a later scroll-back shows the rendered note
                viewModel.handle(RoomMemberProfileAction.RevertPersonalNote)
            }
        }
    }

    /** Reads the ADK from 4S, or generates one and stores it there when 4S has none yet. */
    private fun launchAdkFlow(note: String?) {
        val intent = AdkFlows.buildAdkIntent(requireContext(), session)
        if (intent == null) {
            // Notes are only ever stored encrypted, so without secure backup they cannot be saved
            vectorBaseActivity.showSnackbar(getString(CommonStrings.personal_note_needs_backup))
            return
        }
        pendingNote = note
        adkActivityResultLauncher.launch(intent)
    }

    private var pendingNote: String? = null

    private val adkActivityResultLauncher = registerStartForActivityResult { activityResult ->
        val note = pendingNote
        pendingNote = null
        val cipher = activityResult.data?.getStringExtra(SharedSecureStorageActivity.EXTRA_DATA_RESULT)
        if (activityResult.resultCode == Activity.RESULT_OK && cipher != null) {
            viewModel.handle(
                    RoomMemberProfileAction.GotAdkFromSsss(cipher, SharedSecureStorageActivity.DEFAULT_RESULT_KEYSTORE_ALIAS, pendingNote = note)
            )
        } else if (note != null) {
            // Aborted: put the editor back in sync with what is actually stored
            viewModel.handle(RoomMemberProfileAction.RevertPersonalNote)
        }
    }

    override fun onOverrideDisplayNameClicked(): Unit = withState(viewModel) { state ->
        val inflater = requireActivity().layoutInflater
        val layout = inflater.inflate(R.layout.dialog_base_edit_text, null)
        val dialogViews = DialogBaseEditTextBinding.bind(layout)
        val effectiveName = state.profileOverrideDisplayName ?: state.userMatrixItem()?.getBestName()
        dialogViews.editText.setText(effectiveName)
        dialogViews.editText.hint = state.userId

        val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(CommonStrings.settings_display_name)
                .setView(layout)
                .setPositiveButton(CommonStrings.ok) { _, _ ->
                    val newName = dialogViews.editText.text.toString().trim().takeIf { it.isNotBlank() }
                    // Pre-filled with the effective name, so an unchanged value must not create an override.
                    if (newName != state.profileOverrideDisplayName && !(newName != null && newName == effectiveName)) {
                        viewModel.handle(RoomMemberProfileAction.SetProfileOverrideDisplayName(newName))
                    }
                }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .apply {
                    if (state.profileOverrideDisplayName != null) {
                        setNeutralButton(CommonStrings.room_personalization_reset) { _, _ ->
                            viewModel.handle(RoomMemberProfileAction.SetProfileOverrideDisplayName(null))
                        }
                    }
                }
                .show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(
                ThemeUtils.getColor(requireContext(), com.google.android.material.R.attr.colorError)
        )
    }

    override fun onOverrideAvatarClicked(): Unit = withState(viewModel) { state ->
        galleryOrCameraDialogHelper.show(withDeleteOption = state.profileOverrideAvatarUrl != null)
    }

    override fun onResetProfileOverridesClicked() {
        MaterialAlertDialogBuilder(requireContext())
                .setTitle(CommonStrings.action_reset)
                .setMessage(CommonStrings.user_personalization_reset_confirmation)
                .setPositiveButton(CommonStrings.action_reset) { _, _ ->
                    viewModel.handle(RoomMemberProfileAction.ResetProfileOverrides)
                }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
    }

    override fun onImageReady(uri: Uri?) {
        if (uri == null) {
            Toast.makeText(requireContext(), CommonStrings.error_handling_incoming_share, Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.handle(RoomMemberProfileAction.SetProfileOverrideAvatar(uri))
    }

    override fun onImageDeleted() {
        viewModel.handle(RoomMemberProfileAction.SetProfileOverrideAvatar(null))
    }

    override fun onViewProfileSourceClicked() = withState(viewModel) { state ->
        val profile = state.profileJson ?: return@withState
        JSonViewerDialog.newInstance(
                JSONObject(profile).toString(4),
                -1,
                createJSonViewerStyleProvider(colorProvider)
        ).show(childFragmentManager, "JSON_VIEWER")
    }

    override fun onViewSourceClicked() {
        val memberEvent = fragmentArgs.roomId?.let { roomId ->
            session.getRoom(roomId)
                    ?.getStateEvent(EventType.STATE_ROOM_MEMBER, QueryStringValue.Equals(fragmentArgs.userId))
        }
        if (memberEvent == null) return
        JSonViewerDialog.newInstance(
                memberEvent.toContentStringWithIndent(),
                -1,
                createJSonViewerStyleProvider(colorProvider)
        ).show(childFragmentManager, "JSON_VIEWER")
    }

    companion object {
        private const val PROFILE_COLOR_REQUEST_KEY = "RoomMemberProfileFragment.profileColor"
        private const val PROFILE_COLOR_DIALOG_TAG = "RoomMemberProfileFragment.profileColorDialog"
        private const val COVER_FADE_MS = 180L
    }
}
