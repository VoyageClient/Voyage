/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.redaction.preservation

import im.vector.app.core.di.ActiveSessionHolder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.crypto.model.OlmDecryptionResult
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.UnsignedData
import org.matrix.android.sdk.api.session.events.model.isRedacted
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent

private const val A_ROOM_ID = "!room:example.org"
private const val AN_EVENT_ID = "\$event"
private const val ME = "@alice:example.org"
private const val SOMEONE_ELSE = "@bob:example.org"

class RedactedContentRestorerTest {

    private val revealManager = mockk<RedactedContentRevealManager>(relaxed = true)
    private val repository = mockk<RedactedContentRepository>(relaxed = true)
    private val session = mockk<Session> { every { myUserId } returns ME }
    private val activeSessionHolder = mockk<ActiveSessionHolder> { every { getSafeActiveSession() } returns session }

    private val restorer = RedactedContentRestorer(
            revealManager = revealManager,
            repository = repository,
            activeSessionHolder = { activeSessionHolder },
    )

    private fun timelineEvent(
            type: String = EventType.MESSAGE,
            content: Map<String, Any>? = null,
            unsignedData: UnsignedData? = null,
            stateKey: String? = null,
            senderId: String = SOMEONE_ELSE,
            decryptionResult: OlmDecryptionResult? = null,
    ): TimelineEvent {
        val root = Event(
                type = type,
                eventId = AN_EVENT_ID,
                roomId = A_ROOM_ID,
                senderId = senderId,
                stateKey = stateKey,
                content = content,
                unsignedData = unsignedData,
        ).also { it.mxDecryptionResult = decryptionResult }
        return TimelineEvent(
                root = root,
                localId = 1L,
                eventId = AN_EVENT_ID,
                displayIndex = 0,
                senderInfo = SenderInfo(senderId, null, true, null),
        )
    }

    private fun redactedByEvent() = UnsignedData(age = null, redactedEvent = Event(type = EventType.REDACTION))

    private fun preserve(content: Map<String, Any>, clearType: String? = null) {
        every { repository.cachedContent(AN_EVENT_ID) } returns RedactedContentRepository.PreservedBody(content, clearType)
    }

    private fun revealed(value: Boolean) {
        every { revealManager.isRevealed(A_ROOM_ID, AN_EVENT_ID, any()) } returns value
    }

    @Test
    fun `given the event is not redacted, then nothing is restored`() {
        restorer.restoreEvent(timelineEvent(content = mapOf("body" to "hi"))).shouldBeNull()
    }

    @Test
    fun `given a redacted state event, then nothing is restored`() {
        revealed(true)
        preserve(mapOf("body" to "hi"))

        restorer.restoreEvent(
                timelineEvent(type = EventType.STATE_ROOM_NAME, stateKey = "", unsignedData = redactedByEvent())
        ).shouldBeNull()
    }

    @Test
    fun `given the message is not revealed, then nothing is restored`() {
        revealed(false)
        preserve(mapOf("body" to "hi"))

        restorer.restoreEvent(timelineEvent(unsignedData = redactedByEvent())).shouldBeNull()
    }

    /** The fetch has to be kicked off from here, or nothing else ever asks for the content. */
    @Test
    fun `given revealed but nothing cached, then a fetch is requested and the tile stays redacted`() {
        revealed(true)
        every { repository.cachedContent(AN_EVENT_ID) } returns null

        restorer.restoreEvent(timelineEvent(unsignedData = redactedByEvent())).shouldBeNull()

        verify { repository.requestContent(A_ROOM_ID, AN_EVENT_ID) }
    }

    @Test
    fun `given a preserved copy, then the content is put back`() {
        revealed(true)
        preserve(mapOf("body" to "the original"))

        val restored = restorer.restoreEvent(timelineEvent(unsignedData = redactedByEvent())).shouldNotBeNull()

        restored.root.content?.get("body") shouldBeEqualTo "the original"
    }

