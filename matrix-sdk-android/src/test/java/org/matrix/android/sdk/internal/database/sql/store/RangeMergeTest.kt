/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBe
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

private const val ROOM_ID = "!room:hs"
private const val HOUR = 60 * 60 * 1000L

@RunWith(RobolectricTestRunner::class)
class RangeMergeTest {

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
    fun `normalizing many overlapping ranges finishes every merge`() {
        repeat(10) { index ->
            val id = range()
            add(id, "start-$index", ts = index.toLong())
            add(id, "end-$index", ts = HOUR + index)
        }

        stores.chunk.normaliseRanges(ROOM_ID, 24 * HOUR)

        stores.chunk.getByRoom(ROOM_ID).size shouldBeEqualTo 1
        eventIds(stores.chunk.getByRoom(ROOM_ID).single().id).size shouldBeEqualTo 20
    }

    @Test
    fun `merging moves every event across and deletes the loser`() {
        val winner = range(prevToken = "w-prev")
        val loser = range(prevToken = "l-prev")
        add(winner, "\$w1", ts = 30 * HOUR)
        add(loser, "\$l1", ts = 10 * HOUR)
        add(loser, "\$l2", ts = 20 * HOUR)

        stores.chunk.mergeInto(winner, loser)

        eventIds(winner) shouldBeEqualTo listOf("\$w1", "\$l2", "\$l1")
        stores.chunk.getById(loser).shouldBeNull()
    }

    @Test
    fun `an event both ranges hold is not duplicated`() {
        val winner = range()
        val loser = range()
        add(winner, "\$shared", ts = 10 * HOUR)
        add(loser, "\$shared", ts = 10 * HOUR)
        add(loser, "\$only", ts = 20 * HOUR)

        stores.chunk.mergeInto(winner, loser)

        eventIds(winner) shouldBeEqualTo listOf("\$only", "\$shared")
    }

    @Test
    fun `the older side's backward frontier survives`() {
        val newer = range(prevToken = "newer-prev")
        val older = range(prevToken = "older-prev")
        add(newer, "\$new", ts = 30 * HOUR)
        add(older, "\$old", ts = 10 * HOUR)

        stores.chunk.mergeInto(newer, older)

        stores.chunk.getById(newer)!!.prev_token shouldBeEqualTo "older-prev"
    }

    @Test
    fun `reaching the room start carries over`() {
        val newer = range(prevToken = "newer-prev")
        val older = range(prevToken = null, reachedStart = true)
        add(newer, "\$new", ts = 30 * HOUR)
        add(older, "\$old", ts = 10 * HOUR)

        stores.chunk.mergeInto(newer, older)

        (stores.chunk.getById(newer)!!.is_last_backward != 0L) shouldBeEqualTo true
    }

    @Test
    fun `a newer side's forward frontier survives into a non-live range`() {
        val older = range(nextToken = "older-next")
        val newer = range(nextToken = "newer-next")
        add(older, "\$old", ts = 10 * HOUR)
        add(newer, "\$new", ts = 30 * HOUR)

        stores.chunk.mergeInto(older, newer)

        stores.chunk.getById(older)!!.next_token shouldBeEqualTo "newer-next"
    }

    /** Whichever way round the caller happens to have them, the surviving history is the same. */
    @Test
    fun `merge is commutative in what it keeps`() {
        val a = range(prevToken = "a-prev")
        val b = range(prevToken = "b-prev")
        add(a, "\$a", ts = 30 * HOUR)
        add(b, "\$b", ts = 10 * HOUR)
        stores.chunk.mergeInto(a, b)
        val oneWay = eventIds(a) to stores.chunk.getById(a)!!.prev_token

        setUpFresh()
        val c = range(prevToken = "a-prev")
        val d = range(prevToken = "b-prev")
        add(c, "\$a", ts = 30 * HOUR)
        add(d, "\$b", ts = 10 * HOUR)
        stores.chunk.mergeInto(d, c)

        eventIds(d) shouldBeEqualTo oneWay.first
        stores.chunk.getById(d)!!.prev_token shouldBeEqualTo oneWay.second
    }

