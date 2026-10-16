/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.registerStartForActivityResult
import im.vector.app.core.preference.VectorPreference
import im.vector.app.core.preference.VectorSwitchPreference
import im.vector.app.core.utils.toast
import im.vector.app.features.MainActivity
import im.vector.app.features.emoji.CustomEmojiFontStore
import im.vector.app.features.settings.reactions.QuickReactionsSettingsActivity
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

@AndroidEntryPoint
class VectorSettingsEmojiFragment :
        VectorSettingsBaseFragment() {

    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var customEmojiFontStore: CustomEmojiFontStore

    private val emojiFontPickerLauncher = registerStartForActivityResult { activityResult ->
        if (activityResult.resultCode != Activity.RESULT_OK) return@registerStartForActivityResult
        val uri = activityResult.data?.data ?: return@registerStartForActivityResult
        customEmojiFontStore.import(uri).fold(
                onSuccess = { MainActivity.restartProcess(requireActivity()) },
                onFailure = { requireContext().toast(getString(CommonStrings.settings_custom_emoji_font_invalid)) },
        )
    }

    override var titleRes = CommonStrings.settings_emoji_reactions_title
    override val preferenceXmlRes = R.xml.vector_settings_emoji

    override fun bindPref() {
        findPreference<VectorSwitchPreference>(VectorPreferences.SETTINGS_USE_TWEMOJI_KEY)!!.let { pref ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                // No usable system emoji font below KitKat: Twemoji is mandatory, so lock the toggle on.
                pref.isChecked = true
                pref.isEnabled = false
            } else {
                pref.setOnPreferenceChangeListener { _, newValue ->
                    // The emoji path is wired once in Application.onCreate, so a full process restart is
                    // needed for the switch to take effect — an activity-only restart wouldn't re-run it.
                    // Persist synchronously first (the framework's own persist runs only after this
                    // returns, which is too late once restartProcess kills us), then return false so it
                    // isn't double-written.
                    val enabled = newValue as Boolean
                    vectorPreferences.setUseTwemoji(enabled)
                    // Mutually exclusive with the system-emoji-font mode: turning one on turns the other off.
                    if (enabled) vectorPreferences.setUseSystemEmojiFont(false)
                    MainActivity.restartProcess(requireActivity())
                    false
                }
            }
        }

        findPreference<VectorSwitchPreference>(VectorPreferences.SETTINGS_USE_SYSTEM_EMOJI_FONT_KEY)!!.let { pref ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                // Below KitKat emoji2 is already a no-op and Twemoji is forced, so this has no effect.
                pref.isChecked = false
                pref.isEnabled = false
            } else {
                pref.setOnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as Boolean
                    vectorPreferences.setUseSystemEmojiFont(enabled)
                    // Mutually exclusive with Twemoji: turning one on turns the other off.
                    if (enabled) vectorPreferences.setUseTwemoji(false)
                    MainActivity.restartProcess(requireActivity())
                    false
                }
            }
        }

        findPreference<VectorPreference>(VectorPreferences.SETTINGS_CUSTOM_EMOJI_FONT_KEY)!!.let { pref ->
            // Only meaningful when emoji2 is actually applied: KitKat+, not Twemoji, not system font.
            pref.isEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT &&
                    !vectorPreferences.useTwemoji() &&
                    !vectorPreferences.useSystemEmojiFont()
            pref.summary = customEmojiFontStore.displayName() ?: getString(CommonStrings.settings_custom_emoji_font_builtin)
            pref.setOnPreferenceClickListener {
                showCustomEmojiFontDialog()
                true
            }
        }

        findPreference<VectorPreference>(VectorPreferences.SETTINGS_QUICK_REACTIONS_KEY)!!
                .onPreferenceClickListener = Preference.OnPreferenceClickListener {
            startActivity(QuickReactionsSettingsActivity.newIntent(requireContext()))
            true
        }
    }

    private fun showCustomEmojiFontDialog() {
        val choose = getString(CommonStrings.settings_custom_emoji_font_choose)
        val reset = getString(CommonStrings.settings_custom_emoji_font_reset)
        val items = if (customEmojiFontStore.isAvailable()) arrayOf(choose, reset) else arrayOf(choose)
        MaterialAlertDialogBuilder(requireActivity())
                .setTitle(CommonStrings.settings_custom_emoji_font_title)
                .setItems(items) { _, which ->
                    if (which == 0) {
                        launchEmojiFontPicker()
                    } else {
                        customEmojiFontStore.reset()
                        MainActivity.restartProcess(requireActivity())
                    }
                }
                .show()
    }

    private fun launchEmojiFontPicker() {
        // ACTION_OPEN_DOCUMENT is KitKat+, which matches where this option is enabled. A .ttf reports a
        // variety of MIME types, so offer the common ones and fall back to all files.
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("font/ttf", "application/x-font-ttf", "application/octet-stream"))
        }
        emojiFontPickerLauncher.launch(intent)
    }
}
