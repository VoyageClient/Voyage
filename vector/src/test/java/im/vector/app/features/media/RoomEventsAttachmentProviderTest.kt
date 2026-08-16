/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import im.vector.app.core.date.VectorDateFormatter
import im.vector.app.core.resources.StringProvider
import im.vector.lib.attachmentviewer.AttachmentInfo
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.UnsignedData
import org.matrix.android.sdk.api.session.file.FileService
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent

class RoomEventsAttachmentProviderTest {

    private fun aTimelineEvent(eventId: String, content: Content?, transactionId: String? = null): TimelineEvent {
        return TimelineEvent(
                root = Event(
                        type = EventType.MESSAGE,
                        eventId = eventId,
                        roomId = "!room:example.org",
                        senderId = "@alice:example.org",
                        content = content,
                        unsignedData = transactionId?.let { UnsignedData(age = null, transactionId = it) },
                ),
                localId = 0,
                eventId = eventId,
                displayIndex = 0,
                senderInfo = SenderInfo("@alice:example.org", "alice", true, null),
        )
    }

    private fun anImageEventContent(filename: String, url: String): Content = mapOf(
            "msgtype" to "m.image",
            "body" to filename,
            "url" to url,
            "info" to mapOf("w" to 100, "h" to 50, "mimetype" to "image/jpeg", "size" to 123),
    )

    private fun aGalleryContent(): Content = mapOf(
            "msgtype" to "dm.filament.gallery",
            "body" to "caption",
            "itemtypes" to listOf(
                    mapOf(
                            "itemtype" to "m.image",
                            "body" to "one.jpg",
                            "url" to "mxc://example.org/one",
                            "info" to mapOf("w" to 10, "h" to 10, "mimetype" to "image/jpeg", "size" to 1),
                    ),
                    mapOf(
                            "itemtype" to "m.video",
                            "body" to "two.mp4",
                            "url" to "mxc://example.org/two",
                            "info" to mapOf("w" to 20, "h" to 10, "mimetype" to "video/mp4", "size" to 2, "duration" to 4000),
                    ),
                    mapOf(
                            "itemtype" to "m.file",
                            "body" to "three.pdf",
                            "url" to "mxc://example.org/three",
                            "info" to mapOf("mimetype" to "application/pdf", "size" to 3),
                    ),
            ),
    )

    private fun aProvider(events: List<TimelineEvent>): RoomEventsAttachmentProvider {
        return RoomEventsAttachmentProvider(
                attachments = events,
                imageContentRenderer = mockk<ImageContentRenderer>(relaxed = true),
                dateFormatter = mockk<VectorDateFormatter>(relaxed = true),
                fileService = mockk<FileService>(relaxed = true),
                coroutineScope = CoroutineScope(Dispatchers.Unconfined),
                stringProvider = mockk<StringProvider>(relaxed = true),
        )
    }

    @Test
    fun `gallery fans out to one page per visual item`() {
        val provider = aProvider(
                listOf(
                        aTimelineEvent("\$img", anImageEventContent("solo.jpg", "mxc://example.org/solo")),
                        aTimelineEvent("\$gallery", aGalleryContent()),
                        aTimelineEvent("\$img2", anImageEventContent("last.jpg", "mxc://example.org/last")),
                )
        )
        // 1 + (2 visual gallery items, file skipped) + 1
        assertEquals(4, provider.getItemCount())
    }

    @Test
    fun `gallery pages carry composite uids and the real event`() {
        val provider = aProvider(listOf(aTimelineEvent("\$gallery", aGalleryContent())))
        val first = provider.getAttachmentInfoAt(0)
        val second = provider.getAttachmentInfoAt(1)
        assertTrue(first is AttachmentInfo.Image || first is AttachmentInfo.AnimatedImage)
        assertEquals("\$gallery#0", first.uid)
        assertTrue(second is AttachmentInfo.Video)
        assertEquals("\$gallery#1", second.uid)
        val firstData = (first as AttachmentInfo.Image).data as ImageContentRenderer.Data
        assertEquals("\$gallery", firstData.eventId)
        assertEquals("\$gallery#0", firstData.stableId)
        assertEquals("one.jpg", firstData.filename)
        assertEquals("mxc://example.org/one", firstData.url)
        val videoData = (second as AttachmentInfo.Video).data as VideoContentRenderer.Data
        assertEquals("mxc://example.org/two", videoData.url)
        assertEquals(4000L, videoData.durationMs)
        assertEquals("\$gallery", provider.getTimelineEventAtPosition(1)?.eventId)
    }

    @Test
    fun `indexForEvent matches event ids and transaction ids across the fan-out`() {
        val provider = aProvider(
                listOf(
                        aTimelineEvent("\$img", anImageEventContent("solo.jpg", "mxc://example.org/solo")),
                        aTimelineEvent("\$gallery", aGalleryContent()),
                        aTimelineEvent("\$img2", anImageEventContent("last.jpg", "mxc://example.org/last"), transactionId = "\$local-echo"),
                )
        )
        assertEquals(0, provider.indexForEvent("\$img"))
        assertEquals(1, provider.indexForEvent("\$gallery"))
        assertEquals(3, provider.indexForEvent("\$img2"))
        assertEquals(3, provider.indexForEvent("\$local-echo"))
        assertEquals(-1, provider.indexForEvent("\$unknown"))
    }

    @Test
    fun `plain events still map to a single page`() {
        val provider = aProvider(listOf(aTimelineEvent("\$img", anImageEventContent("solo.jpg", "mxc://example.org/solo"))))
        assertEquals(1, provider.getItemCount())
        val info = provider.getAttachmentInfoAt(0)
        assertEquals("\$img", info.uid)
        val data = (info as AttachmentInfo.Image).data as ImageContentRenderer.Data
        assertEquals("solo.jpg", data.filename)
    }

    @Test
    fun `unparseable content falls back to an empty page`() {
        // body of a wrong type defeats both the polymorphic and the sticker adapter
        val provider = aProvider(listOf(aTimelineEvent("\$broken", mapOf("body" to listOf(1, 2)))))
        assertEquals(1, provider.getItemCount())
        val info = provider.getAttachmentInfoAt(0)
        assertEquals("\$broken", info.uid)
        assertNull((info as AttachmentInfo.Image).data)
    }

    @Test
    fun `gallery with only non-visual items contributes no pages`() {
        val fileOnly: Content = mapOf(
                "msgtype" to "dm.filament.gallery",
                "body" to "x",
                "itemtypes" to listOf(
                        mapOf("itemtype" to "m.file", "body" to "a.pdf", "url" to "mxc://example.org/a"),
                        mapOf("itemtype" to "m.audio", "body" to "b.ogg", "url" to "mxc://example.org/b"),
                ),
        )
        val provider = aProvider(listOf(aTimelineEvent("\$files", fileOnly)))
        assertEquals(0, provider.getItemCount())
        assertEquals(-1, provider.indexForEvent("\$files"))
    }
}
