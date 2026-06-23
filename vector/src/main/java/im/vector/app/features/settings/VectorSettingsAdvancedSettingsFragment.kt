/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import android.os.Bundle
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.preference.VectorPreference
import im.vector.app.core.preference.VectorPreferenceCategory
import im.vector.app.core.preference.VectorSwitchPreference
import im.vector.app.core.utils.PerfTrace
import im.vector.app.core.utils.copyToClipboard
import im.vector.app.features.analytics.plan.MobileScreen
import im.vector.app.features.home.NightlyProxy
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

@AndroidEntryPoint
class VectorSettingsAdvancedSettingsFragment :
        VectorSettingsBaseFragment() {

    override var titleRes = CommonStrings.settings_advanced_settings
    override val preferenceXmlRes = R.xml.vector_settings_advanced_settings

    @Inject lateinit var nightlyProxy: NightlyProxy
    @Inject lateinit var vectorPreferences: VectorPreferences


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        analyticsScreenName = MobileScreen.ScreenName.SettingsAdvanced
    }

    override fun bindPref() {
        setupNightlySection()
        setupDevToolsSection()
        setupPerfLoggingToggle()
    }

    private fun setupPerfLoggingToggle() {
        // Re-sync now in case Developer Mode was toggled elsewhere; push immediate updates
        // when this toggle flips. (Toggling Developer Mode off here doesn't actively flip
        // PerfTrace.isEnabled — the next app start re-seeds via VectorApplication.onCreate,
        // and the XML `dependency` already disables this row visually when dev mode is off.)
        PerfTrace.isEnabled = vectorPreferences.isPerfLoggingEnabled()
        findPreference<VectorSwitchPreference>("SETTINGS_PERF_LOGGING_ENABLED")?.setOnPreferenceChangeListener { _, newValue ->
            PerfTrace.isEnabled = (newValue as? Boolean == true) && vectorPreferences.developerMode()
            true
        }
    }

    private fun setupDevToolsSection() {
        findPreference<VectorPreference>("SETTINGS_ACCESS_TOKEN")?.setOnPreferenceClickListener {
            copyToClipboard(requireActivity(), session.sessionParams.credentials.accessToken)
            true
        }

        findPreference<VectorPreference>(VectorPreferences.SETTINGS_DEVELOPER_MODE_KEY_REQUEST_AUDIT_KEY)?.apply {
            isVisible = session.cryptoService().supportKeyRequestInspection()
        }
    }

    private fun setupNightlySection() {
        findPreference<VectorPreferenceCategory>("SETTINGS_NIGHTLY_BUILD_PREFERENCE_KEY")?.isVisible = nightlyProxy.isNightlyBuild()
        findPreference<VectorPreference>("SETTINGS_NIGHTLY_BUILD_UPDATE_PREFERENCE_KEY")?.setOnPreferenceClickListener {
            nightlyProxy.updateApplication()
            true
        }
    }
}
