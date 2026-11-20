/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.gradients

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.databinding.FragmentGenericRecyclerBinding
import im.vector.app.features.settings.VectorPreferences
import javax.inject.Inject

@AndroidEntryPoint
class GradientListFragment :
        VectorBaseFragment<FragmentGenericRecyclerBinding>(),
        GradientListController.Listener {

    @Inject lateinit var controller: GradientListController
    @Inject lateinit var vectorPreferences: VectorPreferences

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?) =
            FragmentGenericRecyclerBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        controller.listener = this
        views.genericRecyclerView.configureWith(controller)
    }

    override fun onResume() {
        super.onResume()
        controller.setData(vectorPreferences.getCustomGradients())
    }

    override fun onDestroyView() {
        views.genericRecyclerView.cleanup()
        controller.listener = null
        super.onDestroyView()
    }

    override fun onGradientClicked(gradient: CustomGradient) {
        startActivity(GradientEditActivity.newIntent(requireContext(), gradient.label))
    }

    override fun onAddGradient() {
        startActivity(GradientEditActivity.newIntent(requireContext(), label = null))
    }
}
