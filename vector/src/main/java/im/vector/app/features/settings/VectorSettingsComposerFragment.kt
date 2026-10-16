/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import android.content.Context
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
        // update keep medias period
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_MEDIA_SAVING_PERIOD_KEY)!!.let {
            it.summary = vectorPreferences.getSelectedMediasSavingPeriodString()

            it.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                context?.let { context: Context ->
                    MaterialAlertDialogBuilder(context)
                            .setSingleChoiceItems(
                                    im.vector.lib.strings.R.array.media_saving_choice,
                                    vectorPreferences.getSelectedMediasSavingPeriod()
                            ) { d, n ->
                                vectorPreferences.setSelectedMediasSavingPeriod(n)
                                d.cancel()

                                it.summary = vectorPreferences.getSelectedMediasSavingPeriodString()
                            }
                            .show()
                }

                false
            }
        }

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
