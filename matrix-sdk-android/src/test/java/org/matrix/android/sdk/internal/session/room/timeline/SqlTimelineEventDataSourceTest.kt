/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import dagger.Lazy
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.crypto.model.OlmDecryptionResult
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.internal.database.mapper.TimelineEventMapper
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.test.fakes.FakeSessionDatabase
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
internal class SqlTimelineEventDataSourceTest {

    private val roomId = "!room:example.org"
    private val eventId = "\$encrypted-message"

    @Test
    fun `an open event flow refreshes on room decryption without a timeline write`() = runTest {
        val db = FakeSessionDatabase()
        val eventRow = insertEncryptedEvent(db)
        val signal = TimelineDecryptionSignal()
        val source = source(db, StandardTestDispatcher(testScheduler), signal)
        val types = mutableListOf<String>()
        val collector = backgroundScope.launch {
            source.getTimelineEventFlow(roomId, eventId).collect { types.add(it.get().root.getClearType()) }
        }
        try {
            runCurrent()
            assertEquals(listOf(EventType.ENCRYPTED), types)

            persistDecryption(db, eventRow)
            runCurrent()
            assertEquals(listOf(EventType.ENCRYPTED), types)

            signal.onDecrypted("!other:example.org")
            runCurrent()
            assertEquals(listOf(EventType.ENCRYPTED), types)

            signal.onDecrypted(roomId)
            runCurrent()
            assertEquals(listOf(EventType.ENCRYPTED, EventType.MESSAGE), types)
        } finally {
            collector.cancel()
            runCurrent()
            db.close()
        }
    }

    @Test
    fun `a collector started after decryption reads the persisted plaintext immediately`() = runTest {
        val db = FakeSessionDatabase()
        try {
            val eventRow = insertEncryptedEvent(db)
            val signal = TimelineDecryptionSignal()
            val source = source(db, StandardTestDispatcher(testScheduler), signal)
            assertEquals(EventType.ENCRYPTED, source.getTimelineEvent(roomId, eventId)!!.root.getClearType())

            persistDecryption(db, eventRow)
            signal.onDecrypted(roomId)

            val event = source.getTimelineEventFlow(roomId, eventId).first().get()
            assertEquals(EventType.MESSAGE, event.root.getClearType())
            assertEquals("Hello", event.root.getClearContent()?.get("body"))
        } finally {
            db.close()
        }
    }

    private fun source(db: FakeSessionDatabase, dispatcher: CoroutineDispatcher, signal: TimelineDecryptionSignal) =
            SqlTimelineEventDataSource(
                    database = db.database,
                    dispatcher = dispatcher,
                    stores = db.stores,
                    timelineEventMapper = TimelineEventMapper(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)),
                    localEchoRepository = Lazy { mockk() },
                    decryptionSignal = signal,
            )

    private fun insertEncryptedEvent(db: FakeSessionDatabase): Long {
        val eventRow = db.stores.event.insert(EventEntity(
                eventId = eventId,
                roomId = roomId,
                type = EventType.ENCRYPTED,
                content = "{}",
                sender = "@alice:example.org",
        ))
        db.stores.timelineEvent.insert(
                TimelineEventEntity(eventId = eventId, roomId = roomId, localId = 1),
                chunkId = null,
                rootEventDbId = eventRow,
        )
        return eventRow
    }

    private fun persistDecryption(db: FakeSessionDatabase, eventRow: Long) {
        db.stores.event.setDecryptionResult(
                id = eventRow,
                result = OlmDecryptionResult(payload = mapOf(
                        "type" to EventType.MESSAGE,
                        "content" to mapOf("msgtype" to "m.text", "body" to "Hello"),
                )),
                errorCode = null,
                errorReason = null,
                contentJson = "{}",
        )
    }
}
