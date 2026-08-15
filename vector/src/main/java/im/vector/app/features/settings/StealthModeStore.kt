/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import im.vector.app.core.di.DefaultPreferences
import org.matrix.android.sdk.api.session.accountdata.StealthAccountData
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-account "Stealth mode" toggle. Namespaced by the account's user id so it never carries over
 * between logged-in accounts, and mirrored into the process-wide [StealthAccountData] flag that the
 * SDK write path reads. Call [apply] whenever the active account changes.
 */
@Singleton
class StealthModeStore @Inject constructor(
        @DefaultPreferences private val prefs: SharedPreferences,
) {
    fun isEnabled(userId: String): Boolean = prefs.getBoolean(key(userId), false)

    fun setEnabled(userId: String, enabled: Boolean) {
        prefs.edit { putBoolean(key(userId), enabled) }
        StealthAccountData.enabled = enabled
    }

    /** Seed the SDK flag from [userId]'s stored value, or disable it when no account is active. */
    fun apply(userId: String?) {
        StealthAccountData.enabled = userId != null && isEnabled(userId)
    }

    private fun key(userId: String) = "SETTINGS_SECURITY_STEALTH_MODE_$userId"
}
