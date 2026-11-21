/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.rageshake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LogWorkQueueTest {
    @Test
    fun `stalled storage bounds queued memory and never blocks producers`() {
        val queue = LogWorkQueue("test-stalled-log", maxEntries = 8, maxCharacters = 1024)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val control = CountDownLatch(1)
        queue.executeControl {
            entered.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            var accepted = 0
            val started = System.nanoTime()
            repeat(20_000) { if (queue.offer("x".repeat(256)) {}) accepted++ }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            println("LogWorkQueue: 20000 offers with blocked storage took ${elapsed}ms")
            assertTrue("Producer blocked for ${elapsed}ms", elapsed < 2000)
            assertEquals(4, accepted)
            assertEquals(4, queue.pendingCount)
            queue.executeControl { control.countDown() }
        } finally {
            release.countDown()
        }
        assertTrue(control.await(2, TimeUnit.SECONDS))
        assertTrue(queue.awaitIdle())
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun `entry budget applies to empty records and permits later records after drain`() {
        val queue = LogWorkQueue("test-entry-budget", maxEntries = 2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        queue.executeControl {
            entered.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(queue.offer("") {})
            assertTrue(queue.offer("") {})
            assertFalse(queue.offer("") {})
        } finally {
            release.countDown()
        }
        assertTrue(queue.awaitIdle())
        assertTrue(queue.offer("after drain") {})
        assertTrue(queue.awaitIdle())
    }

    @Test
    fun `large records are truncated and preserve queue order`() {
        val queue = LogWorkQueue("test-large-log")
        val records = ArrayList<String>()
        assertTrue(queue.offer("x".repeat(100_000)) { records.add(it) })
        assertTrue(queue.offer("second") { records.add(it) })
        assertTrue(queue.awaitIdle())
        assertTrue(records[0].length <= 16 * 1024)
        assertTrue(records[0].endsWith("[log truncated]"))
        assertEquals("second", records[1])
    }
}
