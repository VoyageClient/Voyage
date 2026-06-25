/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.edit

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.airbnb.mvrx.args
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.databinding.FragmentGenericRecyclerBinding
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class ImagePackListFragment :
        VectorBaseFragment<FragmentGenericRecyclerBinding>(),
        ImagePackListController.Listener {

    @Inject lateinit var repository: ImagePackRepository
    @Inject lateinit var controller: ImagePackListController

    private val listArgs: ImagePackListArgs by args()

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?) =
            FragmentGenericRecyclerBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        controller.listener = this
        views.genericRecyclerView.configureWith(controller)
        // Live + off-main: reflects saves/toggles without re-entering, and won't ANR on large scans.
        repository.listDataLive(listArgs.roomId)
                .onEach { controller.setData(it) }
                .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    override fun onDestroyView() {
        views.genericRecyclerView.cleanup()
        controller.listener = null
        super.onDestroyView()
    }

    override fun onPackClicked(pack: ManagedPack) {
        startActivity(
                ImagePackEditActivity.newIntent(
                        requireContext(),
                        roomId = pack.roomId,
                        stateKey = pack.stateKey ?: "",
                        canEdit = pack.canEdit,
                        displayName = pack.displayName,
                )
        )
    }

    override fun onGlobalToggled(pack: ManagedPack, enabled: Boolean) {
        val roomId = pack.roomId ?: return
        val stateKey = pack.stateKey ?: return
        lifecycleScope.launch {
            // The live flow refreshes the list once the account-data write lands; no manual refresh needed.
            try {
                repository.setPackEnabledGlobally(roomId, stateKey, enabled)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                if (isAdded) showFailure(failure)
            }
        }
    }

    override fun onCreateAccountPack() {
        startActivity(ImagePackEditActivity.newIntent(requireContext(), roomId = null, stateKey = "", canEdit = true))
    }

    override fun onCreateRoomPack() {
        // A room can hold several packs, each under a distinct state_key.
        startActivity(ImagePackEditActivity.newIntent(requireContext(), roomId = listArgs.roomId, stateKey = UUID.randomUUID().toString(), canEdit = true))
    }
}
