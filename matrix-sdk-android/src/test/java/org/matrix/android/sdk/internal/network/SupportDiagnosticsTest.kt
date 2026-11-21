/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.network

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.debug.OperationDiagnostics
import org.matrix.android.sdk.api.debug.RecoveryTracker
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SupportDiagnosticsTest {
    private val messages = CopyOnWriteArrayList<String>()
    private var onMessage: (String) -> Unit = {}
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            messages.add(message)
            onMessage(message)
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
    fun `disabled spans do not evaluate metadata`() {
        DebugLog.enabled = false
        OperationDiagnostics.start("TESTDBG", "disabled") { error("Metadata evaluated while disabled") }
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `key recovery links waiting requests imports and successful decryptions`() {
        val tracker = RecoveryTracker()
        tracker.missing("opaque-key-a", "opaque-event-a")
        tracker.missing("opaque-key-a", "opaque-event-a")
        tracker.phase("opaque-key-a", "request-queued")
        tracker.phase("opaque-key-a", "key-imported")
        tracker.decrypted("opaque-key-a", "opaque-event-a")
        tracker.decrypted("opaque-key-a", "opaque-event-a")
        assertFalse(tracker.hasPending())
        assertEquals(1, messages.count { "outcome=waiting" in it })
        assertEquals(1, messages.count { "outcome=recovered" in it })
        assertTrue(messages.any { "outcome=key-imported" in it })
        assertEquals(1, messages.map { it.substringAfter("op=").substringBefore(' ') }.distinct().size)
    }

    @Test
    fun `recovery times out without canceling or mutating application operations`() {
        var time = 0L
        val tracker = RecoveryTracker({ time }, timeoutMillis = 1000)
        tracker.missing("opaque-key-a", "opaque-event-a")
        time = 999_000_000L
        tracker.expire()
        assertTrue(tracker.hasPending())
        time = 1_000_000_000L
        tracker.expire()
        assertFalse(tracker.hasPending())
        assertTrue(messages.any { "outcome=timeout" in it && "pendingEvents=1" in it })
    }

    @Test
    fun `diagnostic failures retain the original exception and exclude its message`() {
        val original = IllegalStateException("synthetic-private-payload")
        try {
            OperationDiagnostics.run<Unit>("TESTDBG", "failure") { throw original }
            throw AssertionError("Expected failure")
        } catch (failure: IllegalStateException) {
            assertSame(original, failure)
        }
        assertTrue(messages.any { "outcome=failed" in it && "error=IllegalStateException" in it })
        assertFalse(messages.any { "synthetic-private-payload" in it })
    }

    @Test
    fun `progress watcher reports inactivity and stops on completion`() {
        val stalled = CountDownLatch(1)
        onMessage = { if ("outcome=stalled" in it) stalled.countDown() }
        val span = OperationDiagnostics.start("TESTDBG", "progress")!!
        try {
            span.watchProgress(20)
            assertTrue(stalled.await(2, TimeUnit.SECONDS))
            span.progress(10, 10)
            span.finish("completed")
            val late = CountDownLatch(1)
            onMessage = { if ("outcome=stalled" in it) late.countDown() }
            assertFalse(late.await(60, TimeUnit.MILLISECONDS))
            assertEquals(1, messages.count { "outcome=completed" in it })
        } finally {
            span.finish("cleanup")
        }
    }
}