    @Test
    fun `merging the same pair twice changes nothing the second time`() {
        val winner = range()
        val loser = range()
        add(winner, "\$w", ts = 20 * HOUR)
        add(loser, "\$l", ts = 10 * HOUR)

        stores.chunk.mergeInto(winner, loser)
        val after = eventIds(winner)
        stores.chunk.mergeInto(winner, loser)

        eventIds(winner) shouldBeEqualTo after
        stores.chunk.getByRoom(ROOM_ID).size shouldBeEqualTo 1
    }

    @Test
    fun `merging a range into itself is a no-op`() {
        val only = range()
        add(only, "\$a", ts = 10 * HOUR)

        stores.chunk.mergeInto(only, only)

        eventIds(only) shouldBeEqualTo listOf("\$a")
        stores.chunk.getById(only) shouldBeEqualTo stores.chunk.getById(only)
    }

    @Test
    fun `a range sharing an event is folded in, one sharing none is left`() {
        val bound = range()
        val sharing = range()
        val separate = range()
        add(bound, "\$shared", ts = 20 * HOUR)
        add(sharing, "\$shared", ts = 20 * HOUR)
        add(sharing, "\$extra", ts = 15 * HOUR)
        add(separate, "\$elsewhere", ts = 1 * HOUR)

        val survivor = stores.chunk.mergeRangesSharingEvents(ROOM_ID, bound)

        survivor shouldNotBe null
        eventIds(survivor!!) shouldBeEqualTo listOf("\$shared", "\$extra")
        eventIds(separate) shouldBeEqualTo listOf("\$elsewhere")
    }

    /** Span overlap alone does not prove continuous history. */
    @Test
    fun `ranges are not merged just because their spans interleave`() {
        val a = range()
        val b = range()
        add(a, "\$a1", ts = 10 * HOUR)
        add(a, "\$a2", ts = 30 * HOUR)
        add(b, "\$b1", ts = 20 * HOUR)

        stores.chunk.mergeRangesSharingEvents(ROOM_ID, a).shouldBeNull()

        stores.chunk.getByRoom(ROOM_ID).size shouldBeEqualTo 2
    }

    @Test
    fun `a range spanning a gap is split, and the older side keeps the backward frontier`() {
        val fused = stores.chunk.insert(ROOM_ID, "older-prev", null, true, false, null, false)
        add(fused, "\$old1", ts = 1 * HOUR)
        add(fused, "\$old2", ts = 2 * HOUR)
        add(fused, "\$new1", ts = 400 * HOUR)

        stores.chunk.splitRangesAtGaps(ROOM_ID, gapThresholdMs = 24 * HOUR) shouldBeEqualTo 1

        val ranges = stores.chunk.getByRoom(ROOM_ID)
        ranges.size shouldBeEqualTo 2
        eventIds(fused) shouldBeEqualTo listOf("\$new1")
        val older = ranges.first { it.id != fused }
        eventIds(older.id) shouldBeEqualTo listOf("\$old2", "\$old1")
        older.prev_token shouldBeEqualTo "older-prev"
        // The newer side's past is the hole, which no token of this walk can serve.
        stores.chunk.getById(fused)!!.prev_token.shouldBeNull()
    }

    @Test
    fun `a contiguous range is left whole`() {
        val range = range(prevToken = "p")
        add(range, "\$a", ts = 1 * HOUR)
        add(range, "\$b", ts = 2 * HOUR)
        add(range, "\$c", ts = 3 * HOUR)

        stores.chunk.splitRangesAtGaps(ROOM_ID, gapThresholdMs = 24 * HOUR) shouldBeEqualTo 0

        stores.chunk.getByRoom(ROOM_ID).size shouldBeEqualTo 1
    }

    /** Several holes in one range each need their own cut, or the older side still spans one. */
    @Test
    fun `a range with two gaps is split into three`() {
        val fused = range(prevToken = "p")
        add(fused, "\$a", ts = 1 * HOUR)
        add(fused, "\$b", ts = 200 * HOUR)
        add(fused, "\$c", ts = 400 * HOUR)

        stores.chunk.splitRangesAtGaps(ROOM_ID, gapThresholdMs = 24 * HOUR) shouldBeEqualTo 2

        stores.chunk.getByRoom(ROOM_ID).size shouldBeEqualTo 3
        stores.chunk.getByRoom(ROOM_ID).forEach { r ->
            stores.timelineEvent.getByChunk(r.id).size shouldBeEqualTo 1
        }
    }

