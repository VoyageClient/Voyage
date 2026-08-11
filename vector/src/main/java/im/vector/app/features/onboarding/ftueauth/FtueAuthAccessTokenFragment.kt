/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.onboarding.ftueauth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.extensions.content
import im.vector.app.core.extensions.hideKeyboard
import im.vector.app.core.extensions.toReducedUrl
import im.vector.app.databinding.FragmentFtueAuthAccessTokenBinding
import im.vector.app.features.onboarding.OnboardingAction
import im.vector.app.features.onboarding.OnboardingViewState
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import reactivecircus.flowbinding.android.widget.textChanges

/**
 * In this screen, the user pastes an access token issued by the already selected homeserver.
 */
@AndroidEntryPoint
class FtueAuthAccessTokenFragment :
        AbstractFtueAuthFragment<FragmentFtueAuthAccessTokenBinding>() {

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentFtueAuthAccessTokenBinding {
        return FragmentFtueAuthAccessTokenBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        views.loginAccessTokenSubmit.setOnClickListener { submit() }

        views.loginAccessTokenField.textChanges()
                .onEach {
                    views.loginAccessTokenFieldTil.error = null
                    views.loginAccessTokenSubmit.isEnabled = it.isNotBlank()
                }
                .launchIn(viewLifecycleOwner.lifecycleScope)

        views.loginAccessTokenField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
    }

    private fun submit() {
        views.loginAccessTokenField.hideKeyboard()
        val token = views.loginAccessTokenFieldTil.content()
        if (token.isBlank()) {
            views.loginAccessTokenFieldTil.error = getString(CommonStrings.login_signin_token_empty_error)
            return
        }
        views.loginAccessTokenFieldTil.error = null
        viewModel.handle(OnboardingAction.LoginWithAccessToken(token))
    }

    override fun resetViewModel() {
        viewModel.handle(OnboardingAction.ResetAuthenticationAttempt)
    }

    override fun onError(throwable: Throwable) {
        views.loginAccessTokenFieldTil.error = errorFormatter.toHumanReadable(throwable)
    }

    override fun updateWithState(state: OnboardingViewState) {
        views.loginAccessTokenServer.text = getString(
                CommonStrings.login_connect_to,
                state.selectedHomeserver.userFacingUrl.toReducedUrl()
        )
    }
}
