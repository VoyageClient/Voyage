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
import org.matrix.android.sdk.api.session.events.model.UnsignedData
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.room.model.EditAggregatedSummary
import org.matrix.android.sdk.api.session.room.model.EventAnnotationsSummary
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

    private fun event(
            body: String,
            edit: Pair<String, Long>? = null,
            eventId: String = "\$event",
            transactionId: String? = null,
    ) = TimelineEvent(
            root = Event(
                    type = EventType.MESSAGE,
                    eventId = eventId,
                    content = MessageTextContent(msgType = MessageType.MSGTYPE_TEXT, body = body).toContent(),
                    unsignedData = transactionId?.let { UnsignedData(age = null, transactionId = it) },
            ),
            localId = 1L,
            eventId = eventId,
            senderInfo = SenderInfo("@alice:example.org", "Alice", true, null),
            annotations = edit?.let { (id, ts) ->
                EventAnnotationsSummary(
                        editSummary = EditAggregatedSummary(
                                latestEdit = Event(type = EventType.MESSAGE, eventId = id, originServerTs = ts),
                                sourceEvents = listOf(id),
                                localEchos = emptyList(),
                                lastEditTs = ts,
                        )
                )
            },
    )

    private fun translate(event: TimelineEvent) = runBlocking {
        withTimeout(5_000) {
            val done = launch(start = CoroutineStart.UNDISPATCHED) { store.updates.first() }
            store.translate(event.eventId, MessageTranslationStore.sourceOf(event), "Hallo")
            done.join()
        }
    }

    @Test
    fun `a translated local echo stays translated once synced`() {
        translate(event("Hallo", eventId = LOCAL_ID, transactionId = LOCAL_ID))
        val synced = event("Hallo", eventId = "\$remote", transactionId = LOCAL_ID)
        store.get(synced)?.text shouldBeEqualTo "Hello"
        store.get("\$remote")?.text shouldBeEqualTo "Hello"
        store.get(LOCAL_ID)?.text shouldBeEqualTo "Hello"
    }

    @Test
    fun `untranslating by the local echo id after the sync removes it`() {
        translate(event("Hallo", eventId = LOCAL_ID, transactionId = LOCAL_ID))
        store.get(event("Hallo", eventId = "\$remote", transactionId = LOCAL_ID))
        store.untranslate(LOCAL_ID)
        store.get("\$remote").shouldBeNull()
    }

    @Test
    fun `a translation finishing after the sync lands on the synced id`() {
        coEvery { client.translate(any(), any(), any()) } coAnswers {
            store.get(event("Hallo", eventId = "\$remote", transactionId = LOCAL_ID)).shouldBeNull()
            TranslationResult.Success("Hello", "de")
        }
        translate(event("Hallo", eventId = LOCAL_ID, transactionId = LOCAL_ID))
        store.get("\$remote")?.text shouldBeEqualTo "Hello"
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
        store.get(event("Tschüss", edit = "\$edit" to 2_000L)).shouldBeNull()
        store.get("\$event").shouldBeNull()
    }

    @Test
    fun `a newer edit drops a translation of an earlier edit`() {
        translate(event("Hallo", edit = "\$edit1" to 1_000L))
        store.get(event("Tschüss", edit = "\$edit2" to 2_000L)).shouldBeNull()
    }

    @Test
    fun `a copy without the edit aggregated keeps the translation`() {
        translate(event("Hallo", edit = "\$edit" to 1_000L))
        store.get(event("Hallo"))?.text shouldBeEqualTo "Hello"
        store.get(event("Hallo", edit = "\$edit" to 1_000L))?.text shouldBeEqualTo "Hello"
    }

    @Test
    fun `an undecrypted edit body does not count as an edit`() {
        // Same edit id, body differs because one copy hasn't decrypted the edit yet.
        translate(event("Hallo", edit = "\$edit" to 1_000L))
        store.get(event("Hallo welt", edit = "\$edit" to 1_000L))?.text shouldBeEqualTo "Hello"
    }

    @Test
    fun `a view behind on edits keeps the translation`() {
        translate(event("Hallo", edit = "\$edit2" to 2_000L))
        store.get(event("Hi", edit = "\$edit1" to 1_000L))?.text shouldBeEqualTo "Hello"
    }

    private companion object {
        const val LOCAL_ID = "\$local.1234"
    }
}