    @Test
    fun `an empty range is dropped, but never the live one`() {
        val empty = range()
        val live = stores.chunk.insert(ROOM_ID, null, null, true, false, null, false)
        val held = range()
        add(held, "\$a", ts = 10 * HOUR)

        stores.chunk.deleteEmptyRanges(ROOM_ID) shouldBeEqualTo 1

        stores.chunk.getById(empty).shouldBeNull()
        stores.chunk.getById(live) shouldNotBe null
        eventIds(held) shouldBeEqualTo listOf("\$a")
    }

    /** A limited sync may leave disjoint event sets spanning the same period. */
    @Test
    fun `two ranges over the same period become one`() {
        val demoted = range()
        val live = stores.chunk.insert(ROOM_ID, "live-prev", null, true, false, null, false)
        add(demoted, "\$old1", ts = 10 * HOUR)
        add(demoted, "\$old2", ts = 14 * HOUR)
        add(live, "\$new1", ts = 12 * HOUR)
        add(live, "\$new2", ts = 16 * HOUR)

        stores.chunk.normaliseRanges(ROOM_ID, gapThresholdMs = 24 * HOUR) shouldBeEqualTo 1

        stores.chunk.getByRoom(ROOM_ID).size shouldBeEqualTo 1
        eventIds(live) shouldBeEqualTo listOf("\$new2", "\$old2", "\$new1", "\$old1")
    }

    /** Normalization must preserve a context island separated by real gaps. */
    @Test
    fun `an island inside a wide span survives normalisation as its own range`() {
        val live = stores.chunk.insert(ROOM_ID, null, null, true, false, null, false)
        add(live, "\$ancient", ts = 1 * HOUR)
        add(live, "\$recent", ts = 1000 * HOUR)
        val island = range()
        add(island, "\$jumped", ts = 500 * HOUR)

        stores.chunk.normaliseRanges(ROOM_ID, gapThresholdMs = 24 * HOUR)

        val ranges = stores.chunk.getByRoom(ROOM_ID)
        ranges.size shouldBeEqualTo 3
        ranges.forEach { stores.timelineEvent.getByChunk(it.id).size shouldBeEqualTo 1 }
        // Nothing was lost on the way through the merge.
        ranges.flatMap { stores.timelineEvent.getByChunk(it.id) }.map { it.eventId }.sorted() shouldBeEqualTo
                listOf("\$ancient", "\$jumped", "\$recent")
    }

    /** Running it again must change nothing, or it would oscillate against itself. */
    @Test
    fun `normalisation is idempotent`() {
        val live = stores.chunk.insert(ROOM_ID, null, null, true, false, null, false)
        add(live, "\$a", ts = 1 * HOUR)
        add(live, "\$b", ts = 1000 * HOUR)
        val other = range()
        add(other, "\$c", ts = 500 * HOUR)

        stores.chunk.normaliseRanges(ROOM_ID, gapThresholdMs = 24 * HOUR)
        val after = stores.chunk.getByRoom(ROOM_ID).map { it.id to eventIds(it.id) }.sortedBy { it.first.toString() }

        stores.chunk.normaliseRanges(ROOM_ID, gapThresholdMs = 24 * HOUR) shouldBeEqualTo 0

        stores.chunk.getByRoom(ROOM_ID).map { it.id to eventIds(it.id) }.sortedBy { it.first.toString() } shouldBeEqualTo after
    }

    @Test
    fun `the range below a tokenless frontier is findable, and only the one directly below it`() {
        val newest = range()
        add(newest, "\$new", ts = 1000 * HOUR)
        val middle = range()
        add(middle, "\$mid", ts = 500 * HOUR)
        val oldest = range()
        add(oldest, "\$old", ts = 10 * HOUR)

        stores.chunk.rangeBelow(ROOM_ID, newest) shouldBeEqualTo middle
        stores.chunk.rangeBelow(ROOM_ID, middle) shouldBeEqualTo oldest
        stores.chunk.rangeBelow(ROOM_ID, oldest).shouldBeNull()
    }

