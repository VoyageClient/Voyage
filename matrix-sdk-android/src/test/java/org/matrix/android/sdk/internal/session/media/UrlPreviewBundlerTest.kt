/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.media

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.settings.LightweightSettingsStorage
import org.matrix.android.sdk.api.settings.LinkPreviewMode
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.session.content.ContentUploadResponse
import org.matrix.android.sdk.internal.session.content.FileUploader
import org.matrix.android.sdk.internal.session.room.send.LocalEchoRepository
import org.matrix.android.sdk.internal.session.room.summary.RoomSummaryDataSource
import org.matrix.android.sdk.test.fakes.FakeClock
import org.robolectric.RobolectricTestRunner

private const val AN_EVENT_ID = "\$local.event"
private const val A_ROOM_ID = "!room:example.org"
private const val URL = "https://matrix.org"
private const val UPLOADED_MXC = "mxc://example.org/reuploaded"
private val IMAGE_BYTES = ByteArray(64) { it.toByte() }

private val PREVIEW = FetchedPreview(
        fields = mapOf(
                "og:url" to "https://matrix.org/",
                "og:title" to "Matrix.org",
                "og:description" to "The open protocol",
                "og:site_name" to "Matrix.org"
        ),
        image = FetchedImage(IMAGE_BYTES, "image/jpeg")
)

@RunWith(RobolectricTestRunner::class)
internal class UrlPreviewBundlerTest {

    private val urlPreviewFetcher = mockk<UrlPreviewFetcher>()
    private val homeServerUrlPreviewFetcher = mockk<HomeServerUrlPreviewFetcher>()
    private val fileUploader = mockk<FileUploader>()
    private val settingsStorage = mockk<LightweightSettingsStorage> {
        every { getLinkPreviewMode(any()) } returns LinkPreviewMode.ALWAYS
    }
    private val roomSummaryDataSource = mockk<RoomSummaryDataSource>()
    private val localEchoRepository = mockk<LocalEchoRepository>()
    private val uploadedBytes = slot<ByteArray>()
    private val echoUpdate = slot<(EventEntity) -> Unit>()

    private val bundler = UrlPreviewBundler(
            urlsExtractor = UrlsExtractor(),
            urlPreviewFetcher = urlPreviewFetcher,
            homeServerUrlPreviewFetcher = homeServerUrlPreviewFetcher,
            fileUploader = fileUploader,
            bundleCache = UrlPreviewBundleCache(clock = mockk(relaxed = true)),
            lightweightSettingsStorage = settingsStorage,
            roomSummaryDataSource = roomSummaryDataSource,
            localEchoRepository = localEchoRepository,
            taskExecutor = mockk { every { executorScope } returns CoroutineScope(Dispatchers.Unconfined) },
            clock = FakeClock().apply { givenEpoch(1234) }
    )

    init {
        coEvery { urlPreviewFetcher.fetch(any()) } returns PREVIEW
        coEvery { homeServerUrlPreviewFetcher.fetch(any()) } returns PREVIEW.copy(fields = PREVIEW.fields + ("og:title" to "From the homeserver"))
        coEvery { fileUploader.uploadByteArray(capture(uploadedBytes), any(), any(), any()) } returns ContentUploadResponse(UPLOADED_MXC)
        coEvery { localEchoRepository.updateEcho(any(), capture(echoUpdate)) } just Runs
        every { roomSummaryDataSource.getRoomSummary(any()) } returns null
    }

    private fun textEvent(body: String, extra: JsonDict = emptyMap()) = Event(
            type = EventType.MESSAGE,
            eventId = AN_EVENT_ID,
            roomId = A_ROOM_ID,
            content = mapOf("msgtype" to "m.text", "body" to body) + extra
    )

    @Suppress("UNCHECKED_CAST")
    private fun Event.previews() = content?.get("m.url_previews") as? List<JsonDict>

    @Suppress("UNCHECKED_CAST")
    private fun Event.unstablePreviews() = content?.get("com.beeper.linkpreviews") as? List<JsonDict>

    private fun givenDirectRoom() {
        every { roomSummaryDataSource.getRoomSummary(A_ROOM_ID) } returns mockk<RoomSummary> { every { isDirect } returns true }
    }

    @Test
    fun `a message with a link gets its preview bundled`() = runTest {
        val previews = bundler.bundleUrlPreviews(textEvent("look at $URL"), encrypt = false).previews()!!

        previews.size shouldBeEqualTo 1
        previews[0]["matrix:matched_url"] shouldBeEqualTo URL
        previews[0]["og:title"] shouldBeEqualTo "Matrix.org"
        previews[0]["og:description"] shouldBeEqualTo "The open protocol"
    }

    @Test
    fun `the unstable field carries the same previews`() = runTest {
        val bundled = bundler.bundleUrlPreviews(textEvent(URL), encrypt = false)

        bundled.unstablePreviews() shouldBeEqualTo bundled.previews()
        bundled.previews()!![0]["matched_url"] shouldBeEqualTo URL
    }

