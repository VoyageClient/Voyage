/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.login

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.databinding.FragmentLoginServerSelectionBinding

/**
 * In this screen, the user chooses to sign in with a Matrix ID or to connect to a custom homeserver.
 */
@AndroidEntryPoint
class LoginServerSelectionFragment :
        AbstractLoginFragment<FragmentLoginServerSelectionBinding>() {

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentLoginServerSelectionBinding {
        return FragmentLoginServerSelectionBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        views.loginServerChoiceMatrixId.debouncedClicks { loginWithMatrixId() }
        views.loginServerChoiceOther.debouncedClicks { selectOther() }
    }

    private fun selectOther() {
        loginViewModel.handle(LoginAction.UpdateServerType(ServerType.Other))
    }

    private fun loginWithMatrixId() {
        loginViewModel.handle(LoginAction.UpdateSignMode(SignMode.SignInWithMatrixId))
    }

    override fun resetViewModel() {
        loginViewModel.handle(LoginAction.ResetHomeServerType)
    }

    override fun updateWithState(state: LoginViewState) {
        // Nothing to do
    }
}
