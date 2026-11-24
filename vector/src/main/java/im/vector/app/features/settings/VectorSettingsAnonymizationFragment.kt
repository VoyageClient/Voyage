/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.PreferenceCategory
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.features.timelineexport.AnonymizationPeoplePickerActivity
import im.vector.app.features.timelineexport.AnonymizationPreferenceBinder
import im.vector.lib.strings.CommonStrings

@AndroidEntryPoint
class VectorSettingsAnonymizationFragment : VectorSettingsBaseFragment() {

    override var titleRes = CommonStrings.export_anonymization
    override val preferenceXmlRes = R.xml.vector_settings_anonymization

    private val peoplePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayExtra(AnonymizationPeoplePickerActivity.EXTRA_USER_IDS)?.let { binder.onPeoplePicked(it.toList()) }
    }

    private val binder: AnonymizationPreferenceBinder by lazy {
        AnonymizationPreferenceBinder(this, session, launchPicker = { title, excluded, roomId ->
            peoplePicker.launch(AnonymizationPeoplePickerActivity.intent(requireContext(), title, excluded, roomId))
        })
    }

    override fun bindPref() {
        findPreference<PreferenceCategory>(AnonymizationPreferenceBinder.CATEGORY_KEY)?.let { binder.bind(it, roomId = null) }
    }
}