    @Test
    fun `the thumbnail is uploaded as our own media, so it outlives the page`() = runTest {
        val preview = bundler.bundleUrlPreviews(textEvent(URL), encrypt = false).previews()!![0]

        preview["og:image"] shouldBeEqualTo UPLOADED_MXC
        preview["og:image:type"] shouldBeEqualTo "image/jpeg"
        preview["matrix:image:size"] shouldBeEqualTo IMAGE_BYTES.size
        uploadedBytes.captured shouldBeEqualTo IMAGE_BYTES
    }

    @Test
    fun `the thumbnail of an encrypted room is encrypted`() = runTest {
        val preview = bundler.bundleUrlPreviews(textEvent(URL), encrypt = true).previews()!![0]

        preview.containsKey("og:image").shouldBeFalse()
        @Suppress("UNCHECKED_CAST")
        val encrypted = preview["matrix:image:encrypted"] as JsonDict
        encrypted["url"] shouldBeEqualTo UPLOADED_MXC
        preview["beeper:image:encryption"] shouldBeEqualTo encrypted
        uploadedBytes.captured shouldNotBeEqualTo IMAGE_BYTES
    }

    @Test
    fun `the local echo is updated, so the sender sees the preview too`() = runTest {
        val bundled = bundler.bundleUrlPreviews(textEvent(URL), encrypt = false)

        coVerify { localEchoRepository.updateEcho(AN_EVENT_ID, any()) }
        val entity = EventEntity()
        echoUpdate.captured(entity)
        entity.content shouldBeEqualTo ContentMapper.map(bundled.content)
    }

    @Test
    fun `previews are fetched by this device by default`() = runTest {
        bundler.bundleUrlPreviews(textEvent(URL), encrypt = false).previews()!![0]["og:title"] shouldBeEqualTo "Matrix.org"

        coVerify(exactly = 0) { homeServerUrlPreviewFetcher.fetch(any()) }
    }

    @Test
    fun `the homeserver previews the link when the user asked for that`() = runTest {
        every { settingsStorage.getLinkPreviewMode(A_ROOM_ID) } returns LinkPreviewMode.NEVER

        bundler.bundleUrlPreviews(textEvent(URL), encrypt = false).previews()!![0]["og:title"] shouldBeEqualTo "From the homeserver"

        coVerify(exactly = 0) { urlPreviewFetcher.fetch(any()) }
    }

    @Test
    fun `previewing only in encrypted rooms keeps the link off the homeserver exactly there`() = runTest {
        every { settingsStorage.getLinkPreviewMode(A_ROOM_ID) } returns LinkPreviewMode.ENCRYPTED_ROOMS

        bundler.bundleUrlPreviews(textEvent(URL), encrypt = true)
        coVerify { urlPreviewFetcher.fetch(URL) }
        coVerify(exactly = 0) { homeServerUrlPreviewFetcher.fetch(any()) }

        bundler.bundleUrlPreviews(textEvent(URL), encrypt = false)
        coVerify { homeServerUrlPreviewFetcher.fetch(URL) }
    }

    @Test
    fun `previewing only in direct messages asks the room whether it is one`() = runTest {
        every { settingsStorage.getLinkPreviewMode(A_ROOM_ID) } returns LinkPreviewMode.DIRECT_MESSAGES

        bundler.bundleUrlPreviews(textEvent(URL), encrypt = false)
        coVerify { homeServerUrlPreviewFetcher.fetch(URL) }

        givenDirectRoom()
        bundler.bundleUrlPreviews(textEvent(URL), encrypt = false)
        coVerify { urlPreviewFetcher.fetch(URL) }
    }

    @Test
    fun `a link previewed while it was typed is not fetched again when the message is sent`() = runTest {
        bundler.prefetch(A_ROOM_ID, "look at $URL", encrypt = false)

        bundler.bundleUrlPreviews(textEvent(URL), encrypt = false).previews()!!.size shouldBeEqualTo 1

        coVerify(exactly = 1) { urlPreviewFetcher.fetch(URL) }
        coVerify(exactly = 1) { fileUploader.uploadByteArray(any(), any(), any(), any()) }
    }

    @Test
    fun `an encrypted room does not reuse the thumbnail uploaded in the clear`() = runTest {
        bundler.prefetch(A_ROOM_ID, URL, encrypt = false)

        bundler.bundleUrlPreviews(textEvent(URL), encrypt = true)

        coVerify(exactly = 2) { fileUploader.uploadByteArray(any(), any(), any(), any()) }
    }

    @Test
    fun `a message without any link is left alone`() = runTest {
        val event = textEvent("no link here")

        bundler.bundleUrlPreviews(event, encrypt = false) shouldBeEqualTo event
        coVerify(exactly = 0) { urlPreviewFetcher.fetch(any()) }
    }

    @Test
    fun `an already bundled event is not previewed again, so a resend reuploads nothing`() = runTest {
        val event = textEvent(URL, extra = mapOf("m.url_previews" to listOf(mapOf("matrix:matched_url" to URL))))

        bundler.bundleUrlPreviews(event, encrypt = false) shouldBeEqualTo event
        coVerify(exactly = 0) { urlPreviewFetcher.fetch(any()) }
    }

