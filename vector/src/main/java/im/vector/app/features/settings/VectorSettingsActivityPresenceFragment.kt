/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.preference.VectorSwitchPreference
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.presence.model.PresenceEnum
import javax.inject.Inject

@AndroidEntryPoint
class VectorSettingsActivityPresenceFragment :
        VectorSettingsBaseFragment() {

    @Inject lateinit var vectorPreferences: VectorPreferences

    override var titleRes = CommonStrings.settings_activity_presence_title
    override val preferenceXmlRes = R.xml.vector_settings_activity_presence

    override fun bindPref() {
        findPreference<VectorSwitchPreference>(VectorPreferences.SETTINGS_PRESENCE_USER_ALWAYS_APPEARS_OFFLINE)!!.let { pref ->
            pref.isChecked = vectorPreferences.userAlwaysAppearsOffline()
            pref.setOnPreferenceChangeListener { _, newValue ->
                val presenceOfflineModeEnabled = newValue as? Boolean ?: false
                lifecycleScope.launch {
                    session.presenceService().setMyPresence(if (presenceOfflineModeEnabled) PresenceEnum.OFFLINE else PresenceEnum.ONLINE)
                }
                true
            }
        }
    }
}
