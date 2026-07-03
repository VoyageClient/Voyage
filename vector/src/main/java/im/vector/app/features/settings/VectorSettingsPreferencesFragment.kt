/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.dialogs.PhotoOrVideoDialog
import im.vector.app.core.extensions.registerStartForActivityResult
import im.vector.app.core.extensions.restart
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.core.utils.toast
import im.vector.app.features.emoji.CustomEmojiFontStore
import im.vector.app.core.preference.ColorMatrixListPreference
import im.vector.app.core.preference.ColorMatrixListPreferenceDialogFragment
import im.vector.app.core.preference.VectorListPreference
import im.vector.app.core.preference.VectorPreference
import im.vector.app.core.preference.VectorSwitchPreference
import im.vector.app.features.MainActivity
import im.vector.app.features.MainActivityArgs
import im.vector.app.features.VectorFeatures
import im.vector.app.features.home.ShortcutsHandler
import im.vector.app.features.settings.font.FontScaleSettingActivity
import im.vector.app.features.settings.reactions.QuickReactionsSettingsActivity
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.presence.model.PresenceEnum
import javax.inject.Inject

@AndroidEntryPoint
class VectorSettingsPreferencesFragment :
        VectorSettingsBaseFragment() {

    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var fontScalePreferences: FontScalePreferences
    @Inject lateinit var vectorFeatures: VectorFeatures
    @Inject lateinit var vectorLocale: VectorLocale
    @Inject lateinit var shortcutsHandler: ShortcutsHandler
    @Inject lateinit var customEmojiFontStore: CustomEmojiFontStore
    @Inject lateinit var appLogoManager: AppLogoManager

    private val emojiFontPickerLauncher = registerStartForActivityResult { activityResult ->
        if (activityResult.resultCode != Activity.RESULT_OK) return@registerStartForActivityResult
        val uri = activityResult.data?.data ?: return@registerStartForActivityResult
        customEmojiFontStore.import(uri).fold(
                onSuccess = { MainActivity.restartProcess(requireActivity()) },
                onFailure = { requireContext().toast(getString(CommonStrings.settings_custom_emoji_font_invalid)) },
        )
    }

    override var titleRes = CommonStrings.settings_preferences
    override val preferenceXmlRes = R.xml.vector_settings_preferences

    private val selectedLanguagePreference by lazy {
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_INTERFACE_LANGUAGE_PREFERENCE_KEY)!!
    }
    private val textSizePreference by lazy {
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_INTERFACE_TEXT_SIZE_KEY)!!
    }
    private val takePhotoOrVideoPreference by lazy {
        findPreference<VectorPreference>("SETTINGS_INTERFACE_TAKE_PHOTO_VIDEO")!!
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference is ColorMatrixListPreference) {
            if (parentFragmentManager.findFragmentByTag(COLOR_MATRIX_DIALOG_TAG) != null) return
            ColorMatrixListPreferenceDialogFragment.newInstance(preference.key).apply {
                @Suppress("DEPRECATION")
                setTargetFragment(this@VectorSettingsPreferencesFragment, 0)
            }.show(parentFragmentManager, COLOR_MATRIX_DIALOG_TAG)
        } else {
            super.onDisplayPreferenceDialog(preference)
        }
    }

    override fun bindPref() {
        // user interface preferences
        setUserInterfacePreferences()

        // Themes
        findPreference<VectorListPreference>(ThemeUtils.APPLICATION_THEME_KEY)!!
                .onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            if (newValue is String) {
                ThemeUtils.setApplicationTheme(requireContext().applicationContext, newValue)
                // recreate() (vs restart()) re-themes while preserving the settings back stack, so we stay
                // on the Preferences screen, same as the accent picker below.
                (activity as? VectorBaseActivity<*>)?.acknowledgeConfigurationChange()
                activity?.recreate()
                true
            } else {
                false
            }
        }

        // App logo: swaps the home-screen launcher icon (via activity-aliases) and the in-app marks.
        findPreference<VectorListPreference>(AppLogo.SETTINGS_APP_LOGO_KEY)!!
                .onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            if (newValue is String) {
                appLogoManager.setCurrent(AppLogo.fromStorageValue(newValue))
                true
            } else {
                false
            }
        }

        findPreference<ColorMatrixListPreference>(ThemeUtils.SETTINGS_SC_ACCENT_LIGHT)!!
                .onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            if (newValue is String) {
                ThemeUtils.setApplicationThemeAccent(requireContext().applicationContext, newValue)
                // recreate() (vs restart()) re-themes while preserving the settings back stack, so we stay
                // on the Preferences screen. acknowledgeConfigurationChange() stops the configuration watcher
                // from then forcing a full restart (which would drop us back to the root settings page).
                (activity as? VectorBaseActivity<*>)?.acknowledgeConfigurationChange()
                activity?.recreate()
                true
            } else {
                false
            }
        }

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

        findPreference<VectorListPreference>(VectorPreferences.SETTINGS_AVATAR_SHAPE_KEY)!!.let { pref ->
            pref.setOnPreferenceChangeListener { _, _ ->
                // Restart so every already-bound avatar picks up the new shape.
                MainActivity.restartApp(requireActivity(), MainActivityArgs(clearCache = false))
                true
            }
        }

        findPreference<VectorSwitchPreference>(VectorPreferences.SETTINGS_PREF_SPACE_SHOW_ALL_ROOM_IN_HOME)!!.let { pref ->
            pref.isChecked = vectorPreferences.prefSpacesShowAllRoomInHome()
            pref.setOnPreferenceChangeListener { _, _ ->
                MainActivity.restartApp(requireActivity(), MainActivityArgs(clearCache = false))
                true
            }
        }

        findPreference<VectorSwitchPreference>(VectorPreferences.SETTINGS_PERFORMANCE_MODE_KEY)?.setOnPreferenceChangeListener { _, newValue ->
            // Update the runtime mirror so new binds pick it up without a restart.
            im.vector.app.core.ui.PerformanceMode.enabled = newValue as Boolean
            true
        }

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

        findPreference<Preference>(VectorPreferences.SETTINGS_PREF_SPACE_CATEGORY)!!.let { pref ->
            pref.isVisible = !vectorPreferences.isNewAppLayoutEnabled()
            pref.isEnabled = !vectorPreferences.isNewAppLayoutEnabled()
        }

        // Url preview
        /*
        TODO Note: we keep the setting client side for now
        findPreference<SwitchPreference>(VectorPreferences.SETTINGS_SHOW_URL_PREVIEW_KEY)!!.let {
            it.isChecked = session.isURLPreviewEnabled

            it.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                if (null != newValue && newValue as Boolean != session.isURLPreviewEnabled) {
                    displayLoadingView()
                    session.setURLPreviewStatus(newValue, object : MatrixCallback<Unit> {
                        override fun onSuccess(info: Void?) {
                            it.isChecked = session.isURLPreviewEnabled
                            hideLoadingView()
                        }

                        private fun onError(errorMessage: String) {
                            activity?.toast(errorMessage)

                            onSuccess(null)
                        }

                        override fun onNetworkError(e: Exception) {
                            onError(e.localizedMessage)
                        }

                        override fun onMatrixError(e: MatrixError) {
                            onError(e.localizedMessage)
                        }

                        override fun onUnexpectedError(e: Exception) {
                            onError(e.localizedMessage)
                        }
                    })
                }

                false
            }
        }
         */

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

    // ==============================================================================================================
    // user interface management
    // ==============================================================================================================

    private fun setUserInterfacePreferences() {
        // Selected language
        selectedLanguagePreference.summary = vectorLocale.localeToLocalisedString(vectorLocale.applicationLocale)

        // Text size
        textSizePreference.summary = getString(fontScalePreferences.getResolvedFontScaleValue().nameResId)

        textSizePreference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            startActivity(Intent(activity, FontScaleSettingActivity::class.java))
            true
        }

        findPreference<VectorPreference>(VectorPreferences.SETTINGS_QUICK_REACTIONS_KEY)!!
                .onPreferenceClickListener = Preference.OnPreferenceClickListener {
            startActivity(QuickReactionsSettingsActivity.newIntent(requireContext()))
            true
        }
    }

    companion object {
        private const val COLOR_MATRIX_DIALOG_TAG = "ColorMatrixListPreferenceDialog"
    }
}
