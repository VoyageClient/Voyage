/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.notifications

import im.vector.app.features.notifications.ProcessedEvent.Type
import im.vector.app.test.fakes.FakeAutoAcceptInvites
import im.vector.app.test.fakes.FakeOutdatedEventDetector
import im.vector.app.test.fixtures.aNotifiableMessageEvent
import im.vector.app.test.fixtures.aSimpleNotifiableEvent
import im.vector.app.test.fixtures.anInviteNotifiableEvent
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.read.ReadService

private val NOT_VIEWING_A_ROOM: String? = null
private val NOT_VIEWING_A_THREAD: String? = null

class NotifiableEventProcessorTest {

    private val outdatedDetector = FakeOutdatedEventDetector()
    private val autoAcceptInvites = FakeAutoAcceptInvites()

    private val eventProcessor = NotifiableEventProcessor(outdatedDetector.instance, autoAcceptInvites)

    @Test
    fun `given simple events when processing then keep simple events`() {
        val events = listOf(
                aSimpleNotifiableEvent(eventId = "event-1"),
                aSimpleNotifiableEvent(eventId = "event-2")
        )

        val result = eventProcessor.process(events, currentRoomId = NOT_VIEWING_A_ROOM, currentThreadId = NOT_VIEWING_A_THREAD, renderedEvents = emptyList())

        result shouldBeEqualTo listOfProcessedEvents(
                Type.KEEP to events[0],
                Type.KEEP to events[1]
        )
    }

    @Test
    fun `given redacted simple event when processing then remove redaction event`() {
        val events = listOf(aSimpleNotifiableEvent(eventId = "event-1", type = EventType.REDACTION))

        val result = eventProcessor.process(events, currentRoomId = NOT_VIEWING_A_ROOM, currentThreadId = NOT_VIEWING_A_THREAD, renderedEvents = emptyList())

        result shouldBeEqualTo listOfProcessedEvents(
                Type.REMOVE to events[0]
        )
    }

    @Test
    fun `given invites are auto accepted when processing then remove invitations`() {
        autoAcceptInvites._isEnabled = true
        val events = listOf<NotifiableEvent>(
                anInviteNotifiableEvent(roomId = "room-1"),
                anInviteNotifiableEvent(roomId = "room-2")
        )

        val result = eventProcessor.process(events, currentRoomId = NOT_VIEWING_A_ROOM, currentThreadId = NOT_VIEWING_A_THREAD, renderedEvents = emptyList())

        result shouldBeEqualTo listOfProcessedEvents(
                Type.REMOVE to events[0],
                Type.REMOVE to events[1]
        )
    }

    @Test
    fun `given invites are not auto accepted when processing then keep invitation events`() {
        autoAcceptInvites._isEnabled = false
        val events = listOf(
                anInviteNotifiableEvent(roomId = "room-1"),
                anInviteNotifiableEvent(roomId = "room-2")
        )

        val result = eventProcessor.process(events, currentRoomId = NOT_VIEWING_A_ROOM, currentThreadId = NOT_VIEWING_A_THREAD, renderedEvents = emptyList())

        result shouldBeEqualTo listOfProcessedEvents(
                Type.KEEP to events[0],
                Type.KEEP to events[1]
        )
    }

    @Test
    fun `given out of date message event when processing then removes message event`() {
        val events = listOf(aNotifiableMessageEvent(eventId = "event-1", roomId = "room-1"))
        outdatedDetector.givenEventIsOutOfDate(events[0])

        val result = eventProcessor.process(events, currentRoomId = NOT_VIEWING_A_ROOM, currentThreadId = NOT_VIEWING_A_THREAD, renderedEvents = emptyList())

        result shouldBeEqualTo listOfProcessedEvents(
                Type.REMOVE to events[0],
        )
    }

    @Test
    fun `given in date message event when processing then keep message event`() {
        val events = listOf(aNotifiableMessageEvent(eventId = "event-1", roomId = "room-1"))
        outdatedDetector.givenEventIsInDate(events[0])

        val result = eventProcessor.process(events, currentRoomId = NOT_VIEWING_A_ROOM, currentThreadId = NOT_VIEWING_A_THREAD, renderedEvents = emptyList())

        result shouldBeEqualTo listOfProcessedEvents(
                Type.KEEP to events[0],
        )
    }

    @Test
    fun `given viewing the same room main timeline when processing main timeline message event then removes message`() {
        val events = listOf(aNotifiableMessageEvent(eventId = "event-1", roomId = "room-1", threadId = null))

        val result = eventProcessor.process(events, currentRoomId = "room-1", currentThreadId = null, renderedEvents = emptyList())

        result shouldBeEqualTo listOfProcessedEvents(
                Type.REMOVE to events[0],
        )
    }

