/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.extensions

import android.os.SystemClock
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterOrEqualTo
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val QUIET = 200L
private const val MAX_DEFER = 1000L

@OptIn(ExperimentalCoroutinesApi::class)
class CollectBatchedTest {

    private val batches = mutableListOf<List<String>>()
    private val source = MutableSharedFlow<String>(extraBufferCapacity = 256)

    @Before
    fun setUp() {
        batches.clear()
        // collectBatched measures elapsed time with SystemClock, which is unimplemented off-device;
        // bind it to the test scheduler's virtual clock so the timings under test are deterministic.
        mockkStatic(SystemClock::class)
    }

    @After
    fun tearDown() {
        unmockkStatic(SystemClock::class)
    }

    /** runCurrent lets the collector subscribe first: a SharedFlow drops emissions made before that. */
    private fun TestScope.startCollector(): Job {
        every { SystemClock.uptimeMillis() } answers { currentTime }
        val job = launch { source.collectBatched(QUIET, MAX_DEFER) { batches.add(it) } }
        runCurrent()
        return job
    }

    @Test
    fun `given a quiet gap, then the batch flushes`() = runTest {
        val job = startCollector()

        source.emit("a")
        source.emit("b")
        delay(QUIET * 2)

        batches shouldBeEqualTo listOf(listOf("a", "b"))
        job.cancel()
    }

    @Test
    fun `given repeated ids in one batch, then they are de-duplicated in arrival order`() = runTest {
        val job = startCollector()

        source.emit("a")
        source.emit("b")
        source.emit("a")
        delay(QUIET * 2)

        batches shouldBeEqualTo listOf(listOf("a", "b"))
        job.cancel()
    }

    @Test
    fun `given two separated bursts, then each flushes on its own`() = runTest {
        val job = startCollector()

        source.emit("a")
        delay(QUIET * 2)
        source.emit("b")
        delay(QUIET * 2)

        batches shouldBeEqualTo listOf(listOf("a"), listOf("b"))
        job.cancel()
    }

    /** The deferral cap is what a plain trailing debounce cannot give: it is bounded. */
    @Test
    fun `given a stream that never goes quiet, then batches still flush at the deferral cap`() = runTest {
        val job = startCollector()

        // An emission every half quiet-period for twice the cap: never quiet, so only the cap can flush.
        repeat(20) {
            source.emit("e$it")
            delay(QUIET / 2)
        }
        delay(QUIET * 2)

        batches.size shouldBeGreaterOrEqualTo 2
        batches.flatten() shouldBeEqualTo (0..19).map { "e$it" }
        job.cancel()
    }

    @Test
    fun `given nothing emitted, then no batch is produced`() = runTest {
        val job = startCollector()

        delay(MAX_DEFER * 2)

        batches.shouldBeEmpty()
        job.cancel()
    }
}
