/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.selection

import im.vector.app.features.home.room.detail.timeline.format.NoticeEventFormatter
import im.vector.app.features.media.MediaContentRevealManager
import im.vector.app.features.redaction.preservation.PreservedAttachmentResolver
import im.vector.app.features.redaction.preservation.RedactedContentRestorer
import im.vector.app.features.settings.MediaPreviewMode
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.settings.admin.ServerAdminStatusDataSource
import im.vector.app.features.timelineexport.TimelineExportBuilder
import im.vector.app.features.timelineexport.TimelineExportDraft
import im.vector.app.features.timelineexport.TimelineExportOptions
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.UnsignedData
import org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilities
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TimelineExportBuilderTest {
    private val self = "@river:example.org"
    private val roomId = "!garden:example.org"
    private val room = mockk<Room>(relaxed = true)
    private val session = mockk<Session>(relaxed = true)
    private val preferences = mockk<VectorPreferences>(relaxed = true)
    private val context = RuntimeEnvironment.getApplication()
    private val notice = mockk<NoticeEventFormatter>(relaxed = true)
    private val preserved = mockk<PreservedAttachmentResolver>(relaxed = true)
    private val renderComponents = mockk<im.vector.app.features.timelineexport.TimelineExportRenderComponent.Factory>(relaxed = true).also { factory ->
        every { factory.create().information().create(any()) } returns im.vector.app.features.home.room.detail.timeline.item.MessageInformationData(
                eventId = "fixture", senderId = self, sendState = SendState.SYNCED, ageLocalTS = null, avatarUrl = null,
                memberName = "River", messageLayout = im.vector.app.features.home.room.detail.timeline.style.TimelineMessageLayout.Default(true, true, true),
                reactionsSummary = im.vector.app.features.home.room.detail.timeline.item.ReactionsSummaryData(), sentByMe = true)
    }
    private val builder = TimelineExportBuilder(context, preferences, notice, mockk<RedactedContentRestorer>(relaxed = true),
            mockk<MediaContentRevealManager>(relaxed = true), mockk<ServerAdminStatusDataSource>(relaxed = true), preserved, mockk(relaxed = true), mockk(relaxed = true), renderComponents, mockk(relaxed = true), mockk(relaxed = true))

    private fun event(id: String, type: String = "m.room.message", content: Map<String, Any> = mapOf("msgtype" to "m.text", "body" to "A message"), redacted: Boolean = false) =
            TimelineEvent(Event(type = type, eventId = id, roomId = roomId, senderId = self, originServerTs = 1000,
                    content = content, unsignedData = if (redacted) UnsignedData(age = null, redactedEvent = Event(type = "m.room.redaction")) else null)
                    .also { it.sendState = SendState.SYNCED }, 0, id, senderInfo = SenderInfo(self, "River", true, null))

    private suspend fun build(events: List<TimelineEvent>, options: TimelineExportOptions = TimelineExportOptions(), memberEvent: Event? = null):
            im.vector.app.features.timelineexport.TimelineExportSnapshot {
        every { session.myUserId } returns self
        every { session.roomService().getRoom(roomId) } returns room
        every { session.homeServerCapabilitiesService().getHomeServerCapabilities() } returns HomeServerCapabilities()
        every { preferences.getMediaPreviewMode(any()) } returns MediaPreviewMode.ALWAYS_SHOW
        every { room.timelineService().getTimelineEvent(any()) } answers { events.find { it.eventId == firstArg<String>() } }
        every { room.timelineService().getTimelineEventsFlow(any()) } answers {
            val ids = firstArg<List<String>>().toSet()
            kotlinx.coroutines.flow.flowOf(events.filter { it.eventId in ids })
        }
        every { room.stateService().getStateEvent(any(), any()) } answers { memberEvent?.takeIf { firstArg<String>() == "m.room.member" } }
        coEvery { session.redactedContentService().getPreservedContent(any()) } returns null
        coEvery { session.redactedContentService().getPreservedRelationsOf(any(), any()) } returns emptyList()
        val directory = File(context.cacheDir, "export-builder-test").also { it.mkdirs() }
        return try { builder.build(session, TimelineExportDraft("draft", self, roomId, eventIds = events.map { it.eventId }, options = options), directory) { _, _ -> } } finally { directory.deleteRecursively() }
    }

    @Test fun `debug event exclusion applies to the prepared events and raw payloads`() = runBlocking {
        val result = build(listOf(event("\$text"), event("\$debug", "com.example.debug", mapOf("secret" to "not exported"))))
        assertEquals(1, result.events.size)
        assertFalse(result.events.toString().contains("not exported"))
        val shown = build(listOf(event("\$debug", "com.example.debug")), TimelineExportOptions(visibility = mapOf("HIDDEN_EVENTS" to true)))
        assertEquals(1, shown.events.size)
    }

    @Test fun `people are exported under the name they publish, not the one this account shows`() = runBlocking {
        val member = Event(type = "m.room.member", stateKey = self, senderId = self, roomId = roomId,
                content = mapOf("membership" to "join", "displayname" to "River Stone"))
        val result = build(listOf(event("\$text")), memberEvent = member)
        assertEquals("River Stone", result.events.single().person.name)
        assertEquals("River Stone", result.exportedBy)
    }

    @Test fun `unrevealed redacted messages never export the stored body or edit history`() = runBlocking {
        val result = build(listOf(event("\$deleted", content = mapOf("body" to "old content"), redacted = true)), TimelineExportOptions(revealRedacted = "NONE"))
        assertEquals(1, result.events.size)
        assertFalse(result.events.toString().contains("old content"))
        assertTrue(result.events.single().history.isEmpty())
    }

    @Test fun `omitting attachments does not download them and removes their remote file reference`() = runBlocking {
        val result = build(listOf(event("\$image", content = mapOf("msgtype" to "m.image", "body" to "image.png", "url" to "mxc://example.org/picture",
                "info" to mapOf("mimetype" to "image/png")))), TimelineExportOptions(omitAttachments = true))
        assertNull(result.events.single().attachment)
        assertFalse(result.events.single().raw.toString().contains("mxc://"))
        coVerify(exactly = 0) { session.fileService().downloadFile(any(), any(), any(), any()) }
    }

    @Test fun `large selections load in bounded batches without per-event lookup`() = runBlocking {
        val events = (0 until 750).map { index -> event("\$event-$index") }
        val result = build(events)
        assertEquals(750, result.events.size)
        val timeline = room.timelineService()
        io.mockk.verify(exactly = 12) { timeline.getTimelineEventsFlow(match { it.size <= 64 }) }
        io.mockk.verify(exactly = 0) { timeline.getTimelineEvent(any()) }
    }
    @Test fun `formatted topic is frozen and removed with room identity`() = runBlocking {
        every { room.roomSummary() } returns org.matrix.android.sdk.api.session.room.model.RoomSummary(roomId = roomId, topic = "A garden", topicFormatted = "A <b>garden</b>", isEncrypted = false, encryptionEventTs = null, typingUsers = emptyList())
        val visible = build(listOf(event("e-topic")))
        assertEquals("A <b>garden</b>", visible.topicFormatted)
        val private = build(listOf(event("e-topic")), TimelineExportOptions(hideRoomIdentity = true))
        assertEquals("", private.topic)
        assertNull(private.topicFormatted)
    }

    @Test fun `mentions freeze profiles outside the selected events without exposing hidden room identity`() = runBlocking {
        val mentioned = "@petal:example.org"
        every { room.membershipService().getRoomMember(mentioned) } returns org.matrix.android.sdk.api.session.room.model.RoomMemberSummary(
                membership = org.matrix.android.sdk.api.session.room.model.Membership.JOIN, userId = mentioned, displayName = "Petal", originalDisplayName = "Petal")
        val html = "Hello <a href=\"matrix:u/petal:example.org\">old label</a> in <a href=\"https://matrix.to/#/$roomId\">Garden</a>"
        val content = mapOf("msgtype" to "m.text", "body" to "Hello Petal in Garden", "format" to "org.matrix.custom.html", "formatted_body" to html)
        val result = build(listOf(event("e-mention", content = content)), TimelineExportOptions(hideRoomIdentity = true))
        assertEquals(1, result.selectedCount)
        assertEquals(mentioned, result.mentionedPeople.single().id)
        assertEquals("Petal", result.mentionedPeople.single().name)
        assertFalse(result.mentionedPeople.toString().contains(roomId))
        assertTrue(result.events.single().html.contains("matrix:u/petal:example.org"))
    }
}