    /**
     * isRedacted() is an OR over redacted_because and redacted_by, and Synapse sets the latter alone
     * whenever it can't inline the redaction event — clearing only one leaves the row a placeholder.
     */
    @Test
    fun `given a restore, then both redaction markers are cleared`() {
        revealed(true)
        preserve(mapOf("body" to "the original"))
        val unsigned = UnsignedData(age = null, redactedEvent = Event(type = EventType.REDACTION), redactedBy = "\$redaction")

        val restored = restorer.restoreEvent(timelineEvent(unsignedData = unsigned)).shouldNotBeNull()

        restored.root.unsignedData?.redactedEvent.shouldBeNull()
        restored.root.unsignedData?.redactedBy.shouldBeNull()
        restored.root.isRedacted() shouldBe false
    }

    /**
     * A redacted encrypted event keeps type m.room.encrypted with its decryption result nulled, so
     * without restoring the type getClearType() keeps routing it to the undecryptable renderer.
     */
    @Test
    fun `given an encrypted event, then the pre-redaction type is restored and the decryption result dropped`() {
        revealed(true)
        preserve(mapOf("body" to "the original"), clearType = EventType.MESSAGE)
        val event = timelineEvent(
                type = EventType.ENCRYPTED,
                unsignedData = redactedByEvent(),
                decryptionResult = OlmDecryptionResult(payload = mapOf("type" to EventType.MESSAGE)),
        )

        val restored = restorer.restoreEvent(event).shouldNotBeNull()

        restored.root.type shouldBeEqualTo EventType.MESSAGE
        restored.root.mxDecryptionResult.shouldBeNull()
    }

    @Test
    fun `given an empty preserved clear type, then the event keeps its own type`() {
        revealed(true)
        preserve(mapOf("body" to "the original"), clearType = "")

        val restored = restorer.restoreEvent(timelineEvent(unsignedData = redactedByEvent())).shouldNotBeNull()

        restored.root.type shouldBeEqualTo EventType.MESSAGE
    }

    @Test
    fun `given an own message, then the reveal check is told so`() {
        every { revealManager.isRevealed(A_ROOM_ID, AN_EVENT_ID, isOwnMessage = true) } returns false

        restorer.restoreEvent(timelineEvent(senderId = ME, unsignedData = redactedByEvent())).shouldBeNull()

        verify { revealManager.isRevealed(A_ROOM_ID, AN_EVENT_ID, isOwnMessage = true) }
    }

    /** isShowingRestoredContent feeds grouping and visibility passes, so it must not start a fetch. */
    @Test
    fun `given isShowingRestoredContent, then it never requests content`() {
        revealed(true)
        every { repository.cachedContent(AN_EVENT_ID) } returns null

        restorer.isShowingRestoredContent(timelineEvent(unsignedData = redactedByEvent())) shouldBe false

        verify(exactly = 0) { repository.requestContent(any(), any()) }
    }

    @Test
    fun `given a revealed and cached event, then isShowingRestoredContent is true`() {
        revealed(true)
        preserve(mapOf("body" to "the original"))

        restorer.isShowingRestoredContent(timelineEvent(unsignedData = redactedByEvent())) shouldBe true
    }

    /**
     * The red mark means "you are reading content a redaction took", so everything that paints it — the
     * timeline row, the reply band, the long-press sheet, the composer preview — keys off a *successful*
     * restore. A bare removed-message placeholder must never be marked.
     */
    @Test
    fun `given a redaction that is not revealed, then nothing marks it as restored`() {
        revealed(false)
        preserve(mapOf("body" to "the original"))

        val event = timelineEvent(unsignedData = redactedByEvent())

        restorer.isShowingRestoredContent(event) shouldBe false
        restorer.restoreEvent(event).shouldBeNull()
    }

    /** A hidden reveal has to drop the mark again, not just stop adding it. */
    @Test
    fun `given a revealed event that is hidden again, then it stops counting as restored`() {
        revealed(true)
        preserve(mapOf("body" to "the original"))
        val event = timelineEvent(unsignedData = redactedByEvent())
        restorer.isShowingRestoredContent(event) shouldBe true

        revealed(false)

        restorer.isShowingRestoredContent(event) shouldBe false
        restorer.restoreEvent(event).shouldBeNull()
    }
}
