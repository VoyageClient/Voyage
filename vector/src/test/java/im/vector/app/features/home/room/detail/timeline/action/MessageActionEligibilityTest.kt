/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.action

import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.translation.MessageTranslationStore
import im.vector.app.features.translation.TranslationEngine
import im.vector.app.features.translation.TranslationSettings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.PowerLevelsContent
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageDefaultContent
import org.matrix.android.sdk.api.session.room.model.message.MessageLocationContent
import org.matrix.android.sdk.api.session.room.model.message.MessageTextContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.powerlevels.RoomPowerLevels
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MessageActionEligibilityTest {
    private val roomId = "!room:example.org"
    private val eventId = "\$abcdefghijklmnopqrstuvwxyzabcdefghijklmnopq"
    private val room = mockk<Room>(relaxed = true)
    private val session = mockk<Session>(relaxed = true) {
        every { roomService().getRoom(roomId) } returns room
        every { myUserId } returns "@alice:example.org"
    }
    private val preferences = mockk<VectorPreferences>(relaxed = true)
    private val settings = mockk<TranslationSettings>(relaxed = true)
    private val translations = mockk<MessageTranslationStore>(relaxed = true)
    private val eligibility = MessageActionEligibility(
            session, preferences, mockk(relaxed = true), mockk(relaxed = true),
            CheckIfCanRedactEventUseCase(mockk { every { getActiveSession() } returns session }),
            settings, translations, mockk(relaxed = true), CheckIfCanReplyEventUseCase(),
    )

    @Test
    fun `room actions require joined membership even for privileged users`() {
        val levels = RoomPowerLevels(PowerLevelsContent(users = mapOf(session.myUserId to 100)), null)
        Membership.values().filter { it != Membership.JOIN }.forEach { membership ->
            every { room.roomSummary() } returns RoomSummary(
                    roomId, membership = membership, isEncrypted = false, encryptionEventTs = null, typingUsers = emptyList(),
            )
            eligibility.roomPermissions(roomId, levels) shouldBeEqualTo ActionPermissions()
        }
        every { session.roomService().getRoom("!unknown:example.org") } returns null
        eligibility.roomPermissions("!unknown:example.org", levels) shouldBeEqualTo ActionPermissions()
    }

    @Test
    fun `message reaction redaction and pin permissions are independent and read current levels`() {
        every { room.roomSummary() } returns RoomSummary(
                roomId, membership = Membership.JOIN, isEncrypted = false, encryptionEventTs = null, typingUsers = emptyList(),
        )
        val types = listOf(EventType.MESSAGE, EventType.REACTION, EventType.REDACTION, EventType.STATE_ROOM_PINNED_EVENT)
        types.forEach { allowedType ->
            val levels = RoomPowerLevels(PowerLevelsContent(
                    events = types.associateWith { if (it == allowedType) 0 else 50 },
            ), null)
            every { room.stateService().getRoomPowerLevels() } returns levels
            val permissions = eligibility.roomPermissions(roomId)
            permissions.canSendMessage shouldBeEqualTo (allowedType == EventType.MESSAGE)
            permissions.canReact shouldBeEqualTo (allowedType == EventType.REACTION)
            permissions.canSendRedaction shouldBeEqualTo (allowedType == EventType.REDACTION)
            permissions.canPinUnpin shouldBeEqualTo (allowedType == EventType.STATE_ROOM_PINNED_EVENT)
            permissions.canRedact shouldBeEqualTo false
        }
    }

    @Test
    fun `redacting an own event requires send-redaction permission and others require moderation permission`() {
        val own = event(MessageTextContent(msgType = MessageType.MSGTYPE_TEXT, body = "A sample message"))
        val other = own.copy(root = own.root.copy(senderId = "@bob:example.org"))
        eligibility.canRedact(own, ActionPermissions(canRedact = true)) shouldBeEqualTo false
        eligibility.canRedact(own, ActionPermissions(canSendRedaction = true)) shouldBeEqualTo true
        eligibility.canRedact(other, ActionPermissions(canSendRedaction = true)) shouldBeEqualTo false
        eligibility.canRedact(other, ActionPermissions(canSendRedaction = true, canRedact = true)) shouldBeEqualTo true
    }

    @Test
    fun `pin binding follows the current pinned event state in both directions`() {
        every { room.stateService().getStateEvent(EventType.STATE_ROOM_PINNED_EVENT, QueryStringValue.IsEmpty) } returns
                Event(type = EventType.STATE_ROOM_PINNED_EVENT, content = mapOf("pinned" to emptyList<String>()))
        eligibility.pinAction(roomId, eventId) shouldBeEqualTo EventSharedAction.Pin(eventId)

        every { room.stateService().getStateEvent(EventType.STATE_ROOM_PINNED_EVENT, QueryStringValue.IsEmpty) } returns
                Event(type = EventType.STATE_ROOM_PINNED_EVENT, content = mapOf("pinned" to listOf(eventId)))
        eligibility.pinAction(roomId, eventId) shouldBeEqualTo EventSharedAction.Unpin(eventId)

        every { room.stateService().getStateEvent(EventType.STATE_ROOM_PINNED_EVENT, QueryStringValue.IsEmpty) } returns null
        eligibility.pinAction(roomId, eventId) shouldBeEqualTo EventSharedAction.Pin(eventId)
    }

    @Test
    fun `thread replies require permission and the appropriate timeline`() {
        val content = MessageTextContent(msgType = MessageType.MSGTYPE_TEXT, body = "A sample message")
        val event = event(content)
        every { preferences.areThreadMessagesEnabled() } returns true
        eligibility.canReplyInThread(event, content, canSendMessage = false, isFromThreadTimeline = false) shouldBeEqualTo false
        eligibility.canReplyInThread(event, content, canSendMessage = true, isFromThreadTimeline = true) shouldBeEqualTo false
        eligibility.canReplyInThread(event, content, canSendMessage = true, isFromThreadTimeline = false) shouldBeEqualTo true
    }

    @Test
    fun `unknown message types with a body can be copied`() {
        val content = MessageDefaultContent(msgType = "org.example.custom", body = "A sample message")
        eligibility.canCopy(content.msgType, content) shouldBeEqualTo true
        eligibility.canCopy(content.msgType, content.copy(body = "")) shouldBeEqualTo false
        eligibility.canCopy("", content.copy(msgType = "")) shouldBeEqualTo false
    }

    @Test
    fun `unknown message types with a body can be forwarded and start a thread`() {
        val content = MessageDefaultContent(msgType = "org.example.custom", body = "A sample message")
        every { preferences.areThreadMessagesEnabled() } returns true
        eligibility.canForward(event(content)) shouldBeEqualTo true
        eligibility.canReplyInThread(event(content), content, canSendMessage = true, isFromThreadTimeline = false) shouldBeEqualTo true
        val blank = content.copy(body = "")
        eligibility.canForward(event(blank)) shouldBeEqualTo false
        eligibility.canReplyInThread(event(blank), blank, canSendMessage = true, isFromThreadTimeline = false) shouldBeEqualTo false
    }

    @Test
    fun `location messages can start a thread`() {
        val content = MessageLocationContent(body = "A sample location", geoUri = "geo:1.0,2.0")
        every { preferences.areThreadMessagesEnabled() } returns true
        eligibility.canReplyInThread(event(content), content, canSendMessage = true, isFromThreadTimeline = false) shouldBeEqualTo true
    }

    @Test
    fun `translation requires a configured primary or backup engine`() {
        val content = MessageTextContent(msgType = MessageType.MSGTYPE_TEXT, body = "A sample message")
        every { settings.engine } returns null
        every { settings.backupEngine } returns null
        eligibility.canTranslate(content) shouldBeEqualTo false
        every { settings.backupEngine } returns TranslationEngine.GOOGLE
        eligibility.canTranslate(content) shouldBeEqualTo true
    }

    @Test
    fun `translating an already translated message restores the original`() = runBlocking<Unit> {
        val event = event(MessageTextContent(msgType = MessageType.MSGTYPE_TEXT, body = "A sample message"))
        every { settings.engine } returns TranslationEngine.GOOGLE
        every { translations.isTranslated(event) } returns true
        eligibility.translationAction(event) shouldBeEqualTo EventSharedAction.Untranslate(eventId)
    }

    @Test
    fun `a pending translation does not start another request`() = runBlocking<Unit> {
        val event = event(MessageTextContent(msgType = MessageType.MSGTYPE_TEXT, body = "A sample message"))
        every { settings.engine } returns TranslationEngine.GOOGLE
        every { translations.isTranslating(eventId) } returns true
        eligibility.canTranslate(event) shouldBeEqualTo false
        eligibility.translationAction(event).shouldBeNull()
    }

    @Test
    fun `translation retains the formatted body`() = runBlocking<Unit> {
        val event = event(MessageTextContent(
                msgType = MessageType.MSGTYPE_TEXT,
                body = "A sample message",
                format = "org.matrix.custom.html",
                formattedBody = "A <strong>sample</strong> message",
        ))
        every { settings.engine } returns TranslationEngine.GOOGLE
        val action = eligibility.translationAction(event) as EventSharedAction.Translate
        action.text shouldBeEqualTo "A sample message"
        action.formattedBody shouldBeEqualTo "A <strong>sample</strong> message"
    }

    private inline fun <reified T : MessageContent> event(content: T) = TimelineEvent(
            root = Event(type = EventType.MESSAGE, eventId = eventId, senderId = session.myUserId, content = content.toContent()),
            localId = 1L,
            eventId = eventId,
            senderInfo = SenderInfo("@alice:example.org", "Alice", true, null),
    )
}
