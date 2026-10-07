/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.media

import io.mockk.mockk
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.commonmark.parser.Parser
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.EditAggregatedSummary
import org.matrix.android.sdk.api.session.room.model.EventAnnotationsSummary
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.util.JsonDict
import org.robolectric.RobolectricTestRunner

private const val AN_EVENT_ID = "\$event"
private const val A_ROOM_ID = "!room:example.org"
private const val URL = "https://matrix.org"
private const val EDITED_URL = "https://element.io"

private fun previewOf(url: String, title: String): JsonDict = mapOf(
        "matrix:matched_url" to url,
        "og:title" to title
)

@RunWith(RobolectricTestRunner::class)
class DefaultMediaServiceTest {

    private val mediaService = DefaultMediaService(
            clearPreviewUrlCacheTask = mockk(),
            getPreviewUrlTask = mockk(),
            getRawPreviewUrlTask = mockk(),
            urlsExtractor = UrlsExtractor(AndroidWebUrlPattern(), Parser.builder().build())
    )

    private fun anEvent(content: JsonDict, latestEdit: Event? = null) = TimelineEvent(
            root = Event(type = EventType.MESSAGE, eventId = AN_EVENT_ID, roomId = A_ROOM_ID, content = mapOf("msgtype" to "m.text") + content),
            localId = 1L,
            eventId = AN_EVENT_ID,
            senderInfo = SenderInfo("@alice:example.org", null, true, null),
            annotations = latestEdit?.let {
                EventAnnotationsSummary(editSummary = EditAggregatedSummary(latestEdit = it, sourceEvents = emptyList(), localEchos = emptyList()))
            }
    )

    @Test
    fun `the previews of a message are read from its content`() {
        val event = anEvent(mapOf("body" to URL, "m.url_previews" to listOf(previewOf(URL, "Matrix.org"))))

        val previews = mediaService.extractBundledUrlPreviews(event)!!

        previews.size shouldBeEqualTo 1
        previews[0].previewUrlData!!.title shouldBeEqualTo "Matrix.org"
    }

    @Test
    fun `a bundled preview is ignored when its visible url points elsewhere`() {
        val event = anEvent(mapOf(
                "body" to URL,
                "format" to "org.matrix.custom.html",
                "formatted_body" to "<a href=\"https://not-matrix.org\" data-mx-link data-org.matrix.msc4550.link>$URL</a>",
                "m.url_previews" to listOf(previewOf(URL, "Matrix.org"))
        ))

        mediaService.extractBundledUrlPreviews(event)!!.shouldBeEmpty()
        mediaService.extractUrls(event).shouldBeEmpty()
    }

    @Test
    fun `a bundled preview is kept when its visible url matches its destination`() {
        val event = anEvent(mapOf(
                "body" to URL,
                "format" to "org.matrix.custom.html",
                "formatted_body" to "<a href=\"$URL\" data-mx-link>$URL</a>",
                "m.url_previews" to listOf(previewOf(URL, "Matrix.org"))
        ))

        mediaService.extractBundledUrlPreviews(event)!!.single().matchedUrl shouldBeEqualTo URL
    }

    @Test
    fun `a message which bundles no preview is reported as such`() {
        mediaService.extractBundledUrlPreviews(anEvent(mapOf("body" to URL))).shouldBeNull()
    }

    @Test
    fun `the previews of an edited message come from the edit`() {
        val edit = Event(
                type = EventType.MESSAGE,
                eventId = "\$edit",
                roomId = A_ROOM_ID,
                content = mapOf(
                        "body" to "* $EDITED_URL",
                        "msgtype" to "m.text",
                        "m.new_content" to mapOf(
                                "body" to EDITED_URL,
                                "msgtype" to "m.text",
                                "m.url_previews" to listOf(previewOf(EDITED_URL, "Element"))
                        )
                )
        )
        val event = anEvent(
                content = mapOf("body" to URL, "m.url_previews" to listOf(previewOf(URL, "Matrix.org"))),
                latestEdit = edit
        )

        val previews = mediaService.extractBundledUrlPreviews(event)!!

        previews.size shouldBeEqualTo 1
        previews[0].matchedUrl shouldBeEqualTo EDITED_URL
        previews[0].previewUrlData!!.title shouldBeEqualTo "Element"
    }

    @Test
    fun `an edit which bundles no preview drops the previews of the original message`() {
        val edit = Event(
                type = EventType.MESSAGE,
                eventId = "\$edit",
                roomId = A_ROOM_ID,
                content = mapOf(
                        "body" to "* no link anymore",
                        "msgtype" to "m.text",
                        "m.new_content" to mapOf("body" to "no link anymore", "msgtype" to "m.text")
                )
        )
        val event = anEvent(
                content = mapOf("body" to URL, "m.url_previews" to listOf(previewOf(URL, "Matrix.org"))),
                latestEdit = edit
        )

        mediaService.extractBundledUrlPreviews(event).shouldBeNull()
    }
}
