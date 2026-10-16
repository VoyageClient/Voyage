/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.devtools

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.airbnb.mvrx.activityViewModel
import com.airbnb.mvrx.withState
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.epoxy.ErrorWithRetryItem_
import im.vector.app.core.epoxy.ListDividerDecoration
import im.vector.app.core.epoxy.LoadingItem_
import im.vector.app.core.epoxy.NoResultItem_
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.databinding.FragmentGenericRecyclerBinding
import javax.inject.Inject

@AndroidEntryPoint
class RoomDevToolStateEventListFragment :
        VectorBaseFragment<FragmentGenericRecyclerBinding>(),
        DevToolsInteractionListener {

    @Inject lateinit var epoxyController: RoomStateListController

    val sharedViewModel: RoomDevToolViewModel by activityViewModel()

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentGenericRecyclerBinding {
        return FragmentGenericRecyclerBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        views.genericRecyclerView.configureWith(epoxyController)
        views.genericRecyclerView.addItemDecoration(
                ListDividerDecoration(requireContext(), drawUnder = { model ->
                    model !is LoadingItem_ && model !is NoResultItem_ && model !is ErrorWithRetryItem_
                })
        )
        epoxyController.interactionListener = this
    }

    override fun onDestroyView() {
        views.genericRecyclerView.cleanup()
        epoxyController.interactionListener = null
        super.onDestroyView()
    }

    override fun invalidate() = withState(sharedViewModel) { state ->
        epoxyController.setData(state)
    }

    override fun processAction(action: RoomDevToolAction) {
        sharedViewModel.handle(action)
    }
}
