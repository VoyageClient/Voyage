/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import com.airbnb.mvrx.test.MavericksTestRule
import im.vector.app.test.testDispatcher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.send.UserDraft
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val A_ROOM_ID = "!room:example.org"
private const val AN_EVENT_ID = "\$event"
private const val TYPED = "half a message"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MessageComposerEditStashTest {

    @get:Rule
    val mavericksTestRule = MavericksTestRule(testDispatcher = testDispatcher)

    private val timelineEvent = mockk<TimelineEvent>(relaxed = true) {
        every { root.eventId } returns AN_EVENT_ID
    }

    // getRoom()/getTimelineEvent() are extensions over these services, so they are what a test stubs.
    private var storedDrafts: List<UserDraft> = emptyList()
    private val room = mockk<Room>(relaxed = true) {
        every { timelineService().getTimelineEvent(any()) } returns timelineEvent
        every { draftService().getDrafts() } answers { storedDrafts }
        every { draftService().getDraft() } answers { storedDrafts.lastOrNull() }
        coEvery { draftService().saveDrafts(any()) } answers { storedDrafts = firstArg() }
        coEvery { draftService().saveDraft(any()) } answers { storedDrafts = listOf(firstArg()) }
        coEvery { draftService().deleteDraft() } answers { storedDrafts = emptyList() }
    }
    private val session = mockk<Session>(relaxed = true) {
        every { roomService().getRoom(A_ROOM_ID) } returns room
    }

    private fun createViewModel() = MessageComposerViewModel(
            initialState = MessageComposerViewState(roomId = A_ROOM_ID),
            session = session,
            stringProvider = mockk(relaxed = true),
            vectorPreferences = mockk(relaxed = true),
            commandParser = mockk(relaxed = true),
            rainbowGenerator = mockk(relaxed = true),
            audioMessageHelper = mockk(relaxed = true),
            clock = mockk(relaxed = true),
            pgpKeyStore = mockk(relaxed = true),
            pgpRoomEncryptor = mockk(relaxed = true),
            pgpDecryptor = mockk(relaxed = true),
            emoteShortcodeProcessor = mockk(relaxed = true),
            downloadMediaUseCase = mockk(relaxed = true),
    )

    private fun MessageComposerViewModel.type(text: String) = handle(MessageComposerAction.OnTextChanged(text))

    private fun MessageComposerViewModel.sendModeText() = com.airbnb.mvrx.withState(this) { state ->
        when (val mode = state.sendMode) {
            is SendMode.Regular -> mode.text
            is SendMode.Edit -> mode.text
            is SendMode.Reply -> mode.text
            is SendMode.Quote -> mode.text
            is SendMode.Voice -> mode.text
        }.toString()
    }

    @Test
    fun `cancelling an edit gives back what was being typed`() {
        val viewModel = createViewModel()
        viewModel.type(TYPED)

        viewModel.handle(MessageComposerAction.EnterEditMode(AN_EVENT_ID))
        // The composer now holds the edited message rather than what was typed.
        viewModel.type("the edited message")
        viewModel.handle(MessageComposerAction.EnterRegularMode(fromSharing = false))

        viewModel.sendModeText() shouldBeEqualTo TYPED
    }

    @Test
    fun `replying from an edit gives back what was being typed`() {
        val viewModel = createViewModel()
        viewModel.type(TYPED)

        viewModel.handle(MessageComposerAction.EnterEditMode(AN_EVENT_ID))
        viewModel.type("the edited message")
        viewModel.handle(MessageComposerAction.EnterReplyMode(AN_EVENT_ID))

        viewModel.sendModeText() shouldBeEqualTo TYPED
    }

    @Test
    fun `an edit started with an empty composer leaves it empty`() {
        val viewModel = createViewModel()

        viewModel.handle(MessageComposerAction.EnterEditMode(AN_EVENT_ID))
        viewModel.type("the edited message")
        viewModel.handle(MessageComposerAction.EnterRegularMode(fromSharing = false))

        // Nothing was stashed, so there is nothing to give back — least of all the message being edited.
        viewModel.sendModeText() shouldBeEqualTo ""
    }

    @Test
    fun `moving from one edit to another still gives back the original text`() {
        val viewModel = createViewModel()
        viewModel.type(TYPED)

        viewModel.handle(MessageComposerAction.EnterEditMode(AN_EVENT_ID))
        viewModel.type("the first edit")
        viewModel.handle(MessageComposerAction.EnterEditMode("\$other"))
        viewModel.type("the second edit")
        viewModel.handle(MessageComposerAction.EnterRegularMode(fromSharing = false))

        viewModel.sendModeText() shouldBeEqualTo TYPED
    }

    @Test
    fun `an edit left behind in the room comes back with the message it interrupted`() {
        storedDrafts = listOf(UserDraft.Regular(TYPED), UserDraft.Edit(AN_EVENT_ID, "the edited message"))

        // Reopening the room restores the edit...
        val viewModel = createViewModel()
        viewModel.sendModeText() shouldBeEqualTo "the edited message"

        // ...and cancelling it still gives back what was being written before it.
        viewModel.handle(MessageComposerAction.EnterRegularMode(fromSharing = false))

        viewModel.sendModeText() shouldBeEqualTo TYPED
    }

    @Test
    fun `the message an edit interrupted is stored underneath it`() {
        val viewModel = createViewModel()
        viewModel.type(TYPED)
        viewModel.handle(MessageComposerAction.EnterEditMode(AN_EVENT_ID))
        viewModel.type("the edited message")

        viewModel.handle(MessageComposerAction.OnEntersBackground("the edited message"))

        storedDrafts shouldBeEqualTo listOf(
                UserDraft.Regular(TYPED),
                UserDraft.Edit(AN_EVENT_ID, "the edited message")
        )
    }

    @Test
    fun `text typed after an edit ended is not stashed again`() {
        val viewModel = createViewModel()
        viewModel.type(TYPED)

        viewModel.handle(MessageComposerAction.EnterEditMode(AN_EVENT_ID))
        viewModel.handle(MessageComposerAction.EnterRegularMode(fromSharing = false))
        viewModel.type("something else")
        viewModel.handle(MessageComposerAction.EnterRegularMode(fromSharing = false))

        viewModel.sendModeText() shouldBeEqualTo "something else"
    }
}
