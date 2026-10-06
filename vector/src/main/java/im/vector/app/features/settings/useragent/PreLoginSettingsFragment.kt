/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.useragent

import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.addFragmentToBackstack
import im.vector.app.core.preference.VectorPreference
import im.vector.app.features.settings.VectorSettingsBaseFragment
import im.vector.lib.strings.CommonStrings

@AndroidEntryPoint
class PreLoginSettingsFragment : VectorSettingsBaseFragment() {

    override var titleRes = CommonStrings.settings_prelogin_title
    override val preferenceXmlRes = R.xml.vector_settings_pre_login

    override fun bindPref() {
        findPreference<VectorPreference>("SETTINGS_PRELOGIN_USER_AGENT")?.setOnPreferenceClickListener {
            addFragmentToBackstack(R.id.container, VectorSettingsUserAgentFragment::class.java)
            true
        }
    }
}
