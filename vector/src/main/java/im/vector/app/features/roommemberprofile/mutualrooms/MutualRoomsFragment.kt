/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roommemberprofile.mutualrooms

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.databinding.FragmentGenericRecyclerBinding
import im.vector.app.features.home.HomeActivity
import im.vector.app.features.navigation.Navigator
import javax.inject.Inject

@AndroidEntryPoint
class MutualRoomsFragment :
        VectorBaseFragment<FragmentGenericRecyclerBinding>(),
        MutualRoomsController.Callback {

    @Inject lateinit var controller: MutualRoomsController

    private val viewModel: MutualRoomsViewModel by fragmentViewModel()

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?) =
            FragmentGenericRecyclerBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        controller.callback = this
        views.genericRecyclerView.configureWith(controller)
    }

    override fun onDestroyView() {
        views.genericRecyclerView.cleanup()
        controller.callback = null
        super.onDestroyView()
    }

    override fun invalidate() = withState(viewModel) { state ->
        controller.setData(state)
    }

    override fun onRoomClicked(roomId: String) {
        navigator.openRoom(requireContext(), roomId, null)
    }

    override fun onSpaceClicked(spaceId: String) {
        // Select the space, then bring Home to the front so the list shows filtered by it.
        navigator.switchToSpace(requireContext(), spaceId, Navigator.PostSwitchSpaceAction.None)
        startActivity(
                HomeActivity.newIntent(requireContext(), firstStartMainActivity = false).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
        )
    }
}
