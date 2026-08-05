/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomdirectory.pendingrequests

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.databinding.FragmentRoomSettingGenericBinding
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import javax.inject.Inject

@AndroidEntryPoint
class PendingJoinRequestsFragment :
        VectorBaseFragment<FragmentRoomSettingGenericBinding>(),
        PendingJoinRequestsController.Listener {

    @Inject lateinit var controller: PendingJoinRequestsController

    private val viewModel: PendingJoinRequestsViewModel by fragmentViewModel()

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentRoomSettingGenericBinding {
        return FragmentRoomSettingGenericBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        controller.listener = this
        setupToolbar(views.roomSettingsToolbar)
                .allowBack()
        // This screen has no room avatar; hide the avatar slot so the title sits next to the back arrow.
        views.roomSettingsToolbarAvatarImageView.isVisible = false
        views.roomSettingsDecorationToolbarAvatarImageView.isVisible = false
        views.roomSettingsToolbarTitleView.text = getString(CommonStrings.pending_join_requests_title)
        views.roomSettingsRecyclerView.configureWith(controller, hasFixedSize = true)
    }

    override fun onDestroyView() {
        controller.listener = null
        views.roomSettingsRecyclerView.cleanup()
        super.onDestroyView()
    }

    override fun onCancelRequest(roomSummary: RoomSummary) {
        viewModel.handle(PendingJoinRequestsViewAction.CancelRequest(roomSummary.roomId))
    }

    override fun invalidate() = withState(viewModel) { state ->
        controller.setData(state)
    }
}
