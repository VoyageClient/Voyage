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
import im.vector.lib.strings.CommonStrings

@AndroidEntryPoint
class VectorSettingsRootFragment :
        VectorSettingsBaseFragment() {

    override var titleRes: Int = CommonStrings.title_activity_settings
    override val preferenceXmlRes = R.xml.vector_settings_root

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun bindPref() {
        tintIcons()
        findPreference<VectorPreference>("SETTINGS_IMAGE_PACKS_KEY")?.setOnPreferenceClickListener {
            startActivity(im.vector.app.features.imagepack.edit.ImagePackListActivity.newIntent(requireContext(), roomId = null))
            true
        }
    }

    private fun tintIcons() {
        for (i in 0 until preferenceScreen.preferenceCount) {
            (preferenceScreen.getPreference(i) as? VectorPreference)?.let { it.tintIcon = true }
        }
    }
}
