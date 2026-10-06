/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.search.index

import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqlDriverFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class EventIndexStoreTest {

    @Test
    fun `clearing an unopened account removes its existing index`() = runBlocking {
        val directory = File(RuntimeEnvironment.getApplication().filesDir, "event-index-unopened-test")
        directory.deleteRecursively()
        directory.mkdirs()
        val databaseFile = File(directory, "event_index.db")
        databaseFile.writeText("old index")
        val store = EventIndexStore(directory, "@user:example.org", FrameworkSqlDriverFactory(RuntimeEnvironment.getApplication()))
        try {
            store.clear()
            databaseFile.exists() shouldBeEqualTo false
        } finally {
            store.onSessionReleased()
        }
        Unit
    }

    @Test
    fun `clearing a populated index reclaims its disk space`() = runBlocking {
        val directory = File(RuntimeEnvironment.getApplication().filesDir, "event-index-clear-test")
        directory.deleteRecursively()
        directory.mkdirs()
        val store = EventIndexStore(directory, "@user:example.org", FrameworkSqlDriverFactory(RuntimeEnvironment.getApplication()))
        try {
            val payload = "x".repeat(4096)
            store.addEvents((1..500).map { number ->
                IndexableEvent(
                        eventId = "\$event$number",
                        roomId = "!room:example.org",
                        sender = "@user:example.org",
                        originServerTs = number.toLong(),
                        contentText = payload,
                        eventJson = payload,
                        msgtype = "m.text",
                        mentions = null,
                )
            })
            val databaseFile = File(directory, "event_index.db")
            val before = databaseFile.length() + File(directory, "event_index.db-wal").length()

            store.clear()

            store.getStats().first shouldBeEqualTo 0L
            val after = databaseFile.length() + File(directory, "event_index.db-wal").length()
            check(after < before / 4) { "Index still occupies $after of $before bytes" }
        } finally {
            store.onSessionReleased()
        }
        Unit
    }

    @Test
    fun `uploads pages attachments newest first by keyset, above the floor`() = withStore("event-index-uploads-test") { store ->
        store.addEvents(listOf(
                indexable("\$text", ts = 600, msgtype = "m.text"),
                indexable("\$image", ts = 500, msgtype = "m.image"),
                indexable("\$tieB", ts = 400, msgtype = "m.video"),
                indexable("\$tieA", ts = 400, msgtype = "m.file"),
                indexable("\$gallery", ts = 300, msgtype = "m.gallery m.image"),
                indexable("\$sticker", ts = 200, msgtype = "m.sticker"),
                indexable("\$redacted", ts = 150, msgtype = null),
                indexable("\$audio", ts = 100, msgtype = "m.audio"),
                indexable("\$other", ts = 450, msgtype = "m.image", roomId = "!other:example.org"),
        ))

        val first = store.uploads(ROOM, Long.MAX_VALUE, "", Long.MIN_VALUE, 3)
        first.map { it.event_id } shouldBeEqualTo listOf("\$image", "\$tieB", "\$tieA")

        val last = first.last()
        store.uploads(ROOM, last.origin_server_ts, last.event_id, Long.MIN_VALUE, 10).map { it.event_id } shouldBeEqualTo
                listOf("\$gallery", "\$sticker", "\$audio")

        store.uploads(ROOM, Long.MAX_VALUE, "", 300, 10).map { it.event_id } shouldBeEqualTo
                listOf("\$image", "\$tieB", "\$tieA", "\$gallery")
    }

    @Test
    fun `the crawl frontier only ever moves back in time`() = withStore("event-index-frontier-test") { store ->
        store.getCrawlFrontier(ROOM) shouldBeEqualTo null
        store.lowerCrawlFrontier(ROOM, 500)
        store.lowerCrawlFrontier(ROOM, 700)
        store.getCrawlFrontier(ROOM) shouldBeEqualTo 500L
        store.lowerCrawlFrontier(ROOM, 200)
        store.getCrawlFrontier(ROOM) shouldBeEqualTo 200L
    }

    private fun withStore(name: String, block: suspend (EventIndexStore) -> Unit) = runBlocking {
        val directory = File(RuntimeEnvironment.getApplication().filesDir, name)
        directory.deleteRecursively()
        directory.mkdirs()
        val store = EventIndexStore(directory, "@user:example.org", FrameworkSqlDriverFactory(RuntimeEnvironment.getApplication()))
        try {
            block(store)
        } finally {
            store.onSessionReleased()
        }
    }

    private fun indexable(eventId: String, ts: Long, msgtype: String?, roomId: String = ROOM) = IndexableEvent(
            eventId = eventId,
            roomId = roomId,
            sender = "@user:example.org",
            originServerTs = ts,
            contentText = "",
            eventJson = "{}",
            msgtype = msgtype,
            mentions = null,
    )

    private companion object {
        const val ROOM = "!room:example.org"
    }
}
