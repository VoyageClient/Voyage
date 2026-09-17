/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.random.Random

private const val ROOM_ID = "!room:hs"
private const val HOUR = 60 * 60 * 1000L
private const val GAP_MS = 24 * HOUR

/** Seeded interleavings check unique event retention, nonoverlapping ranges and internal gap splitting. */
@RunWith(RobolectricTestRunner::class)
class RangeInvariantFuzzTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var stores: SessionStores

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = SessionSqlDatabase.Schema)
        stores = SessionStores(SessionSqlDatabase(driver))
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `randomised histories always normalise to disjoint contiguous ranges`() {
        repeat(40) { seed ->
            setUpFresh()
            val random = Random(seed)
            val written = LinkedHashSet<String>()
            val live = stores.chunk.insert(ROOM_ID, "live-prev", null, true, false, null, false)
            var event = 0
            var clock = 1000 * HOUR

            repeat(12) { step ->
                when (random.nextInt(4)) {
                    // A live batch: a few events at the top.
                    0 -> repeat(1 + random.nextInt(3)) {
                        clock += random.nextLong(1, 4) * HOUR
                        add(liveRange(), "\$e${event++}", clock).also { written += it }
                    }
                    // A limited sync: the live range is demoted and a new one started, both then growing
                    // over the same period — the shape that stranded a range with no shared event.
                    1 -> {
                        stores.chunk.setLastForward(liveRange(), false)
                        val fresh = stores.chunk.insert(ROOM_ID, "prev-$step", null, true, false, null, false)
                        repeat(1 + random.nextInt(3)) {
                            clock += random.nextLong(1, 3) * HOUR
                            add(fresh, "\$e${event++}", clock).also { written += it }
                        }
                    }
                    // A page fetched into its own range, adjacent or overlapping in time.
                    2 -> {
                        val page = stores.chunk.insert(ROOM_ID, "page-$step", null, false, false, null, false)
                        val base = clock - random.nextLong(0, 6) * HOUR
                        repeat(1 + random.nextInt(4)) { i ->
                            add(page, "\$e${event++}", base + i * HOUR).also { written += it }
                        }
                    }
                    // A jump: a /context island far from everything else.
                    else -> {
                        val island = stores.chunk.insert(ROOM_ID, null, null, false, false, null, false)
                        val far = clock - (30 + random.nextLong(0, 400)) * 24 * HOUR
                        repeat(1 + random.nextInt(2)) { i ->
                            add(island, "\$e${event++}", far + i * HOUR).also { written += it }
                        }
                    }
                }

                stores.chunk.normaliseRanges(ROOM_ID, GAP_MS)
                assertInvariants(seed, step, written)
                // Running it again must change nothing.
                stores.chunk.normaliseRanges(ROOM_ID, GAP_MS) shouldBeEqualTo 0
                assertInvariants(seed, step, written)
            }
            require(live > 0)
        }
    }

    private fun assertInvariants(seed: Int, step: Int, written: Set<String>) {
        val where = "seed=$seed step=$step"
        val ranges = stores.chunk.getByRoom(ROOM_ID)
        val rows = ranges.associate { it.id to stores.timelineEvent.getByChunk(it.id) }

        // Nothing lost, nothing duplicated.
        val stored = rows.values.flatten().map { it.eventId }
        stored.sorted() shouldBeEqualTo written.sorted()
        stored.size shouldBeEqualTo written.size

        // Exactly one live range.
        ranges.count { it.is_last_forward != 0L } shouldBeEqualTo 1

        // Each range contiguous.
        rows.forEach { (id, events) ->
            val hole = events.zipWithNext().firstOrNull { (newer, older) -> newer.ts - older.ts > GAP_MS }
            check(hole == null) { "$where: range #$id spans a hole of ${(hole!!.first.ts - hole.second.ts) / HOUR}h" }
        }

        // No two ranges over one period.
        val spans = rows.filterValues { it.isNotEmpty() }.mapValues { (_, e) -> e.minOf { it.ts } to e.maxOf { it.ts } }
        spans.entries.sortedBy { it.value.first }.zipWithNext().forEach { (a, b) ->
            check(!(a.value.first < b.value.second && b.value.first < a.value.second)) {
                "$where: #${a.key} ${a.value} and #${b.key} ${b.value} cover the same period"
            }
        }
    }

    private fun liveRange() = stores.chunk.lastForward(ROOM_ID)!!.id

    private fun setUpFresh() {
        driver.close()
        setUp()
    }

    private fun add(chunkId: Long, eventId: String, ts: Long): String {
        val entity = EventEntity(eventId = eventId, roomId = ROOM_ID, type = EventType.MESSAGE, sender = "@a:hs", originServerTs = ts)
        val dbId = stores.event.insert(entity)
        stores.timelineWriter.addTimelineEvent(
                chunkId = chunkId, roomId = ROOM_ID, eventDbId = dbId, event = entity, isLastForward = false,
        )
        return eventId
    }
}
