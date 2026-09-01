/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomdirectory.createroom

import android.text.InputType
import android.view.Gravity
import androidx.fragment.app.Fragment
import com.airbnb.epoxy.EpoxyController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.R
import im.vector.app.core.epoxy.profiles.buildProfileAction
import im.vector.app.core.resources.StringProvider
import im.vector.app.databinding.DialogBaseEditTextBinding
import im.vector.app.features.form.formAdvancedToggleItem
import im.vector.app.features.form.formSwitchItem
import im.vector.lib.strings.CommonStrings

interface AdvancedRoomOptionsListener {
    fun toggleShowAdvanced()
    fun setDisableFederation(disableFederation: Boolean)
    fun selectRoomVersion()
    fun selectMyPowerLevel()
    fun editInitialState()
}

/**
 * Renders the "show advanced" toggle and everything it reveals.
 */
fun EpoxyController.buildAdvancedRoomOptions(
        options: AdvancedRoomOptions,
        stringProvider: StringProvider,
        homeServerName: String,
        enabled: Boolean,
        listener: AdvancedRoomOptionsListener?,
) {
    formAdvancedToggleItem {
        id("showAdvanced")
        title(stringProvider.getString(if (options.showAdvanced) CommonStrings.hide_advanced else CommonStrings.show_advanced))
        expanded(!options.showAdvanced)
        listener { listener?.toggleShowAdvanced() }
    }

    if (!options.showAdvanced) return

    formSwitchItem {
        id("federation")
        enabled(enabled)
        title(stringProvider.getString(CommonStrings.create_room_disable_federation_title, homeServerName))
        summary(stringProvider.getString(CommonStrings.create_room_disable_federation_description))
        switchChecked(options.disableFederation)
        listener { value -> listener?.setDisableFederation(value) }
    }

    if (options.availableRoomVersions.size >= 2) {
        buildProfileAction(
                id = "roomVersion",
                title = stringProvider.getString(CommonStrings.create_room_version_title),
                subtitle = options.selectedRoomVersionLabel(stringProvider),
                divider = false,
                editable = true,
                action = { if (enabled) listener?.selectRoomVersion() }
        )
    }

    if (options.canOverrideOwnPowerLevel) {
        buildProfileAction(
                id = "powerLevel",
                title = stringProvider.getString(CommonStrings.create_room_power_level_title),
                subtitle = options.myPowerLevelOverride?.toString(),
                divider = false,
                editable = true,
                action = { if (enabled) listener?.selectMyPowerLevel() }
        )
    }

    if (options.isDeveloperMode) {
        buildProfileAction(
                id = "initialState",
                title = stringProvider.getString(CommonStrings.create_room_initial_state_title),
                subtitle = if (options.initialStateJsonInvalid) {
                    stringProvider.getString(CommonStrings.create_room_initial_state_invalid)
                } else {
                    options.initialStateJson.takeIf { it.isNotBlank() }
                },
                destructive = options.initialStateJsonInvalid,
                divider = false,
                editable = true,
                action = { if (enabled) listener?.editInitialState() }
        )
    }
}

private fun AdvancedRoomOptions.selectedRoomVersionLabel(stringProvider: StringProvider): String? {
    val selected = roomVersion ?: defaultRoomVersion ?: return null
    return if (availableRoomVersions.any { it.version == selected && !it.stable }) {
        stringProvider.getString(CommonStrings.create_room_version_unstable, selected)
    } else {
        selected
    }
}

fun Fragment.showRoomVersionDialog(options: AdvancedRoomOptions, onSelected: (String) -> Unit) {
    val versions = options.availableRoomVersions
    val labels = versions.map {
        if (it.stable) it.version else getString(CommonStrings.create_room_version_unstable, it.version)
    }
    val checked = versions.indexOfFirst { it.version == (options.roomVersion ?: options.defaultRoomVersion) }.coerceAtLeast(0)
    MaterialAlertDialogBuilder(requireContext())
            .setTitle(CommonStrings.create_room_version_title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                onSelected(versions[which].version)
                dialog.dismiss()
            }
            .setNegativeButton(CommonStrings.action_cancel, null)
            .show()
}

fun Fragment.showMyPowerLevelDialog(options: AdvancedRoomOptions, onSelected: (Int?) -> Unit) {
    val layout = layoutInflater.inflate(R.layout.dialog_base_edit_text, null)
    val views = DialogBaseEditTextBinding.bind(layout)
    views.editText.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
    views.editText.hint = getString(CommonStrings.create_room_power_level_hint)
    views.editText.setText(options.myPowerLevelOverride?.toString().orEmpty())
    MaterialAlertDialogBuilder(requireContext())
            .setTitle(CommonStrings.create_room_power_level_title)
            .setView(layout)
            .setPositiveButton(CommonStrings.ok) { _, _ ->
                onSelected(views.editText.text?.toString()?.trim()?.toIntOrNull())
            }
            .setNegativeButton(CommonStrings.action_cancel, null)
            .show()
}

fun Fragment.showInitialStateDialog(options: AdvancedRoomOptions, onEdited: (String) -> Unit) {
    val layout = layoutInflater.inflate(R.layout.dialog_base_edit_text, null)
    val views = DialogBaseEditTextBinding.bind(layout)
    views.editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    views.editText.gravity = Gravity.TOP or Gravity.START
    views.editText.setLines(10)
    views.editText.isVerticalScrollBarEnabled = true
    views.editText.hint = getString(CommonStrings.create_room_initial_state_hint)
    views.editText.setText(options.initialStateJson)
    MaterialAlertDialogBuilder(requireContext())
            .setTitle(CommonStrings.create_room_initial_state_title)
            .setView(layout)
            .setPositiveButton(CommonStrings.ok) { _, _ ->
                onEdited(views.editText.text?.toString().orEmpty())
            }
            .setNegativeButton(CommonStrings.action_cancel, null)
            .show()
}
