/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.url

import im.vector.app.core.resources.BuildMeta
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.UnsignedData
import org.matrix.android.sdk.api.session.media.BundledUrlPreview
import org.matrix.android.sdk.api.session.media.MediaService
import org.matrix.android.sdk.api.session.media.PreviewUrlData
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent

private const val AN_EVENT_ID = "\$event"
private const val A_TRANSACTION_ID = "txn-1"
private const val A_ROOM_ID = "!room:example.org"
private const val URL = "https://matrix.org"
private const val ANOTHER_URL = "https://element.io"

private val BUNDLED_DATA = PreviewUrlData(
        url = URL,
        siteName = "Matrix.org",
        title = "Bundled",
        description = null,
        mxcUrl = null,
        imageWidth = null,
        imageHeight = null
)

private val SERVER_DATA = BUNDLED_DATA.copy(title = "From the homeserver")

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewUrlRetrieverTest {

    private val mediaService = mockk<MediaService>()
    private val session = mockk<Session> { every { mediaService() } returns mediaService }
    private val buildMeta = mockk<BuildMeta> { every { isDebug } returns false }

    private lateinit var retriever: PreviewUrlRetriever

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        retriever = PreviewUrlRetriever(session, CoroutineScope(Dispatchers.Unconfined), buildMeta)
        every { mediaService.extractUrls(any()) } returns listOf(URL)
        every { mediaService.extractBundledUrlPreviews(any()) } returns null
        coEvery { mediaService.getPreviewUrl(any(), any(), any()) } returns SERVER_DATA
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun anEvent(eventId: String = AN_EVENT_ID, sending: Boolean = false, transactionId: String? = null) = TimelineEvent(
            root = Event(
                    type = EventType.MESSAGE,
                    eventId = eventId,
                    roomId = A_ROOM_ID,
                    content = mapOf("body" to URL),
                    unsignedData = transactionId?.let { UnsignedData(age = null, transactionId = it) },
            ).also { it.sendState = if (sending) SendState.SENDING else SendState.SYNCED },
            localId = 1L,
            eventId = eventId,
            displayIndex = 0,
            senderInfo = SenderInfo("@alice:example.org", null, true, null)
    )

    private fun givenBundled(vararg previews: BundledUrlPreview) {
        every { mediaService.extractBundledUrlPreviews(any()) } returns previews.toList()
    }

    private fun stateOf(event: TimelineEvent): PreviewUrlUiState {
        var state: PreviewUrlUiState = PreviewUrlUiState.Unknown
        retriever.addListener(event.eventId) { state = it }
        return state
    }

    private fun retrieve(event: TimelineEvent = anEvent(), allowServerFetch: Boolean = true): PreviewUrlUiState {
        retriever.getPreviewUrl(event, allowServerFetch)
        return stateOf(event)
    }

    @Test
    fun `a bundled preview is displayed without asking the homeserver`() = runTest {
        givenBundled(BundledUrlPreview(URL, BUNDLED_DATA))

        val state = retrieve()

        state shouldBeEqualTo PreviewUrlUiState.Data(AN_EVENT_ID, URL, BUNDLED_DATA)
        coVerify(exactly = 0) { mediaService.getPreviewUrl(any(), any(), any()) }
    }

    @Test
    fun `a bundled preview is displayed even when previews may not be requested`() = runTest {
        givenBundled(BundledUrlPreview(URL, BUNDLED_DATA))

        retrieve(allowServerFetch = false) shouldBeEqualTo PreviewUrlUiState.Data(AN_EVENT_ID, URL, BUNDLED_DATA)
    }

    @Test
    fun `an empty bundle means the sender wants no preview at all`() = runTest {
        givenBundled()

        retrieve() shouldBeEqualTo PreviewUrlUiState.NoUrl
        coVerify(exactly = 0) { mediaService.getPreviewUrl(any(), any(), any()) }
    }

    @Test
    fun `a bundled url without data falls back to the homeserver`() = runTest {
        givenBundled(BundledUrlPreview(ANOTHER_URL, null))

        retrieve() shouldBeEqualTo PreviewUrlUiState.Data(AN_EVENT_ID, ANOTHER_URL, SERVER_DATA)
        coVerify { mediaService.getPreviewUrl(ANOTHER_URL, any(), any()) }
    }

    @Test
    fun `a bundled url without data is not previewed when the homeserver may not be asked`() = runTest {
        givenBundled(BundledUrlPreview(URL, null))

        retrieve(allowServerFetch = false) shouldBeEqualTo PreviewUrlUiState.NoUrl
        coVerify(exactly = 0) { mediaService.getPreviewUrl(any(), any(), any()) }
    }

    @Test
    fun `the first bundled preview which carries data wins`() = runTest {
        givenBundled(BundledUrlPreview(ANOTHER_URL, null), BundledUrlPreview(URL, BUNDLED_DATA))

        retrieve() shouldBeEqualTo PreviewUrlUiState.Data(AN_EVENT_ID, URL, BUNDLED_DATA)
        coVerify(exactly = 0) { mediaService.getPreviewUrl(any(), any(), any()) }
    }

    @Test
    fun `a message which bundles nothing is previewed by the homeserver`() = runTest {
        retrieve() shouldBeEqualTo PreviewUrlUiState.Data(AN_EVENT_ID, URL, SERVER_DATA)
        coVerify { mediaService.getPreviewUrl(URL, any(), any()) }
    }

    @Test
    fun `a message which bundles nothing is not previewed when the homeserver may not be asked`() = runTest {
        retrieve(allowServerFetch = false) shouldBeEqualTo PreviewUrlUiState.NoUrl
        coVerify(exactly = 0) { mediaService.getPreviewUrl(any(), any(), any()) }
    }

    @Test
    fun `a bundled preview of a blocked domain is not displayed`() = runTest {
        val permalink = "https://matrix.to/#/@alice:example.org"
        givenBundled(BundledUrlPreview(permalink, BUNDLED_DATA.copy(url = permalink)))

        retrieve() shouldBeEqualTo PreviewUrlUiState.NoUrl
    }

    @Test
    fun `a preview the user closed is not displayed again`() = runTest {
        givenBundled(BundledUrlPreview(URL, BUNDLED_DATA))
        retriever.doNotShowPreviewUrlFor(AN_EVENT_ID, URL)

        retrieve() shouldBeEqualTo PreviewUrlUiState.NoUrl
    }

    @Test
    fun `closing a displayed preview hides it`() = runTest {
        givenBundled(BundledUrlPreview(URL, BUNDLED_DATA))
        val event = anEvent()
        var state: PreviewUrlUiState = PreviewUrlUiState.Unknown
        retriever.addListener(AN_EVENT_ID) { state = it }
        retriever.getPreviewUrl(event, allowServerFetch = true)

        retriever.doNotShowPreviewUrlFor(AN_EVENT_ID, URL)

        state shouldBeEqualTo PreviewUrlUiState.NoUrl
    }

    @Test
    fun `a message still being sent is not handed to the homeserver, its own preview is coming`() = runTest {
        retrieve(anEvent(sending = true))

        coVerify(exactly = 0) { mediaService.getPreviewUrl(any(), any(), any()) }
    }

    @Test
    fun `a request which lands after the event moved on does not overwrite what replaced it`() = runTest {
        // Both are the same message — a local echo and the remote event which replaced it, which is why
        // they share a transaction id and so a key — but the second carries a bundled preview.
        val echo = anEvent(eventId = "\$local.echo", transactionId = A_TRANSACTION_ID)
        val remote = anEvent(eventId = "\$remote", transactionId = A_TRANSACTION_ID)
        val serverPreview = CompletableDeferred<PreviewUrlData>()
        coEvery { mediaService.getPreviewUrl(any(), any(), any()) } coAnswers { serverPreview.await() }

        retriever.getPreviewUrl(echo, allowServerFetch = true)
        givenBundled(BundledUrlPreview(URL, BUNDLED_DATA))
        retriever.getPreviewUrl(remote, allowServerFetch = true)
        serverPreview.completeExceptionally(IllegalStateException("no preview"))

        var state: PreviewUrlUiState = PreviewUrlUiState.Unknown
        retriever.addListener(A_TRANSACTION_ID) { state = it }
        state shouldBeEqualTo PreviewUrlUiState.Data(A_TRANSACTION_ID, URL, BUNDLED_DATA)
    }

    @Test
    fun `a failing request is reported as an error`() = runTest {
        coEvery { mediaService.getPreviewUrl(any(), any(), any()) } throws IllegalStateException("no preview")

        retrieve() shouldBeInstanceOf PreviewUrlUiState.Error::class
    }

    @Test
    fun `a listener which binds later is given the state which was already resolved`() = runTest {
        givenBundled(BundledUrlPreview(URL, BUNDLED_DATA))
        retriever.getPreviewUrl(anEvent(), allowServerFetch = true)

        var state: PreviewUrlUiState = PreviewUrlUiState.Unknown
        retriever.addListener(AN_EVENT_ID) { state = it }

        state shouldBeEqualTo PreviewUrlUiState.Data(AN_EVENT_ID, URL, BUNDLED_DATA)
    }

    @Test
    fun `a removed listener is not notified anymore`() = runTest {
        givenBundled(BundledUrlPreview(URL, BUNDLED_DATA))
        var state: PreviewUrlUiState = PreviewUrlUiState.Unknown
        val listener = listener { state = it }
        retriever.addListener(AN_EVENT_ID, listener)
        retriever.removeListener(AN_EVENT_ID, listener)

        retriever.getPreviewUrl(anEvent(), allowServerFetch = true)

        state shouldBeEqualTo PreviewUrlUiState.Unknown
    }

    @Test
    fun `the same event is not previewed twice`() = runTest {
        val event = anEvent()
        retriever.getPreviewUrl(event, allowServerFetch = true)
        retriever.getPreviewUrl(event, allowServerFetch = true)

        coVerify(exactly = 1) { mediaService.getPreviewUrl(any(), any(), any()) }
    }

    private fun listener(onState: (PreviewUrlUiState) -> Unit) = object : PreviewUrlRetriever.PreviewUrlRetrieverListener {
        override fun onStateUpdated(state: PreviewUrlUiState) {
            onState(state)
        }
    }

    private fun PreviewUrlRetriever.addListener(key: String, onState: (PreviewUrlUiState) -> Unit) {
        addListener(key, listener(onState))
    }
}
