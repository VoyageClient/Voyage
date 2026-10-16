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
import im.vector.app.core.preference.VectorPreference
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class VectorSettingsPreferencesFragment :
        VectorSettingsBaseFragment() {

    @Inject lateinit var vectorLocale: VectorLocale

    override var titleRes = CommonStrings.settings_preferences
    override val preferenceXmlRes = R.xml.vector_settings_preferences

    override fun bindPref() {
        val selectedLanguagePreference = findPreference<VectorPreference>(VectorPreferences.SETTINGS_INTERFACE_LANGUAGE_PREFERENCE_KEY)!!
        selectedLanguagePreference.summary = vectorLocale.localeToLocalisedString(vectorLocale.applicationLocale)
        // The display name depends on which other locales we ship (country/script only when ambiguous).
        if (vectorLocale.canLoadLocalesInBackground) {
            lifecycleScope.launch {
                vectorLocale.getSupportedLocales()
                selectedLanguagePreference.summary = vectorLocale.localeToLocalisedString(vectorLocale.applicationLocale)
            }
        }
    }
}
