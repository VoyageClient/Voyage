/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.uploads

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.airbnb.mvrx.args
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.intent.getMimeTypeFromUri
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.platform.VectorMenuProvider
import im.vector.app.core.utils.saveMedia
import im.vector.app.core.utils.shareMedia
import im.vector.app.databinding.FragmentRoomUploadsBinding
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.notifications.NotificationUtils
import im.vector.app.features.roomprofile.RoomProfileArgs
import im.vector.lib.core.utils.timer.Clock
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.util.toDisplayMatrixItem
import javax.inject.Inject

@AndroidEntryPoint
class RoomUploadsFragment :
        VectorBaseFragment<FragmentRoomUploadsBinding>(),
        VectorMenuProvider {

    @Inject lateinit var avatarRenderer: AvatarRenderer
    @Inject lateinit var notificationUtils: NotificationUtils
    @Inject lateinit var clock: Clock

    private val roomProfileArgs: RoomProfileArgs by args()

    private val viewModel: RoomUploadsViewModel by fragmentViewModel()

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentRoomUploadsBinding {
        return FragmentRoomUploadsBinding.inflate(inflater, container, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val sectionsPagerAdapter = RoomUploadsPagerAdapter(this)
        views.roomUploadsViewPager.adapter = sectionsPagerAdapter

        TabLayoutMediator(views.roomUploadsTabs, views.roomUploadsViewPager) { tab, position ->
            when (position) {
                0 -> tab.text = getString(CommonStrings.uploads_media_title)
                1 -> tab.text = getString(CommonStrings.uploads_files_title)
            }
        }.attach()
        views.roomUploadsViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = invalidateOptionsMenu()
        })

        setupToolbar(views.roomUploadsToolbar)
                .allowBack()

        viewModel.observeViewEvents {
            when (it) {
                is RoomUploadsViewEvents.FileReadyForSharing -> {
                    shareMedia(requireContext(), it.file, getMimeTypeFromUri(requireContext(), it.file.toUri()))
                }
                is RoomUploadsViewEvents.FileReadyForSaving -> {
                    lifecycleScope.launch {
                        runCatching {
                            saveMedia(
                                    context = requireContext(),
                                    file = it.file,
                                    title = it.title,
                                    mediaMimeType = getMimeTypeFromUri(requireContext(), it.file.toUri()),
                                    notificationUtils = notificationUtils,
                                    currentTimeMillis = clock.epochMillis()
                            )
                        }.onFailure { failure ->
                            if (!isAdded) return@onFailure
                            showErrorInSnackbar(failure)
                        }
                    }
                    Unit
                }
                is RoomUploadsViewEvents.Failure -> showFailure(it.throwable)
            }
        }
    }

    override fun getMenuRes() = R.menu.menu_room_uploads

    override fun handlePostCreateMenu(menu: Menu) {
        menu.findItem(R.id.menu_room_uploads_show_photos).keepMenuOpenOnTap(menu, R.id.menu_room_uploads_show_videos, RoomUploadsAction.ToggleShowPhotos)
        menu.findItem(R.id.menu_room_uploads_show_videos).keepMenuOpenOnTap(menu, R.id.menu_room_uploads_show_photos, RoomUploadsAction.ToggleShowVideos)
    }

    // An item whose collapsible action view refuses to expand takes the tap without closing the overflow popup.
    private fun MenuItem.keepMenuOpenOnTap(menu: Menu, otherId: Int, action: RoomUploadsAction) {
        actionView = View(requireContext())
        setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
        setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                // One of the two always stays on.
                if (item.isChecked && !menu.findItem(otherId).isChecked) return false
                item.isChecked = !item.isChecked
                viewModel.handle(action)
                return false
            }

            override fun onMenuItemActionCollapse(item: MenuItem) = true
        })
    }

    override fun handlePrepareMenu(menu: Menu) {
        withState(viewModel) { state ->
            val onMediaTab = views.roomUploadsViewPager.currentItem == 0
            menu.findItem(R.id.menu_room_uploads_show_photos).apply {
                isVisible = onMediaTab
                isChecked = state.showPhotos
            }
            menu.findItem(R.id.menu_room_uploads_show_videos).apply {
                isVisible = onMediaTab
                isChecked = state.showVideos
            }
        }
    }

    override fun handleMenuItemSelected(item: MenuItem) = false

    override fun invalidate() = withState(viewModel) { state ->
        renderRoomSummary(state)
    }

    private fun renderRoomSummary(state: RoomUploadsViewState) {
        state.roomSummary()?.let {
            views.roomUploadsToolbarTitleView.text = it.displayName.prepareForDisplay()
            views.roomUploadsDecorationToolbarAvatarImageView.render(it.roomEncryptionTrustLevel)
            avatarRenderer.render(it.toDisplayMatrixItem(), views.roomUploadsToolbarAvatarImageView)
        }
    }

    val roomUploadsAppBar: AppBarLayout
        get() = views.roomUploadsAppBar
}
