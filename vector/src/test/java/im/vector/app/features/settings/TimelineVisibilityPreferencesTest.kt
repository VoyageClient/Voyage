/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import androidx.preference.PreferenceManager
import im.vector.app.ActiveSessionDataSource
import im.vector.app.core.resources.UserPreferencesProvider
import im.vector.app.features.home.room.detail.timeline.helper.TimelineDisplayableEvents
import im.vector.app.features.home.room.detail.timeline.helper.TimelineEventVisibilityHelper
import im.vector.app.features.settings.VectorPreferences.TimelineVisibilityRule
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private const val ROOM_ID = "!room:example.org"
private const val OTHER_ROOM_ID = "!other:example.org"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TimelineVisibilityPreferencesTest {

    private val context = RuntimeEnvironment.getApplication()
    private val storage = PreferenceManager.getDefaultSharedPreferences(context)
    private val preferences = VectorPreferences(
            context = context,
            clock = mockk(relaxed = true),
            buildMeta = mockk(relaxed = true),
            vectorFeatures = mockk(relaxed = true),
            defaultPrefs = storage,
            stringProvider = mockk(relaxed = true),
    )
    private val visibility = TimelineEventVisibilityHelper(
            UserPreferencesProvider(preferences),
            mockk(relaxed = true),
            ActiveSessionDataSource(),
    )

    @Test
    fun `room overrides are isolated and clearing them restores inheritance`() {
        storage.edit().putBoolean("SETTINGS_DEVELOPER_MODE_PREFERENCE_KEY", true).apply()
        TimelineVisibilityRule.values().forEach { rule ->
            storage.edit().putBoolean(rule.preferenceKey, true).apply()
        }
        TimelineVisibilityRule.values().forEach { rule ->
            preferences.getRoomTimelineVisibilityOverride(ROOM_ID, rule).shouldBeNull()
            preferences.timelineVisibility(rule, ROOM_ID) shouldBeEqualTo true
            preferences.setRoomTimelineVisibilityOverride(ROOM_ID, rule, false)
            preferences.timelineVisibility(rule, ROOM_ID) shouldBeEqualTo false
            preferences.timelineVisibility(rule, OTHER_ROOM_ID) shouldBeEqualTo true
            preferences.setRoomTimelineVisibilityOverride(ROOM_ID, rule, null)
            preferences.getRoomTimelineVisibilityOverride(ROOM_ID, rule).shouldBeNull()
            preferences.timelineVisibility(rule, ROOM_ID) shouldBeEqualTo true
        }
    }

    @Test
    fun `hidden thread visibility needs effective hidden events and developer mode`() {
        preferences.setRoomTimelineVisibilityOverride(ROOM_ID, TimelineVisibilityRule.HIDDEN_EVENTS, true)
        preferences.setRoomTimelineVisibilityOverride(ROOM_ID, TimelineVisibilityRule.HIDDEN_THREAD_EVENTS, true)
        preferences.shouldShowHiddenThreadEvents(ROOM_ID) shouldBeEqualTo false

        storage.edit().putBoolean("SETTINGS_DEVELOPER_MODE_PREFERENCE_KEY", true).apply()
        preferences.shouldShowHiddenThreadEvents(ROOM_ID) shouldBeEqualTo true
        preferences.shouldShowHiddenThreadEvents(OTHER_ROOM_ID) shouldBeEqualTo false

        preferences.setRoomTimelineVisibilityOverride(ROOM_ID, TimelineVisibilityRule.HIDDEN_EVENTS, null)
        preferences.shouldShowHiddenThreadEvents(ROOM_ID) shouldBeEqualTo false
        storage.edit().putBoolean(TimelineVisibilityRule.HIDDEN_EVENTS.preferenceKey, true).apply()
        preferences.shouldShowHiddenThreadEvents(ROOM_ID) shouldBeEqualTo true
        preferences.setRoomTimelineVisibilityOverride(ROOM_ID, TimelineVisibilityRule.HIDDEN_EVENTS, false)
        preferences.shouldShowHiddenThreadEvents(ROOM_ID) shouldBeEqualTo false
    }

    @Test
    fun `hidden events cannot bypass membership and room settings filters`() {
        storage.edit().putBoolean("SETTINGS_DEVELOPER_MODE_PREFERENCE_KEY", true).apply()
        preferences.setRoomTimelineVisibilityOverride(ROOM_ID, TimelineVisibilityRule.HIDDEN_EVENTS, true)
        preferences.setRoomTimelineVisibilityOverride(ROOM_ID, TimelineVisibilityRule.MEMBERSHIP, false)
        preferences.setRoomTimelineVisibilityOverride(ROOM_ID, TimelineVisibilityRule.ROOM_SETTINGS, false)

        listOf("join", "leave", "invite", "ban", "knock").forEach { membership ->
            val event = event(EventType.STATE_ROOM_MEMBER, mapOf("membership" to membership))
            isShown(event) shouldBeEqualTo false
            visibility.isHiddenEvent(event, null, false) shouldBeEqualTo false
        }
        val noChange = event(EventType.STATE_ROOM_MEMBER, mapOf("membership" to "join"), mapOf("membership" to "join"))
        isShown(noChange) shouldBeEqualTo false
        isShown(event(EventType.STATE_ROOM_THIRD_PARTY_INVITE)) shouldBeEqualTo false
        TimelineDisplayableEvents.ROOM_SETTINGS_TYPES.forEach { type ->
            val event = event(type)
            isShown(event) shouldBeEqualTo false
            visibility.isHiddenEvent(event, null, false) shouldBeEqualTo false
        }
    }

    @Test
    fun `profile changes have their own filter while no-change events use membership`() {
        preferences.setRoomTimelineVisibilityOverride(ROOM_ID, TimelineVisibilityRule.MEMBERSHIP, false)
        val previous = mapOf("membership" to "join", "displayname" to "Alice")
        val changed = event(EventType.STATE_ROOM_MEMBER, previous + ("displayname" to "Alicia"), previous)
        isShown(changed) shouldBeEqualTo true
        isShown(event(EventType.STATE_ROOM_MEMBER, previous, previous)) shouldBeEqualTo false

        preferences.setRoomTimelineVisibilityOverride(ROOM_ID, TimelineVisibilityRule.PROFILE_CHANGES, false)
        isShown(changed) shouldBeEqualTo false
    }

    @Test
    fun `direct navigation can reveal a filtered membership event`() {
        preferences.setRoomTimelineVisibilityOverride(ROOM_ID, TimelineVisibilityRule.MEMBERSHIP, false)
        val event = event(EventType.STATE_ROOM_MEMBER, mapOf("membership" to "invite"))
        isShown(event) shouldBeEqualTo false
        visibility.shouldShowEvent(event, event.eventId, false, null) shouldBeEqualTo true
    }

    private fun isShown(event: TimelineEvent) = visibility.shouldShowEvent(event, null, false, null)

    private fun event(type: String, content: Map<String, Any> = emptyMap(), previous: Map<String, Any>? = null): TimelineEvent {
        return TimelineEvent(
                root = Event(
                        type = type,
                        eventId = "event",
                        roomId = ROOM_ID,
                        stateKey = "@alice:example.org",
                        senderId = "@bob:example.org",
                        content = content,
                        prevContent = previous,
                ),
                localId = 1L,
                eventId = "event",
                senderInfo = mockk(relaxed = true),
        )
    }
}
