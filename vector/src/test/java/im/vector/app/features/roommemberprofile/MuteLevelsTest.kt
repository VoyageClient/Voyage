/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roommemberprofile

import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.session.delayedevents.PowerLevelRestore
import org.matrix.android.sdk.api.session.room.model.PowerLevelsContent

class MuteLevelsTest {

    private val alice = "@alice:example.org"

    @Test
    fun `the message event level wins over events_default`() {
        val content = PowerLevelsContent(eventsDefault = 0, events = mapOf("m.room.message" to 10))

        MuteLevels.mutedLevel(content) shouldBeEqualTo 9
    }

    @Test
    fun `without power levels a mute goes to -1`() {
        MuteLevels.mutedLevel(null) shouldBeEqualTo -1
        MuteLevels.isMuted(PowerLevelsContent(users = mapOf(alice to -1)), alice) shouldBe true
    }

    @Test
    fun `unmute gives back what a pending restore would have`() {
        val content = PowerLevelsContent(users = mapOf(alice to -1))

        MuteLevels.unmuteLevel(content, PowerLevelRestore("d", alice, 50, 0)) shouldBeEqualTo 50
        MuteLevels.unmuteLevel(content, PowerLevelRestore("d", alice, null, 0)) shouldBe null
    }

    @Test
    fun `unmute without a restore falls back to the room default when it can send`() {
        MuteLevels.unmuteLevel(PowerLevelsContent(usersDefault = 0, eventsDefault = 0), null) shouldBe null
        MuteLevels.unmuteLevel(PowerLevelsContent(usersDefault = 0, eventsDefault = 10), null) shouldBeEqualTo 10
    }

    @Test
    fun `a temporary mute gives back the level held before it`() {
        MuteLevels.levelToRestore(PowerLevelsContent(users = mapOf(alice to 50)), alice, null) shouldBeEqualTo 50
        MuteLevels.levelToRestore(PowerLevelsContent(), alice, null) shouldBe null
    }

    @Test
    fun `timing a mute already in place never restores the muted level`() {
        val muted = PowerLevelsContent(users = mapOf(alice to -1), usersDefault = 0)

        MuteLevels.levelToRestore(muted, alice, null) shouldBe null
        MuteLevels.levelToRestore(muted, alice, PowerLevelRestore("d", alice, 50, 0)) shouldBeEqualTo 50
    }
}
