/*
 * Copyright 2021-2024 SchildiChat and New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.lib.strings.CommonStrings

@AndroidEntryPoint
class VectorSettingsBubbleAppearanceFragment :
        VectorSettingsBaseFragment() {

    override var titleRes = CommonStrings.settings_sc_bubble_appearance
    override val preferenceXmlRes = R.xml.vector_settings_bubble_appearance

    override fun bindPref() {
    }
}
