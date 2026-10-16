/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import androidx.preference.Preference
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.preference.VectorSwitchPreference
import im.vector.app.features.MainActivity
import im.vector.app.features.MainActivityArgs
import im.vector.app.features.home.ShortcutsHandler
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

@AndroidEntryPoint
class VectorSettingsRoomsSpacesFragment :
        VectorSettingsBaseFragment() {

    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var shortcutsHandler: ShortcutsHandler

    override var titleRes = CommonStrings.settings_rooms_spaces_title
    override val preferenceXmlRes = R.xml.vector_settings_rooms_spaces

    override fun bindPref() {
        findPreference<Preference>(VectorPreferences.SETTINGS_PREF_SPACE_CATEGORY)!!.let { pref ->
            pref.isVisible = !vectorPreferences.isNewAppLayoutEnabled()
            pref.isEnabled = !vectorPreferences.isNewAppLayoutEnabled()
        }

        findPreference<VectorSwitchPreference>(VectorPreferences.SETTINGS_PREF_SPACE_SHOW_ALL_ROOM_IN_HOME)!!.let { pref ->
            pref.isChecked = vectorPreferences.prefSpacesShowAllRoomInHome()
            pref.setOnPreferenceChangeListener { _, _ ->
                MainActivity.restartApp(requireActivity(), MainActivityArgs(clearCache = false))
                true
            }
        }

        findPreference<VectorSwitchPreference>(VectorPreferences.SETTINGS_SINGLE_OVERVIEW)!!.let { pref ->
            // Combined overview is a legacy-layout feature, force it off and grey it out under the new UI.
            // The change is applied by HomeActivity once the user leaves settings (like the new UI toggle).
            pref.isEnabled = !vectorPreferences.isNewAppLayoutEnabled()
        }

        findPreference<VectorSwitchPreference>("SETTINGS_ENABLE_APP_SHORTCUTS")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == false) {
                // Drop current dynamic shortcuts immediately; otherwise they'd hang around
                // until the next room summaries emission.
                shortcutsHandler.removeAllDynamicShortcuts()
            }
            // When re-enabled, the next emission of the room summaries flow will repopulate.
            true
        }
    }
}
