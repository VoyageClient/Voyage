/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.useragent

import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.extensions.addFragmentToBackstack
import im.vector.app.core.preference.VectorPreference
import im.vector.app.core.preference.VectorSwitchPreference
import im.vector.app.features.settings.StealthModeStore
import im.vector.app.features.settings.VectorSettingsBaseFragment
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

/** Per-account settings the user can set before signing in: the stealth toggle and the UA spoof. */
@AndroidEntryPoint
class PreLoginSettingsFragment : VectorSettingsBaseFragment() {

    @Inject lateinit var stealthModeStore: StealthModeStore
    @Inject lateinit var activeSessionHolder: ActiveSessionHolder

    override var titleRes = CommonStrings.settings_prelogin_title
    override val preferenceXmlRes = R.xml.vector_settings_pre_login

    override fun bindPref() {
        findPreference<VectorSwitchPreference>("SETTINGS_PRELOGIN_STEALTH")?.apply {
            isChecked = stealthModeStore.isPendingEnabled()
            setOnPreferenceChangeListener { _, newValue ->
                // Only touch the live flag when signed out; during add-account it must not affect the current account.
                val signedOut = activeSessionHolder.getSafeActiveSession() == null
                stealthModeStore.setPendingEnabled(newValue as Boolean, applyToProcess = signedOut)
                true
            }
        }
        findPreference<VectorPreference>("SETTINGS_PRELOGIN_USER_AGENT")?.setOnPreferenceClickListener {
            addFragmentToBackstack(R.id.container, VectorSettingsUserAgentFragment::class.java)
            true
        }
    }
}
