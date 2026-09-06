/*
 * Copyright 2023, 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.notifications.defaults

import android.os.Bundle
import androidx.preference.Preference
import com.airbnb.mvrx.withState
import im.vector.app.R
import im.vector.app.core.preference.VectorListPreference
import im.vector.app.core.preference.VectorPreferenceCategory
import im.vector.app.features.settings.notifications.VectorSettingsPushRuleNotificationFragment
import im.vector.app.features.settings.notifications.VectorSettingsPushRuleNotificationViewAction
import im.vector.app.features.settings.notifications.VectorSettingsPushRuleNotificationViewState
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.pushrules.RuleIds

class VectorSettingsDefaultNotificationFragment :
        VectorSettingsPushRuleNotificationFragment() {

    override var titleRes: Int = CommonStrings.settings_notification_default

    override val preferenceXmlRes = R.xml.vector_settings_notification_default

    override val prefKeyToPushRuleId = mapOf(
            "SETTINGS_PUSH_RULE_INVITED_TO_ROOM_PREFERENCE_KEY" to RuleIds.RULE_ID_INVITE_ME,
            "SETTINGS_PUSH_RULE_MESSAGES_SENT_BY_BOT_PREFERENCE_KEY" to RuleIds.RULE_ID_SUPPRESS_BOTS_NOTIFICATIONS,
            "SETTINGS_PUSH_RULE_ROOMS_UPGRADED_KEY" to RuleIds.RULE_ID_TOMBSTONE
    )

    private val defaultPreferenceRules = mapOf(
            "SETTINGS_PUSH_RULE_DIRECT_MESSAGES_PREFERENCE_KEY" to listOf(
                    RuleIds.RULE_ID_ONE_TO_ONE_ROOM,
                    RuleIds.RULE_ID_ONE_TO_ONE_ENCRYPTED_ROOM
            ),
            "SETTINGS_PUSH_RULE_GROUP_MESSAGES_PREFERENCE_KEY" to listOf(
                    RuleIds.RULE_ID_ALL_OTHER_MESSAGES_ROOMS,
                    RuleIds.RULE_ID_ENCRYPTED
            )
    )

    override fun bindPref() {
        super.bindPref()
        findPreference<VectorPreferenceCategory>("SETTINGS_DEFAULT")!!.isIconSpaceReserved = false
        findPreference<VectorPreferenceCategory>("SETTINGS_OTHER")!!.isIconSpaceReserved = false
        defaultPreferenceRules.forEach { (preferenceKey, ruleIds) ->
            findPreference<VectorListPreference>(preferenceKey)?.apply {
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                    viewModel.handle(
                            VectorSettingsPushRuleNotificationViewAction.UpdatePushRules(
                                    ruleIds,
                                    newValue == DEFAULT_LEVEL_ALL_MESSAGES
                            )
                    )
                    false
                }
            }
        }
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.onEach(VectorSettingsPushRuleNotificationViewState::allRules) {
            refreshDefaultPreferences()
        }
    }

    override fun onPushRulesUpdated(ruleIds: List<String>, checked: Boolean, failure: Throwable?) {
        refreshDefaultPreferences()
        updateDefaultError(ruleIds, failure != null)
    }

    override fun onFailure(ruleId: String) {
        super.onFailure(ruleId)
        refreshDefaultPreferences()
        defaultPreferenceRules.entries
                .firstOrNull { ruleId in it.value }
                ?.let { updateDefaultError(it.value, true) }
    }

    private fun refreshDefaultPreferences() {
        val knownRuleIds = withState(viewModel) { it.allRules.map { rule -> rule.ruleId }.toSet() }
        defaultPreferenceRules.forEach { (preferenceKey, ruleIds) ->
            findPreference<VectorListPreference>(preferenceKey)?.apply {
                isVisible = ruleIds.any { it in knownRuleIds }
                value = if (ruleIds.any(viewModel::isPushRuleChecked)) {
                    DEFAULT_LEVEL_ALL_MESSAGES
                } else {
                    DEFAULT_LEVEL_MENTIONS_ONLY
                }
            }
        }
    }

    private fun updateDefaultError(ruleIds: List<String>, hasError: Boolean) {
        defaultPreferenceRules.entries
                .firstOrNull { it.value == ruleIds }
                ?.let { (preferenceKey, _) ->
                    findPreference<VectorListPreference>(preferenceKey)?.apply {
                        if (hasError) {
                            setSummary(CommonStrings.settings_notification_error_on_update)
                        } else {
                            summary = null
                        }
                    }
                }
    }

    private companion object {
        const val DEFAULT_LEVEL_ALL_MESSAGES = "all_messages"
        const val DEFAULT_LEVEL_MENTIONS_ONLY = "mentions_only"
    }
}