    /** Do not repeatedly split gaps that the server cannot fill. */
    @Test
    fun `a gap proven unfillable is not split out again`() {
        val fused = range(prevToken = "p")
        add(fused, "\$old", ts = 1 * HOUR)
        add(fused, "\$new", ts = 400 * HOUR)

        stores.chunk.markGapUnfillable(ROOM_ID, olderSideMaxTs = 1 * HOUR)

        stores.chunk.splitRangesAtGaps(ROOM_ID, gapThresholdMs = 24 * HOUR) shouldBeEqualTo 0
        stores.chunk.getByRoom(ROOM_ID).size shouldBeEqualTo 1
    }

    /**
     * The loop's give-up bound must not be re-read from the shrinking range list: with seven ranges over
     * one period it quit after five merges on device and left two of them — and everything they held —
     * unreachable.
     */
    @Test
    fun `many ranges over one period all merge in a single pass`() {
        val live = stores.chunk.insert(ROOM_ID, "live-prev", null, true, false, null, false)
        add(live, "\$live1", ts = 10 * HOUR)
        add(live, "\$live2", ts = 100 * HOUR)
        repeat(6) { i ->
            val other = range()
            add(other, "\$other$i", ts = (20 + i * 10) * HOUR)
        }

        stores.chunk.mergeOverlappingRanges(ROOM_ID) shouldBeEqualTo 6

        stores.chunk.getByRoom(ROOM_ID).size shouldBeEqualTo 1
        stores.timelineEvent.getByChunk(live).size shouldBeEqualTo 8
    }

    private fun setUpFresh() {
        driver.close()
        setUp()
    }

    /** Sync normalises around the range it just wrote; reading every range of the room per sync is the cost being avoided. */
    @Test
    fun `a scoped normalisation only splits the range that was written`() {
        val untouched = range(prevToken = "p")
        add(untouched, "\$old", ts = 1 * HOUR)
        add(untouched, "\$new", ts = 400 * HOUR)
        val written = range()
        add(written, "\$w1", ts = 800 * HOUR)
        add(written, "\$w2", ts = 1_200 * HOUR)

        stores.chunk.normaliseRanges(ROOM_ID, gapThresholdMs = 24 * HOUR, writtenRangeId = written)

        eventIds(untouched) shouldBeEqualTo listOf("\$new", "\$old")
        eventIds(written) shouldBeEqualTo listOf("\$w2")
        stores.chunk.getByRoom(ROOM_ID).size shouldBeEqualTo 3
    }

    /** A long history an open timeline is reading must not be folded into the small range a page created. */
    @Test
    fun `folding shared history keeps the larger range`() {
        val history = range(prevToken = "h-prev")
        repeat(20) { add(history, "\$e$it", ts = it * HOUR) }
        val page = range(prevToken = "p-prev")
        add(page, "\$e0", ts = 0)

        stores.chunk.mergeRangesSharingEvents(ROOM_ID, page) shouldBeEqualTo history

        stores.chunk.getById(page).shouldBeNull()
        eventIds(history).size shouldBeEqualTo 20
    }

    private fun range(prevToken: String? = null, nextToken: String? = null, reachedStart: Boolean = false): Long =
            stores.chunk.insert(ROOM_ID, prevToken, nextToken, false, reachedStart, null, false)

    private fun eventIds(chunkId: Long) = stores.timelineEvent.getByChunk(chunkId).map { it.eventId }

    private fun add(chunkId: Long, eventId: String, ts: Long) {
        val entity = EventEntity(eventId = eventId, roomId = ROOM_ID, type = EventType.MESSAGE, sender = "@a:hs", originServerTs = ts)
        val dbId = stores.event.insert(entity)
        stores.timelineWriter.addTimelineEvent(
                chunkId = chunkId, roomId = ROOM_ID, eventDbId = dbId, event = entity, isLastForward = false,
        )
    }
}
