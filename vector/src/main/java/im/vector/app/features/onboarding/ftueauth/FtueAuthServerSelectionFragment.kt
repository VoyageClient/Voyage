/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.onboarding.ftueauth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.databinding.FragmentLoginServerSelectionBinding
import im.vector.app.features.login.ServerType
import im.vector.app.features.login.SignMode
import im.vector.app.features.onboarding.OnboardingAction
import im.vector.app.features.onboarding.OnboardingViewState

/**
 * In this screen, the user chooses to sign in with a Matrix ID or to connect to a custom homeserver.
 */
@AndroidEntryPoint
class FtueAuthServerSelectionFragment :
        AbstractFtueAuthFragment<FragmentLoginServerSelectionBinding>() {

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentLoginServerSelectionBinding {
        return FragmentLoginServerSelectionBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        views.loginServerChoiceMatrixId.setOnClickListener { loginWithMatrixId() }
        views.loginServerChoiceOther.setOnClickListener { selectOther() }
    }

    private fun selectOther() {
        viewModel.handle(OnboardingAction.UpdateServerType(ServerType.Other))
    }

    private fun loginWithMatrixId() {
        viewModel.handle(OnboardingAction.UpdateSignMode(SignMode.SignInWithMatrixId))
    }

    override fun resetViewModel() {
        viewModel.handle(OnboardingAction.ResetHomeServerType)
    }

    override fun updateWithState(state: OnboardingViewState) {
        // Nothing to do
    }
}
