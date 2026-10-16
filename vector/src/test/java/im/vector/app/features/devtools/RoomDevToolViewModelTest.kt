/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.devtools

import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.test.MavericksTestRule
import com.airbnb.mvrx.withState
import im.vector.app.test.fakes.FakeErrorFormatter
import im.vector.app.test.fakes.FakeStringProvider
import im.vector.app.test.testDispatcher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.Rule
import org.junit.Test
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.EventService
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.UnsignedData
import org.matrix.android.sdk.api.session.room.model.PowerLevelsContent
import org.matrix.android.sdk.api.session.room.powerlevels.RoomPowerLevels

private const val A_ROOM_ID = "!room:server"

class RoomDevToolViewModelTest {

    @get:Rule
    val mavericksTestRule = MavericksTestRule(testDispatcher = testDispatcher)

    private val eventService = mockk<EventService>()
    private val session = mockk<Session>(relaxed = true).also {
        every { it.roomService().getRoom(any()) } returns null
        every { it.eventService() } returns eventService
    }

    private fun stateEvent(id: String, replaces: String?, name: String = id) = Event(
            type = "m.room.name",
            eventId = id,
            stateKey = "",
            roomId = A_ROOM_ID,
            content = mapOf("name" to name),
            unsignedData = UnsignedData(age = null, replacesState = replaces)
    )

    private fun givenServerEvents(vararg events: Event) {
        events.forEach { event ->
            coEvery { eventService.getEvent(A_ROOM_ID, event.eventId!!) } returns event
        }
    }

    private fun createViewModel(current: Event) = RoomDevToolViewModel(
            RoomDevToolViewState(
                    roomId = A_ROOM_ID,
                    displayMode = RoomDevToolViewState.Mode.StateEventDetail,
                    selectedEvent = current
            ),
            FakeErrorFormatter(),
            FakeStringProvider().instance,
            session
    )

    private fun RoomDevToolViewModel.history() = withState(this) { state -> state.stateHistory.map { it.eventId } }

    @Test
    fun `walks the replaces_state chain to its start, newest first`() {
        val current = stateEvent("\$c", replaces = "\$b")
        givenServerEvents(stateEvent("\$b", replaces = "\$a"), stateEvent("\$a", replaces = null))
        val viewModel = createViewModel(current)

        viewModel.handle(RoomDevToolAction.ShowStateHistory)

        viewModel.history() shouldBeEqualTo listOf("\$c", "\$b", "\$a")
        withState(viewModel) {
            it.displayMode shouldBe RoomDevToolViewState.Mode.StateEventHistory
            it.stateHistoryLoad shouldBeInstanceOf Success::class
        }
    }

    @Test
    fun `fetches the server copy when the local current event lacks replaces_state`() {
        val current = stateEvent("\$c", replaces = null)
        givenServerEvents(stateEvent("\$c", replaces = "\$b"), stateEvent("\$b", replaces = null))
        val viewModel = createViewModel(current)

        viewModel.handle(RoomDevToolAction.ShowStateHistory)

        viewModel.history() shouldBeEqualTo listOf("\$c", "\$b")
    }

    @Test
    fun `stops at a replaces_state cycle`() {
        val current = stateEvent("\$c", replaces = "\$b")
        givenServerEvents(stateEvent("\$b", replaces = "\$c"))
        val viewModel = createViewModel(current)

        viewModel.handle(RoomDevToolAction.ShowStateHistory)

        viewModel.history() shouldBeEqualTo listOf("\$c", "\$b")
        withState(viewModel) { it.stateHistoryLoad shouldBeInstanceOf Success::class }
    }

    @Test
    fun `retry resumes after the last loaded revision`() {
        val current = stateEvent("\$c", replaces = "\$b")
        givenServerEvents(stateEvent("\$b", replaces = "\$a"))
        coEvery { eventService.getEvent(A_ROOM_ID, "\$a") } throws IllegalStateException("offline")
        val viewModel = createViewModel(current)

        viewModel.handle(RoomDevToolAction.ShowStateHistory)

        viewModel.history() shouldBeEqualTo listOf("\$c", "\$b")
        withState(viewModel) { it.stateHistoryLoad shouldBeInstanceOf Fail::class }

        givenServerEvents(stateEvent("\$a", replaces = null))
        viewModel.handle(RoomDevToolAction.RetryStateHistory)

        viewModel.history() shouldBeEqualTo listOf("\$c", "\$b", "\$a")
        coVerify(exactly = 1) { eventService.getEvent(A_ROOM_ID, "\$b") }
    }

    @Test
    fun `only current state the user may send is editable`() {
        val current = stateEvent("\$c", replaces = "\$b")
        val old = stateEvent("\$b", replaces = null)
        val state = RoomDevToolViewState(
                roomId = A_ROOM_ID,
                stateEvents = Success(listOf(current)),
                canEditState = true,
                myUserId = "@me:server",
                roomPowerLevels = RoomPowerLevels(PowerLevelsContent(users = mapOf("@me:server" to 50), stateDefault = 50), null),
                selectedEvent = current
        )

        state.canEditSelectedStateEvent() shouldBe true
        state.copy(selectedEvent = old).canEditSelectedStateEvent() shouldBe false
        state.copy(roomPowerLevels = RoomPowerLevels(PowerLevelsContent(stateDefault = 50), null)).canEditSelectedStateEvent() shouldBe false
        state.copy(selectedEvent = current.copy(stateKey = "@other:server")).canEditSelectedStateEvent() shouldBe false
    }

    @Test
    fun `back from a revision returns to the history, then to the current event`() {
        val current = stateEvent("\$c", replaces = "\$b", name = "new")
        val revision = stateEvent("\$b", replaces = null, name = "old")
        givenServerEvents(revision)
        val viewModel = createViewModel(current)
        viewModel.handle(RoomDevToolAction.ShowStateHistory)

        viewModel.handle(RoomDevToolAction.ShowStateRevision(revision))
        withState(viewModel) {
            it.displayMode shouldBe RoomDevToolViewState.Mode.StateEventDetail
            it.detailFromHistory shouldBe true
            it.selectedEvent?.eventId shouldBeEqualTo "\$b"
        }

        viewModel.handle(RoomDevToolAction.OnBackPressed)
        withState(viewModel) {
            it.displayMode shouldBe RoomDevToolViewState.Mode.StateEventHistory
            it.stateHistory.size shouldBeEqualTo 2
        }

        viewModel.handle(RoomDevToolAction.OnBackPressed)
        withState(viewModel) {
            it.displayMode shouldBe RoomDevToolViewState.Mode.StateEventDetail
            it.detailFromHistory shouldBe false
            it.selectedEvent?.eventId shouldBeEqualTo "\$c"
            it.historyOf shouldBe null
            it.stateHistory shouldBeEqualTo emptyList()
        }
    }
}
