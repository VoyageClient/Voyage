/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import androidx.preference.PreferenceManager
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MessageSwipeBindingsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val storage = PreferenceManager.getDefaultSharedPreferences(context)
    private val preferences = VectorPreferences(context, mockk(relaxed = true), mockk(relaxed = true), storage)

    @Test
    fun `default bindings distinguish direction and distance`() {
        val bindings = preferences.messageSwipeBindings()
        bindings.resolve(-99f) shouldBeEqualTo MessageSwipeAction.NONE
        bindings.resolve(-100f) shouldBeEqualTo MessageSwipeAction.REPLY
        bindings.resolve(-179f) shouldBeEqualTo MessageSwipeAction.REPLY
        bindings.resolve(-180f) shouldBeEqualTo MessageSwipeAction.EDIT
        bindings.resolve(100f) shouldBeEqualTo MessageSwipeAction.NONE
        bindings.resolve(180f) shouldBeEqualTo MessageSwipeAction.NONE
    }

    @Test
    fun `unavailable farther action preserves the shorter action`() {
        val bindings = preferences.messageSwipeBindings()
        bindings.resolve(-200f) { it != MessageSwipeAction.EDIT } shouldBeEqualTo MessageSwipeAction.REPLY
        bindings.resolve(-200f) { false } shouldBeEqualTo MessageSwipeAction.NONE
    }

    @Test
    fun `the sole available action uses the regular threshold in either direction`() {
        listOf(MessageSwipeSlot.LEFT to MessageSwipeSlot.LEFT_FAR, MessageSwipeSlot.RIGHT to MessageSwipeSlot.RIGHT_FAR).forEach { (near, far) ->
            val direction = if (near == MessageSwipeSlot.LEFT) -1 else 1
            preferences.setMessageSwipeAction(near, MessageSwipeAction.REPLY)
            preferences.setMessageSwipeAction(far, MessageSwipeAction.PIN)
            val bindings = preferences.messageSwipeBindings()
            listOf(MessageSwipeAction.REPLY, MessageSwipeAction.PIN).forEach { available ->
                bindings.resolve(direction * 99f) { it == available } shouldBeEqualTo MessageSwipeAction.NONE
                listOf(100f, 179f, 180f, 240f).forEach { distance ->
                    bindings.resolve(direction * distance) { it == available } shouldBeEqualTo available
                    bindings.hint(direction * distance) { it == available } shouldBeEqualTo available
                }
            }
            bindings.resolve(direction * 100f) { false } shouldBeEqualTo MessageSwipeAction.NONE
            bindings.hint(direction * 100f) { false } shouldBeEqualTo MessageSwipeAction.NONE
        }
    }

    @Test
    fun `room overrides inherit each slot and remain isolated from other rooms and global bindings`() {
        val room = "!room:example.org"
        val otherRoom = "!other:example.org"
        MessageSwipeSlot.values().forEach { slot ->
            preferences.getRoomMessageSwipeActionOverride(room, slot) shouldBeEqualTo null
            preferences.messageSwipeBindings(room)[slot] shouldBeEqualTo preferences.messageSwipeBindings()[slot]
            preferences.setRoomMessageSwipeActionOverride(room, slot, MessageSwipeAction.REDACT)
            preferences.messageSwipeBindings(room)[slot] shouldBeEqualTo MessageSwipeAction.REDACT
            preferences.messageSwipeBindings(otherRoom)[slot] shouldBeEqualTo preferences.messageSwipeBindings()[slot]
            preferences.setMessageSwipeAction(slot, MessageSwipeAction.COPY)
            preferences.messageSwipeBindings(room)[slot] shouldBeEqualTo MessageSwipeAction.REDACT
            preferences.messageSwipeBindings(otherRoom)[slot] shouldBeEqualTo MessageSwipeAction.COPY
            preferences.setRoomMessageSwipeActionOverride(room, slot, null)
            preferences.messageSwipeBindings(room)[slot] shouldBeEqualTo MessageSwipeAction.COPY
        }
    }

    @Test
    fun `reset clears all room overrides including disabled farther actions`() {
        val room = "!room:example.org"
        val otherRoom = "!other:example.org"
        MessageSwipeSlot.values().forEach { slot ->
            preferences.setRoomMessageSwipeActionOverride(room, slot, MessageSwipeAction.NONE)
        }
        preferences.setRoomMessageSwipeActionOverride(room, MessageSwipeSlot.LEFT_FAR, MessageSwipeAction.PIN)
        preferences.setRoomMessageSwipeActionOverride(otherRoom, MessageSwipeSlot.LEFT, MessageSwipeAction.COPY)
        preferences.messageSwipeBindings(room).isEnabled(MessageSwipeSlot.LEFT_FAR) shouldBeEqualTo false
        preferences.resetRoomMessageSwipeActions(room)
        MessageSwipeSlot.values().forEach { slot ->
            preferences.getRoomMessageSwipeActionOverride(room, slot) shouldBeEqualTo null
        }
        preferences.messageSwipeBindings(room) shouldBeEqualTo preferences.messageSwipeBindings()
        preferences.getRoomMessageSwipeActionOverride(otherRoom, MessageSwipeSlot.LEFT) shouldBeEqualTo MessageSwipeAction.COPY
    }

    @Test
    fun `unbound farther swipes retain the regular action in both directions`() {
        listOf(MessageSwipeSlot.LEFT to MessageSwipeSlot.LEFT_FAR, MessageSwipeSlot.RIGHT to MessageSwipeSlot.RIGHT_FAR).forEach { (near, far) ->
            val direction = if (near == MessageSwipeSlot.LEFT) -1 else 1
            preferences.setMessageSwipeAction(near, MessageSwipeAction.REPLY)
            preferences.setMessageSwipeAction(far, MessageSwipeAction.NONE)
            val bindings = preferences.messageSwipeBindings()
            listOf(120f, 180f, 200f, 400f).forEach { distance ->
                bindings.resolve(direction * distance) shouldBeEqualTo MessageSwipeAction.REPLY
                bindings.hint(direction * distance) { true } shouldBeEqualTo MessageSwipeAction.REPLY
                bindings.resolve(direction * distance) { false } shouldBeEqualTo MessageSwipeAction.NONE
            }
        }
    }

    @Test
    fun `unbinding a direction disables its farther swipe and preserves the saved action`() {
        listOf(MessageSwipeSlot.LEFT to MessageSwipeSlot.LEFT_FAR, MessageSwipeSlot.RIGHT to MessageSwipeSlot.RIGHT_FAR).forEach { (near, far) ->
            val direction = if (near == MessageSwipeSlot.LEFT) -1 else 1
            preferences.setMessageSwipeAction(near, MessageSwipeAction.NONE)
            preferences.setMessageSwipeAction(far, MessageSwipeAction.REDACT)
            val bindings = preferences.messageSwipeBindings()
            bindings.isEnabled(near) shouldBeEqualTo true
            bindings.isEnabled(far) shouldBeEqualTo false
            bindings[far] shouldBeEqualTo MessageSwipeAction.REDACT
            bindings.resolve(direction * 120f) shouldBeEqualTo MessageSwipeAction.NONE
            bindings.hint(direction * 120f) { true } shouldBeEqualTo MessageSwipeAction.NONE
            bindings.resolve(direction * 180f) shouldBeEqualTo MessageSwipeAction.NONE

            preferences.setMessageSwipeAction(near, MessageSwipeAction.REPLY)
            preferences.messageSwipeBindings().isEnabled(far) shouldBeEqualTo true
            preferences.messageSwipeBindings().resolve(direction * 180f) shouldBeEqualTo MessageSwipeAction.REDACT
        }
    }

    @Test
    fun `every action can be stored independently in every slot`() {
        MessageSwipeSlot.values().forEach { slot ->
            MessageSwipeAction.values().forEach { action ->
                preferences.setMessageSwipeAction(slot, action)
                preferences.messageSwipeBindings()[slot] shouldBeEqualTo action
            }
        }
    }

    @Test
    fun `legacy disable preserves selection and explicit bindings override it`() {
        storage.edit().putBoolean("SETTINGS_LABS_ENABLE_SWIPE_TO_REPLY", false).apply()
        preferences.setMessageSwipeAction(MessageSwipeSlot.RIGHT, MessageSwipeAction.SELECT)
        val bindings = preferences.messageSwipeBindings()
        bindings.resolve(-100f) shouldBeEqualTo MessageSwipeAction.NONE
        bindings.resolve(-180f) shouldBeEqualTo MessageSwipeAction.NONE
        bindings.resolve(100f) shouldBeEqualTo MessageSwipeAction.SELECT
        preferences.setMessageSwipeAction(MessageSwipeSlot.LEFT, MessageSwipeAction.PIN)
        preferences.messageSwipeBindings().resolve(-100f) shouldBeEqualTo MessageSwipeAction.PIN
    }

    @Test
    fun `unrecognized stored action uses the default binding`() {
        storage.edit().putString(MessageSwipeSlot.RIGHT.preferenceKey, "unknown_action").apply()
        preferences.messageSwipeBindings()[MessageSwipeSlot.RIGHT] shouldBeEqualTo MessageSwipeAction.NONE
    }
}
