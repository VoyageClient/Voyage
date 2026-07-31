/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.knock

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.airbnb.mvrx.args
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.utils.toast
import im.vector.app.databinding.FragmentRoomSettingGenericBinding
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.roomprofile.RoomProfileArgs
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.api.util.toDisplayMatrixItem
import javax.inject.Inject

@AndroidEntryPoint
class RoomKnockRequestsFragment :
        VectorBaseFragment<FragmentRoomSettingGenericBinding>(),
        RoomKnockRequestsController.Callback {

    @Inject lateinit var controller: RoomKnockRequestsController
    @Inject lateinit var avatarRenderer: AvatarRenderer

    private val viewModel: RoomKnockRequestsViewModel by fragmentViewModel()
    private val roomProfileArgs: RoomProfileArgs by args()

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentRoomSettingGenericBinding {
        return FragmentRoomSettingGenericBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        controller.callback = this
        setupToolbar(views.roomSettingsToolbar)
                .allowBack()
        views.roomSettingsToolbarTitleView.text = getString(CommonStrings.room_knock_requests_title)
        views.roomSettingsRecyclerView.configureWith(controller, hasFixedSize = true)

        viewModel.observeViewEvents {
            when (it) {
                is RoomKnockRequestsViewEvents.ToastMessage -> requireActivity().toast(it.info)
            }
        }
    }

    override fun onDestroyView() {
        views.roomSettingsRecyclerView.cleanup()
        controller.callback = null
        super.onDestroyView()
    }

    override fun onAcceptClicked(roomMember: RoomMemberSummary) {
        viewModel.handle(RoomKnockRequestsAction.Accept(roomMember))
    }

    override fun onDeclineClicked(roomMember: RoomMemberSummary) {
        viewModel.handle(RoomKnockRequestsAction.Decline(roomMember))
    }

    override fun invalidate() = withState(viewModel) { viewState ->
        controller.setData(viewState)
        viewState.roomSummary()?.let {
            avatarRenderer.render(it.toDisplayMatrixItem(), views.roomSettingsToolbarAvatarImageView)
            views.roomSettingsDecorationToolbarAvatarImageView.render(it.roomEncryptionTrustLevel)
        }
        Unit
    }
}
