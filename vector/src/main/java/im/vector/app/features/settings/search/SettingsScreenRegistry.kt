/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.search

import im.vector.app.R
import im.vector.app.features.settings.VectorSettingsActivityPresenceFragment
import im.vector.app.features.settings.VectorSettingsAdvancedSettingsFragment
import im.vector.app.features.settings.VectorSettingsAppearanceFragment
import im.vector.app.features.settings.VectorSettingsBubbleAppearanceFragment
import im.vector.app.features.settings.VectorSettingsComposerFragment
import im.vector.app.features.settings.VectorSettingsEmojiFragment
import im.vector.app.features.settings.VectorSettingsGeneralFragment
import im.vector.app.features.settings.VectorSettingsHelpAboutFragment
import im.vector.app.features.settings.VectorSettingsPreferencesFragment
import im.vector.app.features.settings.VectorSettingsRoomsSpacesFragment
import im.vector.app.features.settings.VectorSettingsRootFragment
import im.vector.app.features.settings.VectorSettingsSecurityPrivacyFragment
import im.vector.app.features.settings.VectorSettingsTimelineFragment
import im.vector.app.features.settings.VectorSettingsTranslationFragment
import im.vector.app.features.settings.labs.VectorSettingsLabsFragment
import im.vector.app.features.settings.notifications.VectorSettingsNotificationFragment
import im.vector.app.features.settings.notifications.defaults.VectorSettingsDefaultNotificationFragment
import im.vector.app.features.settings.notifications.keywordandmentions.VectorSettingsKeywordAndMentionsNotificationFragment
import im.vector.app.features.settings.useragent.VectorSettingsUserAgentFragment
import im.vector.app.features.settings.vpn.VectorSettingsVpnExclusionsFragment

/** The settings screens whose rows are searchable, by fragment class name. */
object SettingsScreenRegistry {

    val ROOT: String = VectorSettingsRootFragment::class.java.name

    private val screens: Map<String, Int> = mapOf(
            ROOT to R.xml.vector_settings_root,
            VectorSettingsGeneralFragment::class.java.name to R.xml.vector_settings_general,
            VectorSettingsNotificationFragment::class.java.name to R.xml.vector_settings_notifications,
            VectorSettingsDefaultNotificationFragment::class.java.name to R.xml.vector_settings_notification_default,
            VectorSettingsKeywordAndMentionsNotificationFragment::class.java.name to R.xml.vector_settings_notification_mentions_and_keywords,
            VectorSettingsPreferencesFragment::class.java.name to R.xml.vector_settings_preferences,
            VectorSettingsAppearanceFragment::class.java.name to R.xml.vector_settings_appearance,
            VectorSettingsBubbleAppearanceFragment::class.java.name to R.xml.vector_settings_bubble_appearance,
            VectorSettingsEmojiFragment::class.java.name to R.xml.vector_settings_emoji,
            VectorSettingsComposerFragment::class.java.name to R.xml.vector_settings_composer,
            VectorSettingsTimelineFragment::class.java.name to R.xml.vector_settings_timeline,
            VectorSettingsActivityPresenceFragment::class.java.name to R.xml.vector_settings_activity_presence,
            VectorSettingsRoomsSpacesFragment::class.java.name to R.xml.vector_settings_rooms_spaces,
            VectorSettingsTranslationFragment::class.java.name to R.xml.vector_settings_translation,
            VectorSettingsSecurityPrivacyFragment::class.java.name to R.xml.vector_settings_security_privacy,
            VectorSettingsUserAgentFragment::class.java.name to R.xml.vector_settings_user_agent,
            VectorSettingsLabsFragment::class.java.name to R.xml.vector_settings_labs,
            VectorSettingsAdvancedSettingsFragment::class.java.name to R.xml.vector_settings_advanced_settings,
            VectorSettingsHelpAboutFragment::class.java.name to R.xml.vector_settings_help_about,
    )

    /**
     * Rows that open their screen from a click listener instead of `app:fragment`, by preference key.
     * The PIN screen is deliberately absent: Security only opens it after PIN authentication.
     */
    val codeLinks: Map<String, String> = mapOf(
            "SETTINGS_VPN_EXCLUSIONS_KEY" to VectorSettingsVpnExclusionsFragment::class.java.name,
            "SETTINGS_USER_AGENT_KEY" to VectorSettingsUserAgentFragment::class.java.name,
    )

    val registeredScreens: Set<String> get() = screens.keys

    fun xmlFor(fragmentClassName: String): Int? = screens[fragmentClassName]
}
