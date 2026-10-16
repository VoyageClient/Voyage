/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import android.content.Intent
import androidx.preference.Preference
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.core.preference.ColorMatrixListPreference
import im.vector.app.core.preference.ColorMatrixListPreferenceDialogFragment
import im.vector.app.core.preference.VectorListPreference
import im.vector.app.core.preference.VectorPreference
import im.vector.app.core.preference.VectorSwitchPreference
import im.vector.app.core.ui.colorpicker.AvatarShapePickerDialogFragment
import im.vector.app.core.ui.colorpicker.CodePalettePickerDialogFragment
import im.vector.app.core.ui.colorpicker.ColorPalette
import im.vector.app.core.ui.colorpicker.DefaultAvatarPickerDialogFragment
import im.vector.app.core.ui.colorpicker.PalettePickerDialogFragment
import im.vector.app.features.MainActivity
import im.vector.app.features.MainActivityArgs
import im.vector.app.features.home.avatar.DefaultAvatarStyle
import im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider
import im.vector.app.features.html.highlight.CodeBlockHighlighter
import im.vector.app.features.html.highlight.CodePalette
import im.vector.app.features.settings.font.FontScaleSettingActivity
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

@AndroidEntryPoint
class VectorSettingsAppearanceFragment :
        VectorSettingsBaseFragment() {

    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var fontScalePreferences: FontScalePreferences
    @Inject lateinit var appLogoManager: AppLogoManager
    @Inject lateinit var matrixItemColorProvider: MatrixItemColorProvider
    @Inject lateinit var codeBlockHighlighter: CodeBlockHighlighter

    override var titleRes = CommonStrings.settings_appearance_title
    override val preferenceXmlRes = R.xml.vector_settings_appearance

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference is ColorMatrixListPreference) {
            if (parentFragmentManager.findFragmentByTag(COLOR_MATRIX_DIALOG_TAG) != null) return
            ColorMatrixListPreferenceDialogFragment.newInstance(preference.key).apply {
                @Suppress("DEPRECATION")
                setTargetFragment(this@VectorSettingsAppearanceFragment, 0)
            }.show(parentFragmentManager, COLOR_MATRIX_DIALOG_TAG)
        } else {
            super.onDisplayPreferenceDialog(preference)
        }
    }

    override fun bindPref() {
        findPreference<VectorListPreference>(ThemeUtils.APPLICATION_THEME_KEY)!!
                .onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            if (newValue is String) {
                ThemeUtils.setApplicationTheme(requireContext().applicationContext, newValue)
                // recreate() (vs restart()) re-themes while preserving the settings back stack, so we stay
                // on this screen, same as the accent picker below.
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
                appLogoManager.setCurrentLogo(AppLogo.fromStorageValue(newValue))
                true
            } else {
                false
            }
        }

        findPreference<ColorMatrixListPreference>(AppIconColor.SETTINGS_APP_ICON_COLOR_KEY)!!
                .onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            if (newValue is String) {
                appLogoManager.setCurrentColor(AppIconColor.fromStorageValue(newValue))
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
                // on this screen. acknowledgeConfigurationChange() stops the configuration watcher
                // from then forcing a full restart (which would drop us back to the root settings page).
                (activity as? VectorBaseActivity<*>)?.acknowledgeConfigurationChange()
                activity?.recreate()
                true
            } else {
                false
            }
        }

        findPreference<VectorPreference>(VectorPreferences.SETTINGS_INTERFACE_TEXT_SIZE_KEY)!!.let { pref ->
            pref.summary = getString(fontScalePreferences.getResolvedFontScaleValue().nameResId)
            pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                startActivity(Intent(activity, FontScaleSettingActivity::class.java))
                true
            }
        }

        bindAvatarShapePreference()

        // The change listener runs before the value is persisted, so invalidate on the next frame.
        val invalidateColors = Preference.OnPreferenceChangeListener { _, _ ->
            view?.post { matrixItemColorProvider.invalidate() }
            true
        }
        findPreference<VectorSwitchPreference>(VectorPreferences.SETTINGS_SHOW_OTHERS_PROFILE_COLORS_KEY)?.onPreferenceChangeListener = invalidateColors
        bindPalettePreferences()
        bindCodePalettePreference()
        bindDefaultAvatarPreferences()

        findPreference<VectorSwitchPreference>(VectorPreferences.SETTINGS_PERFORMANCE_MODE_KEY)?.setOnPreferenceChangeListener { _, newValue ->
            // Update the runtime mirror so new binds pick it up without a restart.
            val performanceMode = newValue as Boolean
            im.vector.app.core.ui.PerformanceMode.enabled = performanceMode
            vectorPreferences.applyPerformanceModeConstraints(performanceMode)
            true
        }
    }

    private fun bindPalettePreferences() {
        val peoplePref = findPreference<VectorPreference>(VectorPreferences.SETTINGS_PEOPLE_COLOR_PALETTE_KEY)
        val roomPref = findPreference<VectorPreference>(VectorPreferences.SETTINGS_ROOM_COLOR_PALETTE_KEY)

        childFragmentManager.setFragmentResultListener(PEOPLE_PALETTE_REQUEST_KEY, this) { _, bundle ->
            val picked = bundle.getString(PalettePickerDialogFragment.RESULT_PALETTE) ?: return@setFragmentResultListener
            vectorPreferences.setPeopleColorPalette(ColorPalette.valueOf(picked))
            matrixItemColorProvider.invalidate()
        }
        childFragmentManager.setFragmentResultListener(ROOM_PALETTE_REQUEST_KEY, this) { _, bundle ->
            val picked = bundle.getString(PalettePickerDialogFragment.RESULT_PALETTE) ?: return@setFragmentResultListener
            vectorPreferences.setRoomColorPalette(ColorPalette.valueOf(picked))
            matrixItemColorProvider.invalidate()
        }

        peoplePref?.setOnPreferenceClickListener {
            showPalettePicker(
                    PEOPLE_PALETTE_REQUEST_KEY,
                    getString(CommonStrings.settings_people_color_palette_title),
                    PalettePickerDialogFragment.Kind.PEOPLE,
                    vectorPreferences.peopleColorPalette().name,
            )
            true
        }
        roomPref?.setOnPreferenceClickListener {
            showPalettePicker(
                    ROOM_PALETTE_REQUEST_KEY,
                    getString(CommonStrings.settings_room_color_palette_title),
                    PalettePickerDialogFragment.Kind.ROOM,
                    vectorPreferences.roomColorPalette().name,
            )
            true
        }
    }

    private fun bindCodePalettePreference() {
        val pref = findPreference<VectorPreference>(VectorPreferences.SETTINGS_CODE_PALETTE_KEY) ?: return
        // Ready the picker's previews while this screen is up, so it opens with them already colored.
        codeBlockHighlighter.prefetch(CodePalettePickerDialogFragment.PREVIEW_REQUEST)
        childFragmentManager.setFragmentResultListener(CODE_PALETTE_REQUEST_KEY, this) { _, bundle ->
            vectorPreferences.setCodeBlockPalette(CodePalette.of(bundle.getString(CodePalettePickerDialogFragment.RESULT_PALETTE)))
            codeBlockHighlighter.notifyPaletteChanged()
        }
        pref.setOnPreferenceClickListener {
            if (childFragmentManager.findFragmentByTag(CODE_PALETTE_REQUEST_KEY) == null) {
                CodePalettePickerDialogFragment.newInstance(
                        CODE_PALETTE_REQUEST_KEY,
                        getString(CommonStrings.settings_code_palette),
                        vectorPreferences.codeBlockPalette().name,
                ).show(childFragmentManager, CODE_PALETTE_REQUEST_KEY)
            }
            true
        }
    }

    private fun bindAvatarShapePreference() {
        val pref = findPreference<VectorPreference>(VectorPreferences.SETTINGS_AVATAR_SHAPE_KEY) ?: return
        pref.summary = getString(vectorPreferences.avatarShape().titleRes)

        childFragmentManager.setFragmentResultListener(AVATAR_SHAPE_REQUEST_KEY, this) { _, bundle ->
            val picked = bundle.getString(AvatarShapePickerDialogFragment.RESULT_SHAPE) ?: return@setFragmentResultListener
            vectorPreferences.setAvatarShape(AvatarShape.of(picked))
            // Restart so every already-bound avatar picks up the new shape: Glide's cache keys bake
            // the transform in.
            MainActivity.restartApp(requireActivity(), MainActivityArgs(clearCache = false))
        }

        pref.setOnPreferenceClickListener {
            if (childFragmentManager.findFragmentByTag(AVATAR_SHAPE_REQUEST_KEY) == null) {
                AvatarShapePickerDialogFragment.newInstance(
                        AVATAR_SHAPE_REQUEST_KEY,
                        getString(CommonStrings.settings_avatar_shape_title),
                        vectorPreferences.avatarShape().storageKey,
                ).show(childFragmentManager, AVATAR_SHAPE_REQUEST_KEY)
            }
            true
        }
    }

    private fun bindDefaultAvatarPreferences() {
        val peoplePref = findPreference<VectorPreference>(VectorPreferences.SETTINGS_PEOPLE_AVATAR_STYLE_KEY)
        val roomPref = findPreference<VectorPreference>(VectorPreferences.SETTINGS_ROOM_AVATAR_STYLE_KEY)

        fun refreshSummaries() {
            peoplePref?.summary = getString(vectorPreferences.peopleAvatarStyle().titleRes)
            roomPref?.summary = getString(vectorPreferences.roomAvatarStyle().titleRes)
        }
        refreshSummaries()

        childFragmentManager.setFragmentResultListener(PEOPLE_AVATAR_STYLE_REQUEST_KEY, this) { _, bundle ->
            val picked = bundle.getString(DefaultAvatarPickerDialogFragment.RESULT_STYLE) ?: return@setFragmentResultListener
            vectorPreferences.setPeopleAvatarStyle(DefaultAvatarStyle.of(picked, DefaultAvatarStyle.PEOPLE))
            refreshSummaries()
            matrixItemColorProvider.invalidate()
        }
        childFragmentManager.setFragmentResultListener(ROOM_AVATAR_STYLE_REQUEST_KEY, this) { _, bundle ->
            val picked = bundle.getString(DefaultAvatarPickerDialogFragment.RESULT_STYLE) ?: return@setFragmentResultListener
            vectorPreferences.setRoomAvatarStyle(DefaultAvatarStyle.of(picked, DefaultAvatarStyle.ROOM))
            refreshSummaries()
            matrixItemColorProvider.invalidate()
        }

        peoplePref?.setOnPreferenceClickListener {
            showAvatarStylePicker(
                    PEOPLE_AVATAR_STYLE_REQUEST_KEY,
                    getString(CommonStrings.settings_people_avatar_style_title),
                    DefaultAvatarPickerDialogFragment.Kind.PEOPLE,
                    vectorPreferences.peopleAvatarStyle().name,
            )
            true
        }
        roomPref?.setOnPreferenceClickListener {
            showAvatarStylePicker(
                    ROOM_AVATAR_STYLE_REQUEST_KEY,
                    getString(CommonStrings.settings_room_avatar_style_title),
                    DefaultAvatarPickerDialogFragment.Kind.ROOM,
                    vectorPreferences.roomAvatarStyle().name,
            )
            true
        }
    }

    private fun showAvatarStylePicker(requestKey: String, title: String, kind: DefaultAvatarPickerDialogFragment.Kind, selected: String) {
        if (childFragmentManager.findFragmentByTag(requestKey) != null) return
        DefaultAvatarPickerDialogFragment.newInstance(requestKey, title, kind, selected)
                .show(childFragmentManager, requestKey)
    }

    private fun showPalettePicker(requestKey: String, title: String, kind: PalettePickerDialogFragment.Kind, selected: String) {
        if (childFragmentManager.findFragmentByTag(requestKey) != null) return
        PalettePickerDialogFragment.newInstance(requestKey, title, kind, selected)
                .show(childFragmentManager, requestKey)
    }

    companion object {
        private const val COLOR_MATRIX_DIALOG_TAG = "ColorMatrixListPreferenceDialog"
        private const val PEOPLE_PALETTE_REQUEST_KEY = "VectorSettingsAppearanceFragment.peoplePalette"
        private const val ROOM_PALETTE_REQUEST_KEY = "VectorSettingsAppearanceFragment.roomPalette"
        private const val CODE_PALETTE_REQUEST_KEY = "VectorSettingsAppearanceFragment.codePalette"
        private const val PEOPLE_AVATAR_STYLE_REQUEST_KEY = "VectorSettingsAppearanceFragment.peopleAvatarStyle"
        private const val ROOM_AVATAR_STYLE_REQUEST_KEY = "VectorSettingsAppearanceFragment.roomAvatarStyle"
        private const val AVATAR_SHAPE_REQUEST_KEY = "VectorSettingsAppearanceFragment.avatarShape"
    }
}