    @Test
    fun `an event which is not a message is left alone`() = runTest {
        val event = Event(type = EventType.REACTION, eventId = AN_EVENT_ID, roomId = A_ROOM_ID, content = mapOf("body" to URL))

        bundler.bundleUrlPreviews(event, encrypt = false) shouldBeEqualTo event
    }

    @Test
    fun `an attachment is left alone`() = runTest {
        val event = Event(
                type = EventType.MESSAGE,
                eventId = AN_EVENT_ID,
                roomId = A_ROOM_ID,
                content = mapOf("msgtype" to "m.image", "body" to URL)
        )

        bundler.bundleUrlPreviews(event, encrypt = false) shouldBeEqualTo event
    }

    @Test
    fun `notices and emotes are previewed too`() = runTest {
        listOf("m.notice", "m.emote").forEach { msgType ->
            val event = Event(
                    type = EventType.MESSAGE,
                    eventId = AN_EVENT_ID,
                    roomId = A_ROOM_ID,
                    content = mapOf("msgtype" to msgType, "body" to URL)
            )

            bundler.bundleUrlPreviews(event, encrypt = false).previews()!!.size shouldBeEqualTo 1
        }
    }

    @Test
    fun `an edit previews the text it replaces the message with`() = runTest {
        val event = textEvent(
                body = "* edited",
                extra = mapOf("m.new_content" to mapOf("msgtype" to "m.text", "body" to "edited, now with $URL"))
        )

        val bundled = bundler.bundleUrlPreviews(event, encrypt = false)

        bundled.previews()!![0]["matrix:matched_url"] shouldBeEqualTo URL
        @Suppress("UNCHECKED_CAST")
        val newContent = bundled.content!!["m.new_content"] as JsonDict
        newContent["m.url_previews"] shouldBeEqualTo bundled.previews()
        newContent["com.beeper.linkpreviews"] shouldBeEqualTo bundled.previews()
    }

    @Test
    fun `the fallback of a reply is not previewed`() = runTest {
        val event = textEvent("> <@alice:example.org> https://quoted.example.org\n\nhave a look at $URL")

        val previews = bundler.bundleUrlPreviews(event, encrypt = false).previews()!!

        previews.size shouldBeEqualTo 1
        previews[0]["matrix:matched_url"] shouldBeEqualTo URL
    }

    @Test
    fun `permalinks are not fetched`() = runTest {
        val event = textEvent("hello https://matrix.to/#/@alice:example.org")

        bundler.bundleUrlPreviews(event, encrypt = false) shouldBeEqualTo event
        coVerify(exactly = 0) { urlPreviewFetcher.fetch(any()) }
    }

    @Test
    fun `only the first few links of a message are previewed`() = runTest {
        val links = (1..8).map { "https://example$it.org" }

        val previews = bundler.bundleUrlPreviews(textEvent(links.joinToString(" ")), encrypt = false).previews()!!

        previews.size shouldBeEqualTo 4
    }

    @Test
    fun `a message is sent unbundled when the page cannot be previewed`() = runTest {
        coEvery { urlPreviewFetcher.fetch(any()) } returns null
        val event = textEvent(URL)

        bundler.bundleUrlPreviews(event, encrypt = false) shouldBeEqualTo event
    }

    @Test
    fun `a message is sent unbundled when previewing throws`() = runTest {
        coEvery { urlPreviewFetcher.fetch(any()) } throws IllegalStateException("no network")
        val event = textEvent(URL)

        bundler.bundleUrlPreviews(event, encrypt = false) shouldBeEqualTo event
    }

    @Test
    fun `a preview with nothing but an image still carries it`() = runTest {
        coEvery { urlPreviewFetcher.fetch(any()) } returns FetchedPreview(fields = emptyMap(), image = FetchedImage(IMAGE_BYTES, "image/png"))

        val preview = bundler.bundleUrlPreviews(textEvent(URL), encrypt = false).previews()!![0]

        preview["og:image"] shouldBeEqualTo UPLOADED_MXC
        preview["matrix:matched_url"] shouldBeEqualTo URL
    }

    @Test
    fun `the text of a preview is still bundled when its image cannot be uploaded`() = runTest {
        coEvery { fileUploader.uploadByteArray(any(), any(), any(), any()) } throws IllegalStateException("upload failed")

        val preview = bundler.bundleUrlPreviews(textEvent(URL), encrypt = false).previews()!![0]

        preview["og:title"] shouldBeEqualTo "Matrix.org"
        preview["og:image"].shouldBeNull()
        preview["matrix:image:size"].shouldBeNull()
    }

    @Test
    fun `a preview nobody could display is not bundled, so no one takes it as an invitation to ask their server`() = runTest {
        coEvery { urlPreviewFetcher.fetch(any()) } returns FetchedPreview(fields = mapOf("og:url" to URL), image = null)
        val event = textEvent(URL)

        bundler.bundleUrlPreviews(event, encrypt = false) shouldBeEqualTo event
    }
}
