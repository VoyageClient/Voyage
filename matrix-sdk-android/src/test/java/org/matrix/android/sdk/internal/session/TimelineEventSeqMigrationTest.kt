/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session

import app.cash.sqldelight.db.QueryResult
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TimelineEventSeqMigrationTest {

    private lateinit var driver: FrameworkSqliteDriver

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = SessionSqlDatabase.Schema)
        // The table as databases shipped before seq existed.
        driver.execute(null, "DROP TABLE timeline_event", 0)
        driver.execute(
                null,
                """
                CREATE TABLE timeline_event (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    local_id INTEGER NOT NULL DEFAULT 0,
                    event_id TEXT NOT NULL DEFAULT '',
                    room_id TEXT NOT NULL DEFAULT '',
                    chunk_id INTEGER,
                    ts INTEGER NOT NULL DEFAULT 0,
                    root_event_db_id INTEGER,
                    sender_name TEXT,
                    is_unique_display_name INTEGER NOT NULL DEFAULT 0,
                    sender_avatar TEXT,
                    sender_membership_event_id TEXT,
                    owned_by_thread_chunk INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
                0,
        )
        driver.execute(null, "CREATE INDEX timeline_event_chunk_order ON timeline_event(chunk_id, ts, event_id)", 0)
        driver.execute(null, "CREATE INDEX timeline_event_room_order ON timeline_event(room_id, ts, event_id)", 0)
        listOf("\$zzzCreate", "\$mmmMember", "\$aaaPowerLevels").forEach { eventId ->
            driver.execute(null, "INSERT INTO timeline_event(event_id, room_id, chunk_id, ts) VALUES ('$eventId', '!r:hs', 1, 1000)", 0)
        }
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `existing rows are ordered by when they were stored and the old indexes are replaced`() {
        SessionModule.addTimelineEventSeq(driver)

        SessionSqlDatabase(driver).timelineEventQueries.selectByChunk(1).executeAsList().map { it.event_id } shouldBeEqualTo
                listOf("\$aaaPowerLevels", "\$mmmMember", "\$zzzCreate")
        indexes() shouldBeEqualTo listOf("timeline_event_chunk_seq_order", "timeline_event_room_seq_order", "timeline_event_seq")
    }

    @Test
    fun `running it again changes nothing`() {
        SessionModule.addTimelineEventSeq(driver)
        driver.execute(null, "UPDATE timeline_event SET seq = 100 WHERE event_id = '\$zzzCreate'", 0)

        SessionModule.addTimelineEventSeq(driver)

        SessionSqlDatabase(driver).timelineEventQueries.selectByChunk(1).executeAsList().first().event_id shouldBeEqualTo "\$zzzCreate"
    }

    private fun indexes(): List<String> = driver.executeQuery(
            null,
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'timeline_event' " +
                    "AND (name LIKE '%order%' OR name = 'timeline_event_seq') ORDER BY name",
            { cursor ->
                val names = ArrayList<String>()
                while (cursor.next().value) names.add(cursor.getString(0)!!)
                QueryResult.Value(names)
            },
            0,
    ).value
}