    @Test
    fun `given viewing the same thread timeline when processing thread message event then removes message`() {
        val events = listOf(aNotifiableMessageEvent(eventId = "event-1", roomId = "room-1", threadId = "thread-1"))

        val result = eventProcessor.process(events, currentRoomId = "room-1", currentThreadId = "thread-1", renderedEvents = emptyList())

        result shouldBeEqualTo listOfProcessedEvents(
                Type.REMOVE to events[0],
        )
    }

    @Test
    fun `given viewing main timeline of the same room when processing thread timeline message event then keep message`() {
        val events = listOf(aNotifiableMessageEvent(eventId = "event-1", roomId = "room-1", threadId = "thread-1"))
        outdatedDetector.givenEventIsInDate(events[0])

        val result = eventProcessor.process(events, currentRoomId = "room-1", currentThreadId = null, renderedEvents = emptyList())

        result shouldBeEqualTo listOfProcessedEvents(
                Type.KEEP to events[0],
        )
    }

    @Test
    fun `given viewing thread timeline of the same room when processing main timeline message event then keep message`() {
        val events = listOf(aNotifiableMessageEvent(eventId = "event-1", roomId = "room-1"))
        outdatedDetector.givenEventIsInDate(events[0])

        val result = eventProcessor.process(events, currentRoomId = "room-1", currentThreadId = "thread-1", renderedEvents = emptyList())

        result shouldBeEqualTo listOfProcessedEvents(
                Type.KEEP to events[0],
        )
    }

    @Test
    fun `given events are different to rendered events when processing then removes difference`() {
        val events = listOf(aSimpleNotifiableEvent(eventId = "event-1"))
        val renderedEvents = listOf<ProcessedEvent<NotifiableEvent>>(
                ProcessedEvent(Type.KEEP, events[0]),
                ProcessedEvent(Type.KEEP, anInviteNotifiableEvent(roomId = "event-2"))
        )

        val result = eventProcessor.process(events, currentRoomId = NOT_VIEWING_A_ROOM, currentThreadId = NOT_VIEWING_A_THREAD, renderedEvents = renderedEvents)

        result shouldBeEqualTo listOfProcessedEvents(
                Type.REMOVE to renderedEvents[1].event,
                Type.KEEP to renderedEvents[0].event
        )
    }

    @Test
    fun `unchanged messages do not query read state when another message arrives`() {
        val old = aNotifiableMessageEvent(eventId = "old", roomId = "!room:example.org")
        val fresh = aNotifiableMessageEvent(eventId = "fresh", roomId = "!room:example.org")
        val rendered = listOf<ProcessedEvent<NotifiableEvent>>(ProcessedEvent(Type.KEEP, old))

        val result = eventProcessor.process(listOf(old, fresh), null, null, rendered, emptySet())

        result shouldBeEqualTo listOfProcessedEvents(Type.KEEP to old, Type.KEEP to fresh)
        verify(exactly = 1) { outdatedDetector.instance.readEventIds(listOf(fresh)) }
    }

    @Test
    fun `receipt updates clear only read messages in the affected room and thread`() {
        val read = aNotifiableMessageEvent(eventId = "read", roomId = "!room:example.org")
        val unread = aNotifiableMessageEvent(eventId = "unread", roomId = "!room:example.org")
        val thread = aNotifiableMessageEvent(eventId = "thread-message", roomId = "!room:example.org", threadId = "thread")
        val other = aNotifiableMessageEvent(eventId = "other", roomId = "!other:example.org")
        val events = listOf(read, unread, thread, other)
        outdatedDetector.givenEventIsOutOfDate(read)
        val rendered = events.map { ProcessedEvent<NotifiableEvent>(Type.KEEP, it) }

        val result = eventProcessor.process(events, null, null, rendered, setOf(read.roomId to ReadService.THREAD_ID_MAIN))

        result shouldBeEqualTo listOfProcessedEvents(Type.REMOVE to read, Type.KEEP to unread, Type.KEEP to thread, Type.KEEP to other)
        verify(exactly = 1) { outdatedDetector.instance.readEventIds(listOf(read, unread)) }
    }

    @Test
    fun `an idle refresh performs no read queries`() {
        val message = aNotifiableMessageEvent(eventId = "kept", roomId = "!room:example.org")
        val rendered = listOf<ProcessedEvent<NotifiableEvent>>(ProcessedEvent(Type.KEEP, message))

        eventProcessor.process(listOf(message), null, null, rendered, emptySet()) shouldBeEqualTo rendered

        verify(exactly = 0) { outdatedDetector.instance.readEventIds(any()) }
    }

    @Test
    fun `removed notifications are not retained across refreshes`() {
        val message = aNotifiableMessageEvent(eventId = "removed", roomId = "!room:example.org")
        val removed = listOf<ProcessedEvent<NotifiableEvent>>(ProcessedEvent(Type.REMOVE, message))

        eventProcessor.process(emptyList(), null, null, removed, emptySet()) shouldBeEqualTo emptyList()
    }

    private fun listOfProcessedEvents(vararg event: Pair<Type, NotifiableEvent>) = event.map {
        ProcessedEvent(it.first, it.second)
    }
}
