/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.colorpicker

import android.graphics.Color
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import im.vector.app.core.preference.ProfileColorPreference
import im.vector.app.features.themes.ThemeUtils
import org.matrix.android.sdk.api.session.profile.ColorPreference

/**
 * Drives the "Profile Color" rows of a preference screen: a light and a dark row when the
 * "per-theme colors" switch is on, a single row otherwise. [stored] is what this scope has set,
 * [inherited] what applies when it hasn't (e.g. the account color on a room screen) and
 * [defaultHex] the client's hash color.
 */
class ProfileColorPreferenceBinder(
        private val fragment: Fragment,
        private val single: ProfileColorPreference,
        private val light: ProfileColorPreference,
        private val dark: ProfileColorPreference,
        private val perThemeSwitch: SwitchPreference,
        requestKeyPrefix: String,
        private val resetIsDelete: Boolean,
        private val onSave: (ColorPreference?) -> Unit,
) {
    private val requestKey = "$requestKeyPrefix.profileColor"
    private val dialogTag = "$requestKeyPrefix.profileColorDialog"

    private var stored: ColorPreference? = null
    private var inherited: ColorPreference? = null
    private var defaultHex: (Boolean) -> String = { "#000000" }
    private var same = true
    private var sameInitialized = false

    init {
        fragment.childFragmentManager.setFragmentResultListener(requestKey, fragment) { _, bundle ->
            val theme = ProfileColorPickerDialogFragment.themeOf(bundle)
            val picked = ProfileColorPickerDialogFragment.resultToColorPreference(bundle)
            val updated = when (theme) {
                ProfileColorPickerDialogFragment.Theme.CURRENT -> picked
                ProfileColorPickerDialogFragment.Theme.LIGHT -> ColorPreference(picked?.onLight, stored?.onDark)
                ProfileColorPickerDialogFragment.Theme.DARK -> ColorPreference(stored?.onLight, picked?.onDark)
            }?.takeIf { !it.isEmpty() }
            onSave(updated)
        }
        single.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            open(ProfileColorPickerDialogFragment.Theme.CURRENT, single.title.toString())
            true
        }
        light.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            open(ProfileColorPickerDialogFragment.Theme.LIGHT, light.title.toString())
            true
        }
        dark.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            open(ProfileColorPickerDialogFragment.Theme.DARK, dark.title.toString())
            true
        }
        perThemeSwitch.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            same = !(newValue as Boolean)
            refresh()
            true
        }
    }

    fun update(stored: ColorPreference?, inherited: ColorPreference?, defaultHex: (light: Boolean) -> String) {
        this.stored = stored
        this.inherited = inherited
        this.defaultHex = defaultHex
        if (!sameInitialized) {
            same = stored == null || stored.onLight == null || stored.onDark == null || stored.onLight == stored.onDark
            perThemeSwitch.isChecked = !same
            sameInitialized = true
        }
        refresh()
    }

    private fun refresh() {
        single.isVisible = same
        light.isVisible = !same
        dark.isVisible = !same
        bind(single, ThemeUtils.isLightTheme(fragment.requireContext()))
        bind(light, true)
        bind(dark, false)
    }

    private fun bind(preference: ProfileColorPreference, forLight: Boolean) {
        val own = stored?.axis(forLight)
        val hex = own ?: inherited?.forTheme(forLight) ?: defaultHex(forLight)
        preference.setColor(Color.parseColor(hex), hex, light = forLight, isDefault = own == null)
    }

    private fun ColorPreference.axis(light: Boolean) = if (light) onLight else onDark

    private fun open(theme: ProfileColorPickerDialogFragment.Theme, title: String) {
        val manager = fragment.childFragmentManager
        if (manager.findFragmentByTag(dialogTag) != null) return
        val forLight = when (theme) {
            ProfileColorPickerDialogFragment.Theme.LIGHT -> true
            ProfileColorPickerDialogFragment.Theme.DARK -> false
            ProfileColorPickerDialogFragment.Theme.CURRENT -> ThemeUtils.isLightTheme(fragment.requireContext())
        }
        val own = stored?.axis(forLight)
        ProfileColorPickerDialogFragment.newInstance(
                requestKey = requestKey,
                title = title,
                initialHex = own,
                defaultHex = inherited?.forTheme(forLight) ?: defaultHex(forLight),
                theme = theme,
                showReset = own != null,
                resetIsDelete = resetIsDelete,
        ).show(manager, dialogTag)
    }
}
