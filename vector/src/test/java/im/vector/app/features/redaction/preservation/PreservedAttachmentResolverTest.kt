/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.redaction.preservation

import im.vector.app.core.di.ActiveSessionHolder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.UnsignedData
import org.matrix.android.sdk.api.session.redaction.PreservationOrigin
import org.matrix.android.sdk.api.session.redaction.PreservedEventContent
import org.matrix.android.sdk.api.session.redaction.RedactedContentService
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.RoomService
import org.matrix.android.sdk.api.session.room.members.MembershipService
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.TimelineService

private const val A_ROOM_ID = "!room:example.org"
private const val ME = "@alice:example.org"
private const val SOMEONE_ELSE = "@bob:example.org"

private val IMAGE_CONTENT = mapOf<String, Any>(
        "msgtype" to "m.image",
        "body" to "photo.jpg",
        "url" to "mxc://example.org/abc",
)
private val TEXT_CONTENT = mapOf<String, Any>("msgtype" to "m.text", "body" to "just words")

class PreservedAttachmentResolverTest {

    private val redactedContentService = mockk<RedactedContentService>()
    private val membershipService = mockk<MembershipService> { every { getRoomMember(any()) } returns null }
    private val timelineService = mockk<TimelineService> { every { getTimelineEvent(any()) } returns null }
    private val room = mockk<Room> {
        every { membershipService() } returns membershipService
        every { timelineService() } returns timelineService
    }
    private val roomService = mockk<RoomService> { every { getRoom(A_ROOM_ID) } returns room }
    private val session = mockk<Session> {
        every { myUserId } returns ME
        every { redactedContentService() } returns redactedContentService
        every { roomService() } returns roomService
    }
    private val activeSessionHolder = mockk<ActiveSessionHolder> { every { getSafeActiveSession() } returns session }
    private val revealManager = mockk<RedactedContentRevealManager> {
        every { isRevealed(any(), any(), any()) } returns true
    }
    private val mediaStore = mockk<PreservedMediaStore>(relaxed = true)

    private val resolver = PreservedAttachmentResolver(
            activeSessionHolder = { activeSessionHolder },
            revealManager = revealManager,
            mediaStore = mediaStore,
    )

    private fun preserved(eventId: String, content: Map<String, Any> = IMAGE_CONTENT, senderId: String = SOMEONE_ELSE) =
            PreservedEventContent(
                    eventId = eventId,
                    roomId = A_ROOM_ID,
                    content = content,
                    clearType = EventType.MESSAGE,
                    senderId = senderId,
                    originServerTs = 1000L,
                    origin = PreservationOrigin.CAPTURED,
                    preservedAt = 2000L,
            )

    private fun givenPreserved(vararg items: PreservedEventContent) {
        coEvery { redactedContentService.getPreservedContentInRoom(A_ROOM_ID) } returns items.toList()
    }

    private fun localEvent(eventId: String, redacted: Boolean): TimelineEvent {
        val root = Event(
                type = EventType.MESSAGE,
                eventId = eventId,
                roomId = A_ROOM_ID,
                senderId = SOMEONE_ELSE,
                content = IMAGE_CONTENT,
                unsignedData = if (redacted) UnsignedData(age = null, redactedBy = "\$redaction") else null,
        )
        return TimelineEvent(
                root = root,
                localId = 1L,
                eventId = eventId,
                displayIndex = 0,
                senderInfo = SenderInfo(SOMEONE_ELSE, null, true, null),
        )
    }

    @Test
    fun `given a redacted attachment, then it is offered`() = runTest {
        givenPreserved(preserved("\$one"))

        resolver.attachments(A_ROOM_ID).map { it.eventId } shouldBeEqualTo listOf("\$one")
    }

    /** A tombstone for a cancelled upload describes an event the timeline still has live. */
    @Test
    fun `given the local event is still live, then it is not offered`() = runTest {
        givenPreserved(preserved("\$one"))
        every { timelineService.getTimelineEvent("\$one") } returns localEvent("\$one", redacted = false)

        resolver.attachments(A_ROOM_ID).shouldBeEmpty()
    }

    @Test
    fun `given the local event is redacted, then it is still offered`() = runTest {
        givenPreserved(preserved("\$one"))
        every { timelineService.getTimelineEvent("\$one") } returns localEvent("\$one", redacted = true)

        resolver.attachments(A_ROOM_ID).map { it.eventId } shouldBeEqualTo listOf("\$one")
    }

    @Test
    fun `given a preserved text message, then it is not offered as an attachment`() = runTest {
        givenPreserved(preserved("\$one", content = TEXT_CONTENT))

        resolver.attachments(A_ROOM_ID).shouldBeEmpty()
        resolver.uploads(A_ROOM_ID).shouldBeEmpty()
    }

    @Test
    fun `given the message is not revealed, then it is not offered`() = runTest {
        givenPreserved(preserved("\$one"))
        every { revealManager.isRevealed(A_ROOM_ID, "\$one", any()) } returns false

        resolver.attachments(A_ROOM_ID).shouldBeEmpty()
    }

    @Test
    fun `given an own message, then the reveal check is told so`() = runTest {
        givenPreserved(preserved("\$mine", senderId = ME))
        every { revealManager.isRevealed(A_ROOM_ID, "\$mine", isOwnMessage = true) } returns false

        resolver.attachments(A_ROOM_ID).shouldBeEmpty()
    }

    /** No local media file: MSC2815 restores content but never media, and the mxc url usually resolves. */
    @Test
    fun `given no preserved file on disk, then the attachment is still offered`() = runTest {
        givenPreserved(preserved("\$one"))
        every { mediaStore.fileFor(any(), any()) } returns java.io.File("/nonexistent")

        resolver.attachments(A_ROOM_ID).map { it.eventId } shouldBeEqualTo listOf("\$one")
    }

    @Test
    fun `given attachments, then uploads exposes the same set with parsed content`() = runTest {
        givenPreserved(preserved("\$one"), preserved("\$two"))

        val uploads = resolver.uploads(A_ROOM_ID)

        uploads.map { it.eventId } shouldBeEqualTo listOf("\$one", "\$two")
        uploads.map { it.contentWithAttachmentContent.body } shouldBeEqualTo listOf("photo.jpg", "photo.jpg")
    }

    @Test
    fun `given no active session, then nothing is offered`() = runTest {
        every { activeSessionHolder.getSafeActiveSession() } returns null

        resolver.attachments(A_ROOM_ID).shouldBeEmpty()
        resolver.uploads(A_ROOM_ID).shouldBeEmpty()
    }
}
