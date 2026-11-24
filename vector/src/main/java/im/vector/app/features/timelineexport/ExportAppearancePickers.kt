/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import androidx.appcompat.view.ContextThemeWrapper
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.R
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.core.preference.ColorMatrixPickerView
import im.vector.app.core.ui.colorpicker.AvatarShapePickerDialogFragment
import im.vector.app.core.ui.colorpicker.CodePalettePickerDialogFragment
import im.vector.app.core.ui.colorpicker.DefaultAvatarPickerDialogFragment
import im.vector.app.core.ui.colorpicker.PalettePickerDialogFragment
import im.vector.app.features.settings.AvatarShape
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings

object ExportAppearancePickers {
    fun accent(activity: FragmentActivity, selected: String, preview: (String) -> Unit) {
        val resources = activity.resources
        var picked = selected
        val context = ContextThemeWrapper(activity, ThemeUtils.getApplicationThemeRes(activity))
        val picker = ColorMatrixPickerView(context,
                resources.getTextArray(R.array.sc_accent_color_light_entries), resources.getTextArray(R.array.sc_accent_color_light_values),
                resources.getTextArray(R.array.sc_accent_color_previews), selected, resources.getInteger(R.integer.accent_color_trailing_row_count)) { picked = it; preview(it) }
        var applied = false
        MaterialAlertDialogBuilder(context).setTitle(CommonStrings.settings_accent).setView(picker)
                .setPositiveButton(android.R.string.ok) { _, _ -> applied = true; preview(picked) }
                .setNegativeButton(android.R.string.cancel, null).create().apply {
                    setOnDismissListener { if (!applied) preview(selected) }
                }.show()
    }

    fun avatarShape(activity: FragmentActivity, selected: String, update: (String) -> Unit) {
        val key = "export-avatar-shape"
        if (activity.supportFragmentManager.findFragmentByTag(key) != null) return
        activity.supportFragmentManager.setFragmentResultListener(key, activity) { _, result ->
            result.getString(AvatarShapePickerDialogFragment.RESULT_SHAPE)?.let { update(AvatarShape.of(it).name) }
        }
        val shape = AvatarShape.values().firstOrNull { it.name == selected } ?: AvatarShape.CIRCLE
        AvatarShapePickerDialogFragment.newInstance(key, activity.getString(CommonStrings.settings_avatar_shape_title), shape.storageKey)
                .show(activity.supportFragmentManager, key)
    }

    fun codePalette(activity: FragmentActivity, selected: String, update: (String) -> Unit) {
        val key = "export-code-palette"
        if (activity.supportFragmentManager.findFragmentByTag(key) != null) return
        activity.supportFragmentManager.setFragmentResultListener(key, activity) { _, result ->
            result.getString(CodePalettePickerDialogFragment.RESULT_PALETTE)?.let(update)
        }
        CodePalettePickerDialogFragment.newInstance(key, activity.getString(CommonStrings.settings_code_palette), selected)
                .show(activity.supportFragmentManager, key)
    }

    fun palette(activity: FragmentActivity, room: Boolean, selected: String, update: (String) -> Unit) {
        val key = "export-palette-" + room
        if (activity.supportFragmentManager.findFragmentByTag(key) != null) return
        activity.supportFragmentManager.setFragmentResultListener(key, activity) { _, result ->
            result.getString(PalettePickerDialogFragment.RESULT_PALETTE)?.let(update)
        }
        val prefs = (activity as? VectorBaseActivity<*>)?.vectorPreferences
        val fallback = if (room) prefs?.roomColorPalette()?.name else prefs?.peopleColorPalette()?.name
        PalettePickerDialogFragment.newInstance(key, activity.getString(if (room) CommonStrings.settings_room_color_palette_title else CommonStrings.settings_people_color_palette_title),
                if (room) PalettePickerDialogFragment.Kind.ROOM else PalettePickerDialogFragment.Kind.PEOPLE,
                selected.takeUnless { it == "CURRENT" } ?: fallback.orEmpty()).show(activity.supportFragmentManager, key)
    }

    fun avatar(activity: FragmentActivity, room: Boolean, selected: String, update: (String) -> Unit) {
        val key = "export-avatar-" + room
        if (activity.supportFragmentManager.findFragmentByTag(key) != null) return
        activity.supportFragmentManager.setFragmentResultListener(key, activity) { _, result ->
            result.getString(DefaultAvatarPickerDialogFragment.RESULT_STYLE)?.let(update)
        }
        val prefs = (activity as? VectorBaseActivity<*>)?.vectorPreferences
        val fallback = if (room) prefs?.roomAvatarStyle()?.name else prefs?.peopleAvatarStyle()?.name
        DefaultAvatarPickerDialogFragment.newInstance(key, activity.getString(if (room) CommonStrings.settings_room_avatar_style_title else CommonStrings.settings_people_avatar_style_title),
                if (room) DefaultAvatarPickerDialogFragment.Kind.ROOM else DefaultAvatarPickerDialogFragment.Kind.PEOPLE,
                selected.takeUnless { it == "CURRENT" } ?: fallback.orEmpty()).show(activity.supportFragmentManager, key)
    }
}
