/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.redaction.preservation

import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.test.fakes.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBe
import org.junit.Test
import org.matrix.android.sdk.api.session.Session

private const val A_ROOM_ID = "!room:example.org"
private const val ANOTHER_ROOM_ID = "!other:example.org"
private const val AN_EVENT_ID = "\$event1"
private const val ANOTHER_EVENT_ID = "\$event2"
private const val A_USER_ID = "@alice:example.org"

class RedactedContentRevealManagerTest {

    private val preferences = InMemorySharedPreferences()
    private val settings = mockk<RedactionPreservationSettings>()
    private val repository = mockk<RedactedContentRepository>(relaxed = true)

    private val session = mockk<Session> { every { myUserId } returns A_USER_ID }
    private val activeSessionHolder = mockk<ActiveSessionHolder> { every { getSafeActiveSession() } returns session }

    private val manager = RedactedContentRevealManager(
            preferences = preferences,
            activeSessionHolder = { activeSessionHolder },
            settings = settings,
            repository = repository,
    )

    private fun setPreserve(roomId: String, preserve: Boolean) {
        every { settings.preserveRedactedFor(roomId) } returns preserve
    }

    @Test
    fun `given preservation off, then nothing is revealed by default`() {
        setPreserve(A_ROOM_ID, false)

        manager.isRevealed(A_ROOM_ID, AN_EVENT_ID, isOwnMessage = false) shouldBe false
    }

    @Test
    fun `given preservation on, then everything is revealed by default`() {
        setPreserve(A_ROOM_ID, true)

        manager.isRevealed(A_ROOM_ID, AN_EVENT_ID, isOwnMessage = false) shouldBe true
    }

    @Test
    fun `given an explicit reveal with preservation off, then it is revealed`() {
        setPreserve(A_ROOM_ID, false)

        manager.setRevealed(AN_EVENT_ID, true)

        manager.isRevealed(A_ROOM_ID, AN_EVENT_ID, isOwnMessage = false) shouldBe true
    }

    @Test
    fun `given an explicit hide with preservation on, then it stays hidden`() {
        setPreserve(A_ROOM_ID, true)

        manager.setRevealed(AN_EVENT_ID, false)

        manager.isRevealed(A_ROOM_ID, AN_EVENT_ID, isOwnMessage = false) shouldBe false
        // Unrelated events still follow the room default.
        manager.isRevealed(A_ROOM_ID, ANOTHER_EVENT_ID, isOwnMessage = false) shouldBe true
    }

    /** An explicit choice is absolute, not a delta from the room's current setting. */
    @Test
    fun `given an explicit hide, when the room setting changes, then it is still hidden`() {
        setPreserve(A_ROOM_ID, true)
        manager.setRevealed(AN_EVENT_ID, false)

        setPreserve(A_ROOM_ID, false)

        manager.isRevealed(A_ROOM_ID, AN_EVENT_ID, isOwnMessage = false) shouldBe false
    }

    @Test
    fun `given an explicit reveal, when the room setting changes, then it is still revealed`() {
        setPreserve(A_ROOM_ID, false)
        manager.setRevealed(AN_EVENT_ID, true)

        setPreserve(A_ROOM_ID, true)

        manager.isRevealed(A_ROOM_ID, AN_EVENT_ID, isOwnMessage = false) shouldBe true
    }

    @Test
    fun `given a reveal then a hide, then the later choice wins`() {
        setPreserve(A_ROOM_ID, false)

        manager.setRevealed(AN_EVENT_ID, true)
        manager.setRevealed(AN_EVENT_ID, false)

        manager.isRevealed(A_ROOM_ID, AN_EVENT_ID, isOwnMessage = false) shouldBe false
    }

    @Test
    fun `given explicit choices, when cleared, then room defaults apply again`() {
        setPreserve(A_ROOM_ID, true)
        manager.setRevealed(AN_EVENT_ID, false)

        manager.clearExplicitChoices()

        manager.isRevealed(A_ROOM_ID, AN_EVENT_ID, isOwnMessage = false) shouldBe true
    }

    @Test
    fun `given an explicit choice, then it survives a reload from storage`() {
        setPreserve(A_ROOM_ID, false)
        manager.setRevealed(AN_EVENT_ID, true)

        val reloaded = RedactedContentRevealManager(
                preferences = preferences,
                activeSessionHolder = { activeSessionHolder },
                settings = settings,
                repository = repository,
        )

        reloaded.isRevealed(A_ROOM_ID, AN_EVENT_ID, isOwnMessage = false) shouldBe true
    }

    @Test
    fun `given a per-room override, then each room uses its own default`() {
        setPreserve(A_ROOM_ID, true)
        setPreserve(ANOTHER_ROOM_ID, false)

        manager.isRevealed(A_ROOM_ID, AN_EVENT_ID, isOwnMessage = false) shouldBe true
        manager.isRevealed(ANOTHER_ROOM_ID, AN_EVENT_ID, isOwnMessage = false) shouldBe false
    }

    /** Deleting your own message is deliberate, so a room-wide reveal must not undo it. */
    @Test
    fun `given own message, then the room default does not reveal it`() {
        setPreserve(A_ROOM_ID, true)

        manager.isRevealed(A_ROOM_ID, AN_EVENT_ID, isOwnMessage = true) shouldBe false
        manager.isRevealed(A_ROOM_ID, AN_EVENT_ID, isOwnMessage = false) shouldBe true
    }

    @Test
    fun `given own message, then an explicit reveal still works`() {
        setPreserve(A_ROOM_ID, true)

        manager.setRevealed(AN_EVENT_ID, true)

        manager.isRevealed(A_ROOM_ID, AN_EVENT_ID, isOwnMessage = true) shouldBe true
    }

    @Test
    fun `given own message explicitly hidden, then it stays hidden`() {
        setPreserve(A_ROOM_ID, false)

        manager.setRevealed(AN_EVENT_ID, false)

        manager.isRevealed(A_ROOM_ID, AN_EVENT_ID, isOwnMessage = true) shouldBe false
    }

    @Test
    fun `given no active session, then nothing is revealed`() {
        every { activeSessionHolder.getSafeActiveSession() } returns null

        manager.isRevealed(A_ROOM_ID, AN_EVENT_ID, isOwnMessage = false) shouldBe false
    }
}
