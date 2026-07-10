/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.orEmpty
import im.vector.app.core.preference.VectorPreference
import im.vector.app.core.resources.BuildMeta
import im.vector.app.core.utils.FirstThrottler
import im.vector.app.core.utils.copyToClipboard
import im.vector.app.core.utils.openAppSettingsPage
import im.vector.app.core.utils.openUrlInChromeCustomTab
import im.vector.app.features.version.VersionProvider
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.Matrix
import javax.inject.Inject

@AndroidEntryPoint
class VectorSettingsHelpAboutFragment :
        VectorSettingsBaseFragment() {

    @Inject lateinit var versionProvider: VersionProvider
    @Inject lateinit var buildMeta: BuildMeta

    override var titleRes = CommonStrings.preference_root_help_about
    override val preferenceXmlRes = R.xml.vector_settings_help_about

    private val firstThrottler = FirstThrottler(1000)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun bindPref() {
        // Help
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_HELP_PREFERENCE_KEY)!!
                .onPreferenceClickListener = Preference.OnPreferenceClickListener {
            if (firstThrottler.canHandle() is FirstThrottler.CanHandlerResult.Yes) {
                openUrlInChromeCustomTab(requireContext(), null, VectorSettingsUrls.HELP)
            }
            false
        }

        // preference to start the App info screen, to facilitate App permissions access
        findPreference<VectorPreference>(APP_INFO_LINK_PREFERENCE_KEY)!!
                .onPreferenceClickListener = Preference.OnPreferenceClickListener {
            activity?.let { openAppSettingsPage(it) }
            true
        }

        // application version
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_VERSION_PREFERENCE_KEY)!!.let {
            it.summary = buildString {
                append(versionProvider.getVersion(longFormat = false))
                if (buildMeta.isDebug) {
                    append(" ")
                    append(buildMeta.gitBranchName)
                    append(" ")
                    append(buildMeta.gitRevision)
                }
            }

            it.setOnPreferenceClickListener { pref ->
                copyToClipboard(requireContext(), pref.summary.orEmpty())
                true
            }
        }

        // SDK version
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_SDK_VERSION_PREFERENCE_KEY)!!.let {
            it.summary = Matrix.getSdkVersion()

            it.setOnPreferenceClickListener { pref ->
                copyToClipboard(requireContext(), pref.summary.orEmpty())
                true
            }
        }

        // olm version
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_CRYPTO_VERSION_PREFERENCE_KEY)!!.let {
            it.summary = Matrix.getCryptoVersion(true)

            it.setOnPreferenceClickListener {
                onCryptoVersionTapped()
                true
            }
        }
    }

    // AOSP's developer-options tap counter (Build number on About phone), minus the unlock: taps 1-2
    // are silent, taps 3-6 toast the remaining count, the 7th plays the video and wraps back around.
    private var kitkatCountdown = KITKAT_TAPS
    private var kitkatToast: Toast? = null

    override fun onResume() {
        super.onResume()
        kitkatCountdown = KITKAT_TAPS
    }

    override fun onPause() {
        super.onPause()
        kitkatToast?.cancel()
        kitkatToast = null
    }

    private fun onCryptoVersionTapped() {
        kitkatCountdown--
        when {
            kitkatCountdown in 1..4 -> {
                val message = if (kitkatCountdown == 1) {
                    getString(CommonStrings.one_step_away_from_kitkat)
                } else {
                    getString(CommonStrings.steps_away_from_kitkat, kitkatCountdown)
                }
                kitkatToast?.cancel()
                kitkatToast = Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).also { it.show() }
            }
            kitkatCountdown <= 0 -> {
                kitkatCountdown = KITKAT_TAPS
                kitkatToast?.cancel()
                KitkatVideoActivity.start(requireContext())
            }
        }
    }

    companion object {
        private const val APP_INFO_LINK_PREFERENCE_KEY = "APP_INFO_LINK_PREFERENCE_KEY"
        private const val KITKAT_TAPS = 7
    }
}
