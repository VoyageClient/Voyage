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
import im.vector.app.core.dialogs.PhotoOrVideoDialog
import im.vector.app.core.preference.VectorPreference
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

@AndroidEntryPoint
class VectorSettingsComposerFragment :
        VectorSettingsBaseFragment() {

    @Inject lateinit var vectorPreferences: VectorPreferences

    override var titleRes = CommonStrings.settings_category_message_composer
    override val preferenceXmlRes = R.xml.vector_settings_composer

    private val takePhotoOrVideoPreference by lazy {
        findPreference<VectorPreference>("SETTINGS_INTERFACE_TAKE_PHOTO_VIDEO")!!
    }

    override fun bindPref() {
        // Take photo or video
        updateTakePhotoOrVideoPreferenceSummary()
        takePhotoOrVideoPreference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            PhotoOrVideoDialog(requireActivity(), vectorPreferences).showForSettings(object : PhotoOrVideoDialog.PhotoOrVideoDialogSettingsListener {
                override fun onUpdated() {
                    updateTakePhotoOrVideoPreferenceSummary()
                }
            })
            true
        }
    }

    private fun updateTakePhotoOrVideoPreferenceSummary() {
        takePhotoOrVideoPreference.summary = getString(
                when (vectorPreferences.getTakePhotoVideoMode()) {
                    VectorPreferences.TAKE_PHOTO_VIDEO_MODE_PHOTO -> CommonStrings.option_take_photo
                    VectorPreferences.TAKE_PHOTO_VIDEO_MODE_VIDEO -> CommonStrings.option_take_video
                    /* VectorPreferences.TAKE_PHOTO_VIDEO_MODE_ALWAYS_ASK */
                    else -> CommonStrings.option_always_ask
                }
        )
    }
}
