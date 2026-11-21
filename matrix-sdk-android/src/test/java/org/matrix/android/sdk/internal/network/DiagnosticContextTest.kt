/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.debug.DiagnosticContext
import org.matrix.android.sdk.api.debug.TrafficSampler
import org.matrix.android.sdk.api.debug.TrafficSamplingQueue
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DiagnosticContextTest {
    private val messages = CopyOnWriteArrayList<String>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            messages.add(message)
        }
    }

    @Before
    fun setUp() {
        DebugLog.enabled = true
        Timber.plant(tree)
    }

    @After
    fun tearDown() {
        DebugLog.enabled = false
        Timber.uproot(tree)
    }

    @Test
    fun `origins survive dispatch and suspension without leaking between concurrent actions`() = runBlocking {
        val roots = listOf("action-a", "action-b").map { source ->
            async(Dispatchers.Default) {
                DiagnosticContext.withOrigin(source) {
                    val root = DiagnosticContext.current()!!
                    DiagnosticContext.withOrigin("fetch") {
                        withContext(Dispatchers.IO) {
                            yield()
                            val child = DiagnosticContext.current()!!
                            assertEquals(root.id, child.root)
                            assertEquals(root.id, child.parent)
                            assertNotEquals(root.id, child.id)
                        }
                    }
                    assertEquals(root, DiagnosticContext.current())
                    root.root
                }
            }
        }.map { it.await() }
        assertNotEquals(roots[0], roots[1])
        assertNull(DiagnosticContext.current())
    }

    @Test
    fun `cancellation restores the caller origin and preserves the failure`() = runBlocking {
        val original = CancellationException("synthetic cancellation")
        DiagnosticContext.withOrigin("caller") {
            val root = DiagnosticContext.current()
            try {
                DiagnosticContext.withOrigin<Unit>("child") { throw original }
                throw AssertionError("Expected cancellation")
            } catch (failure: CancellationException) {
                assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === original })
                assertEquals(root, DiagnosticContext.current())
            }
        }
        assertNull(DiagnosticContext.current())
    }

    @Test
    fun `UID snapshots compare supported counters and throttle unforced reads`() {
        var time = 0L
        var reads = 0
        var bytes = 100L to 200L
        val sampler = TrafficSampler({
            reads++
            bytes
        }, { time })
        sampler.sample("baseline", 10, 20)
        bytes = 180L to 700L
        time = 500_000_000L
        sampler.sample("throttled", 40, 320)
        assertEquals(1, reads)
        time = 2_000_000_000L
        sampler.sample("next", 40, 320)
        assertTrue(messages.any { "deltaTx=80 deltaRx=500 httpDeltaTxCompleted=30 httpDeltaRx=300" in it })
        assertEquals(2, reads)
    }

    @Test
    fun `unsupported and reset UID counters are not reported as zero usage`() {
        var bytes = -1L to -1L
        val sampler = TrafficSampler({ bytes })
        sampler.sample("unsupported", 10, 20, force = true)
        assertTrue(messages.last().contains("supported=false"))
        assertTrue(messages.last().contains("deltaRx=null"))
        bytes = 1000L to 2000L
        sampler.sample("supported", 10, 20, force = true)
        assertTrue(messages.last().contains("deltaRx=null"))
        bytes = 5L to 10L
        sampler.sample("counter-reset", 30, 40, force = true)
        assertTrue(messages.last().contains("deltaRx=null"))
        sampler.reset()
        bytes = 500L to 1000L
        sampler.sample("new-baseline", 30, 40, force = true)
        assertTrue(messages.last().contains("deltaRx=null"))
    }

    @Test
    fun `disabled diagnostics do not sample platform counters or create origins`() = runBlocking {
        DebugLog.enabled = false
        val sampler = TrafficSampler({ throw AssertionError("Counters must not be read") })
        sampler.sample("disabled", 0, 0, force = true)
        DiagnosticContext.withOrigin("disabled") { assertNull(DiagnosticContext.current()) }
        assertNull(DiagnosticContext.newOrigin("disabled"))
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `slow counter reads never block checkpoints or baseline resets`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val reads = AtomicInteger()
        val queue = TrafficSamplingQueue(TrafficSampler({
            if (reads.incrementAndGet() == 1) {
                entered.countDown()
                release.await(30, TimeUnit.SECONDS)
            } else completed.countDown()
            100L to 200L
        }))
        queue.sample("obsolete", 0, 0, force = true)
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val started = System.nanoTime()
            repeat(20_000) { queue.sample("queued", 0, 0, force = true) }
            queue.reset()
            queue.sample("fresh", 0, 0, force = true)
            val elapsed = (System.nanoTime() - started) / 1_000_000
            println("TrafficSamplingQueue: 20000 checkpoints and reset with blocked counters took ${elapsed}ms")
            assertTrue("Checkpoint blocked for ${elapsed}ms", elapsed < 2000)
            assertTrue(messages.isEmpty())
        } finally {
            release.countDown()
        }
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertTrue(queue.awaitIdle())
        assertEquals(2, reads.get())
        assertTrue(messages.any { "checkpoint=fresh" in it && "deltaRx=null" in it })
        assertFalse(messages.any { "checkpoint=obsolete" in it || "checkpoint=queued" in it })
    }
}
