/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.core.preference.VectorSwitchPreference
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

@AndroidEntryPoint
class VectorSettingsTimelineFragment :
        VectorSettingsBaseFragment() {

    @Inject lateinit var vectorPreferences: VectorPreferences

    override var titleRes = CommonStrings.settings_category_timeline
    override val preferenceXmlRes = R.xml.vector_settings_timeline

    override fun bindPref() {
        findPreference<VectorSwitchPreference>(VectorPreferences.SETTINGS_AUTOPLAY_ANIMATED_IMAGES)!!.let { pref ->
            pref.setOnPreferenceChangeListener { _, _ ->
                // Part of the configuration hash, so the screens behind this one rebuild their bound
                // thumbnails on resume. Acknowledging is posted because the new value is only
                // persisted once this listener returns, and it keeps this screen from restarting too.
                view?.post { (activity as? VectorBaseActivity<*>)?.acknowledgeConfigurationChange() }
                true
            }
        }

        updatePerformanceModeDependents(vectorPreferences.isPerformanceModeEnabled())
    }

    // Settings performance mode forces off: unchecked and locked until it's toggled back off.
    private fun updatePerformanceModeDependents(performanceMode: Boolean) {
        listOf(
                VectorPreferences.SETTINGS_AUTOPLAY_ANIMATED_IMAGES,
                VectorPreferences.SETTINGS_SHOW_URL_PREVIEW_KEY,
        ).forEach { key ->
            findPreference<VectorSwitchPreference>(key)?.let { pref ->
                if (performanceMode) pref.isChecked = false
                pref.isEnabled = !performanceMode
            }
        }
    }
}
