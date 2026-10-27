/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.search

import android.app.Activity
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.features.VectorFeatures
import im.vector.app.features.home.NightlyProxy
import im.vector.app.features.settings.VectorPreferences
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotContain
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilities
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SettingsSearchIndexTest {

    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val vectorPreferences = mockk<VectorPreferences>(relaxed = true)
    private val vectorFeatures = mockk<VectorFeatures>(relaxed = true)
    private val nightlyProxy = mockk<NightlyProxy>(relaxed = true)
    private val activeSessionHolder = mockk<ActiveSessionHolder> {
        every { getSafeActiveSession() } returns null
    }

    private fun build() = SettingsSearchIndex(vectorPreferences, vectorFeatures, nightlyProxy, activeSessionHolder).build(activity)

    @Test
    fun `every row of the former Preferences screen is indexed once under a Preferences sub-screen`() {
        val entries = build()

        FORMER_PREFERENCES_KEYS.forEach { key ->
            val matches = entries.filter { it.highlight?.key == key }
            matches.size shouldBeEqualTo 1
            matches.single().breadcrumb.first() shouldBeEqualTo activity.getString(im.vector.lib.strings.R.string.settings_preferences)
        }
    }

    @Test
    fun `skips rows hidden in XML`() {
        val keys = build().mapNotNull { it.highlight?.key }

        keys shouldNotContain "SETTINGS_MEDIA_SAVING_PERIOD_KEY"
        keys shouldNotContain "SETTINGS_12_24_TIMESTAMPS_KEY"
        keys shouldNotContain "SETTINGS_PIN_UNREAD_MESSAGES_PREFERENCE_KEY"
        keys shouldNotContain "SETTINGS_ALWAYS_SHOW_TIMESTAMPS_KEY"
    }

    @Test
    fun `skips rows hidden at runtime by local state`() {
        every { vectorPreferences.isNewAppLayoutEnabled() } returns true

        build().mapNotNull { it.highlight?.key } shouldNotContain VectorPreferences.SETTINGS_PREF_SPACE_SHOW_ALL_ROOM_IN_HOME
    }

    @Test
    fun `skips rows the homeserver capabilities hide`() {
        val session = mockk<Session> {
            every { homeServerCapabilitiesService().getHomeServerCapabilities() } returns HomeServerCapabilities(
                    canChangePassword = false,
                    externalAccountManagementUrl = null,
            )
        }
        every { activeSessionHolder.getSafeActiveSession() } returns session

        val keys = build().mapNotNull { it.highlight?.key }

        keys shouldNotContain VectorPreferences.SETTINGS_CHANGE_PASSWORD_PREFERENCE_KEY
        keys shouldNotContain VectorPreferences.SETTINGS_EXTERNAL_ACCOUNT_MANAGEMENT_KEY
    }

    @Test
    fun `drops summaries that are runtime templates`() {
        build().none { it.summary?.contains("%1\$s") == true }.shouldBeTrue()
    }

    @Test
    fun `reaches every registered screen from the root`() {
        val reached = build().flatMap { it.screenPath }.toSet() + SettingsScreenRegistry.ROOT

        SettingsScreenRegistry.registeredScreens.forEach { reached shouldContain it }
    }

    @Test
    fun `a row that opens a screen leads to it instead of highlighting itself`() {
        val appearance = build().single { it.title == activity.getString(im.vector.lib.strings.R.string.settings_appearance_title) }

        (appearance.highlight == null).shouldBeTrue()
        appearance.screenPath.last() shouldBeEqualTo im.vector.app.features.settings.VectorSettingsAppearanceFragment::class.java.name
    }

    @Test
    fun `never leads into the PIN screen`() {
        build().any { entry -> entry.screenPath.any { it.endsWith("VectorSettingsPinFragment") } }.shouldBeFalse()
    }

    companion object {
        private val FORMER_PREFERENCES_KEYS = listOf(
                "APPLICATION_THEME_KEY",
                "SETTINGS_APP_LOGO_KEY",
                "SETTINGS_SC_ACCENT_LIGHT",
                "SETTINGS_APP_ICON_COLOR_KEY",
                "SETTINGS_INTERFACE_TEXT_SIZE_KEY",
                "BUBBLE_STYLE_KEY",
                "SETTINGS_SCROLL_ANIMATION_KEY",
                "SETTINGS_AVATAR_SHAPE_KEY",
                "SETTINGS_PEOPLE_COLOR_PALETTE_KEY",
                "SETTINGS_ROOM_COLOR_PALETTE_KEY",
                "SETTINGS_CODE_PALETTE_KEY",
                "SETTINGS_PEOPLE_AVATAR_STYLE_KEY",
                "SETTINGS_ROOM_AVATAR_STYLE_KEY",
                "SETTINGS_SHOW_OTHERS_PROFILE_COLORS_KEY",
                "SETTINGS_PERFORMANCE_MODE_KEY",
                "SETTINGS_USE_TWEMOJI_KEY",
                "SETTINGS_USE_SYSTEM_EMOJI_FONT_KEY",
                "SETTINGS_CUSTOM_EMOJI_FONT_KEY",
                "SETTINGS_QUICK_REACTIONS_KEY",
                "SETTINGS_PREF_SPACE_SHOW_ALL_ROOM_IN_HOME",
                "SETTINGS_ENABLE_MARKDOWN_KEY",
                "SETTINGS_PREVENT_UNINTENDED_MARKDOWN_KEY",
                "SETTINGS_LINKIFY_MSC_REFERENCES_KEY",
                "SETTINGS_ENABLE_SED_REPLACEMENT_KEY",
                "SETTINGS_SEND_READ_RECEIPT_KEY",
                "SETTINGS_SEND_TYPING_NOTIF_KEY",
                "SETTINGS_SEND_MESSAGE_WITH_ENTER",
                "SETTINGS_SHOW_EMOJI_KEYBOARD",
                "SETTINGS_CLASSIC_COMPOSER",
                "SETTINGS_INTERFACE_TAKE_PHOTO_VIDEO",
                "SETTINGS_SHOW_URL_PREVIEW_KEY",
                "SETTINGS_SHOW_PINNED_MESSAGES_BANNER_KEY",
                "SETTINGS_OVERRIDE_DM_DISPLAY_KEY",
                "SETTINGS_COMPACT_QUICK_REACTIONS_KEY",
                "SETTINGS_SHOW_MESSAGE_TIMESTAMPS_KEY",
                "SETTINGS_TIMELINE_LINE_WRAP_KEY",
                "SETTINGS_SHOW_READ_RECEIPTS_KEY",
                "SETTINGS_AUTO_DISMISS_JUMP_TO_UNREAD_KEY",
                "SETTINGS_SHOW_REDACTED_KEY",
                "SETTINGS_SKIP_REDACTION_CONFIRMATION_KEY",
                "SETTINGS_SHOW_JOIN_LEAVE_MESSAGES_KEY",
                "SETTINGS_SHOW_AVATAR_DISPLAY_NAME_CHANGES_MESSAGES_KEY",
                "SETTINGS_SHOW_ROOM_SETTINGS_EVENTS_KEY",
                "SETTINGS_SHOW_ACL_EVENTS_KEY",
                "SETTINGS_SHOW_REACTIONS_KEY",
                "SETTINGS_AUTOPLAY_ANIMATED_IMAGES",
                "SETTINGS_LOOP_VIDEOS_KEY",
                "SETTINGS_ENABLE_DIRECT_SHARE",
                "SETTINGS_ENABLE_APP_SHORTCUTS",
                "SETTINGS_ENABLE_EMOJI_AUTOCOMPLETE",
                "SETTINGS_ENABLE_VOICE_MESSAGE_BUTTON",
                "SETTINGS_RENDER_BLOCKQUOTES_AS_GREENTEXT",
                "SETTINGS_JUMP_TO_BOTTOM_ON_SEND",
                "SETTINGS_JUMP_BACK_TO_REPLY_SOURCE",
                "SETTINGS_SPACE_MEMBERS_IN_SPACE_ROOMS",
                "SETTINGS_SINGLE_OVERVIEW",
                "SETTINGS_ROOM_DIRECTORY_SHOW_ALL_PUBLIC_ROOMS",
        )
    }
}
