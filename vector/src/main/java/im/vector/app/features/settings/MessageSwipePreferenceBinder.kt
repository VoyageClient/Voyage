/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import androidx.preference.PreferenceFragmentCompat
import im.vector.lib.strings.CommonStrings

class MessageSwipePreferenceBinder(
        private val host: PreferenceFragmentCompat,
        private val preferences: VectorPreferences,
        private val roomId: String? = null,
        private val onChanged: () -> Unit = {},
) {
    fun bind() {
        val actions = MessageSwipeAction.values().toList()
        val labels = (if (roomId == null) emptyList() else listOf(host.getString(CommonStrings.settings_use_account_setting))) +
                actions.map { host.getString(it.titleRes) }
        val values = (if (roomId == null) emptyList() else listOf(INHERIT)) + actions.map { it.name }
        MessageSwipeSlot.values().forEach { slot ->
            requireNotNull(host.findPreference<SwipeActionPreference>(slot.preferenceKey)).apply {
                entries = labels.toTypedArray()
                entryValues = values.toTypedArray()
                dialogTitle = host.getString(
                        CommonStrings.settings_swipe_binding_description,
                        host.getString(CommonStrings.settings_swipe_choose_action),
                        host.getString(slot.titleRes),
                )
                setOnPreferenceChangeListener { _, newValue ->
                    val action = actions.firstOrNull { it.name == newValue }
                    if (roomId == null) {
                        preferences.setMessageSwipeAction(slot, action ?: return@setOnPreferenceChangeListener false)
                    } else {
                        preferences.setRoomMessageSwipeActionOverride(roomId, slot, action)
                    }
                    refresh()
                    true
                }
            }
        }
        refresh()
    }

    fun refresh() {
        val bindings = preferences.messageSwipeBindings(roomId)
        MessageSwipeSlot.values().forEach { slot ->
            val overrideAction = roomId?.let { preferences.getRoomMessageSwipeActionOverride(it, slot) }
            requireNotNull(host.findPreference<SwipeActionPreference>(slot.preferenceKey)).apply {
                action = bindings[slot]
                isEnabled = bindings.isEnabled(slot)
                value = if (roomId == null) action.name else overrideAction?.name ?: INHERIT
                summary = if (roomId != null && overrideAction == null) {
                    host.getString(CommonStrings.settings_use_account_setting)
                } else null
            }
        }
        onChanged()
    }

    private companion object {
        const val INHERIT = "inherit"
    }
}
