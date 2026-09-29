/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.translation

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.room.model.message.MessageTextContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MessageTranslationStoreTest {

    private val client = mockk<TranslationClient> {
        coEvery { translate(any(), any(), any()) } answers { TranslationResult.Success("Hello", "de") }
    }

    private val store = MessageTranslationStore(client, mockk(relaxed = true))

    private fun event(body: String) = TimelineEvent(
            root = Event(
                    type = EventType.MESSAGE,
                    eventId = "\$event",
                    content = MessageTextContent(msgType = MessageType.MSGTYPE_TEXT, body = body).toContent()
            ),
            localId = 1L,
            eventId = "\$event",
            senderInfo = SenderInfo("@alice:example.org", "Alice", true, null),
    )

    private fun translate(event: TimelineEvent) = runBlocking {
        withTimeout(5_000) {
            val done = launch(start = CoroutineStart.UNDISPATCHED) { store.updates.first() }
            store.translate(event.eventId, MessageTranslationStore.sourceOf(event), "Hallo")
            done.join()
        }
    }

    @Test
    fun `translation shows while the message is unchanged`() {
        val original = event("Hallo")
        translate(original)
        store.get(original)?.text shouldBeEqualTo "Hello"
    }

    @Test
    fun `an edit drops the translation`() {
        translate(event("Hallo"))
        store.get(event("Tschüss")).shouldBeNull()
        store.get("\$event").shouldBeNull()
    }
}
