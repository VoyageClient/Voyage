/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.extensions.lazyViewModel
import im.vector.app.core.extensions.validateBackPressed
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.core.platform.lifecycleAwareLazy
import im.vector.app.databinding.ActivityLoginBinding
import im.vector.app.features.login.LoginConfig
import im.vector.app.features.pin.UnlockedActivity
import im.vector.app.features.settings.StealthModeStore
import im.vector.app.features.settings.useragent.UserAgentSettings
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.auth.AuthenticationService
import javax.inject.Inject

@AndroidEntryPoint
class OnboardingActivity : VectorBaseActivity<ActivityLoginBinding>(), UnlockedActivity {

    private val onboardingVariant by lifecycleAwareLazy {
        onboardingVariantFactory.create(this, views = views, onboardingViewModel = lazyViewModel())
    }

    @Inject lateinit var onboardingVariantFactory: OnboardingVariantFactory
    @Inject lateinit var authenticationService: AuthenticationService
    @Inject lateinit var userAgentSettings: UserAgentSettings
    @Inject lateinit var stealthModeStore: StealthModeStore

    override fun getBinding() = ActivityLoginBinding.inflate(layoutInflater)

    override fun getCoordinatorLayout() = views.coordinatorLayout

    override val rootView: View
        get() = views.coordinatorLayout

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        onboardingVariant.onNewIntent(intent)
    }

    override fun onDestroy() {
        // Backing out of onboarding without signing in means the user gave up: drop any pre-login spoof/
        // stealth choices. On a successful sign-in they were already migrated into the account and cleared,
        // so this is a no-op then.
        if (isFinishing) {
            userAgentSettings.abandonPending()
            stealthModeStore.abandonPending(activeSessionHolder.getSafeActiveSession()?.myUserId)
        }
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        validateBackPressed {
            super.onBackPressed()
        }
    }

    override fun initUiAndData() {
        onboardingVariant.initUiAndData(isFirstCreation())
        if (intent.getBooleanExtra(EXTRA_KEEP_EXISTING_SESSION, false)) {
            views.loginAddAccountBack.isVisible = true
            views.loginAddAccountBack.setOnClickListener {
                // Reset any partially-entered homeserver / login state so a re-entry to
                // "+ Add account" starts from a clean slate instead of resuming this attempt.
                lifecycleScope.launch {
                    runCatching { authenticationService.reset() }
                    finish()
                }
            }
        }
    }

    // Hack for AccountCreatedFragment
    fun setIsLoading(isLoading: Boolean) {
        onboardingVariant.setIsLoading(isLoading)
    }

    companion object {
        const val EXTRA_CONFIG = "EXTRA_CONFIG"
        const val EXTRA_KEEP_EXISTING_SESSION = "EXTRA_KEEP_EXISTING_SESSION"

        fun newIntent(context: Context, loginConfig: LoginConfig?): Intent {
            return Intent(context, OnboardingActivity::class.java).apply {
                putExtra(EXTRA_CONFIG, loginConfig)
            }
        }

        fun redirectIntent(context: Context, data: Uri?): Intent {
            return Intent(context, OnboardingActivity::class.java).apply {
                setData(data)
            }
        }
    }
}
