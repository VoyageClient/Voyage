/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.onboarding.gateway

import android.content.SharedPreferences
import im.vector.app.core.di.DefaultPreferences
import javax.inject.Inject
import javax.inject.Singleton

/** App-wide, so the pre-sign-in screen and the signed-in settings edit the same values. */
@Singleton
class RegistrationGatewaySettings @Inject constructor(
        @DefaultPreferences private val prefs: SharedPreferences,
) {
    companion object {
        const val KEY_URL = "SETTINGS_REGISTRATION_GATEWAY_URL"
        const val KEY_TOKEN = "SETTINGS_REGISTRATION_GATEWAY_TOKEN"
    }

    val url: String get() = prefs.getString(KEY_URL, null)?.trim().orEmpty()
    val token: String get() = prefs.getString(KEY_TOKEN, null)?.trim().orEmpty()

    val isConfigured: Boolean get() = url.isNotEmpty()
}
