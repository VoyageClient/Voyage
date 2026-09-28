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
}
