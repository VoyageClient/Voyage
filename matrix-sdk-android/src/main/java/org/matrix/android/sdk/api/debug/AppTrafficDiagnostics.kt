/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.debug

import android.net.TrafficStats
import android.os.Process
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal class TrafficSampler(
        private val read: () -> Pair<Long, Long>,
        private val now: () -> Long = System::nanoTime,
) {
    private var lastAt: Long? = null
    private var previous: Pair<Long, Long>? = null
    private var previousHttp: Pair<Long, Long>? = null

    @Synchronized
    fun reset() {
        lastAt = null
        previous = null
        previousHttp = null
    }

    @Synchronized
    fun sample(label: String, httpTx: Long, httpRx: Long, force: Boolean = false, valid: () -> Boolean = { true }) {
        if (!DebugLog.enabled) return
        val time = now()
        val elapsed = lastAt?.let { (time - it) / 1_000_000 }
        if (!force && elapsed != null && elapsed < 1000) return
        val bytes = try {
            read().takeIf { it.first >= 0 && it.second >= 0 }
        } catch (failure: RuntimeException) {
            null
        }
        if (!DebugLog.enabled || !valid()) return
        lastAt = time
        val old = previous
        val oldHttp = previousHttp
        previous = bytes
        previousHttp = bytes?.let { httpTx to httpRx }
        DebugLog.i {
            val comparable = old != null && bytes != null && bytes.first >= old.first && bytes.second >= old.second
            "NETDBG uid-traffic checkpoint=$label scope=all-networks supported=${bytes != null} " +
                    "tx=${bytes?.first} rx=${bytes?.second} intervalMs=$elapsed " +
                    "deltaTx=${if (comparable) bytes!!.first - old!!.first else null} " +
                    "deltaRx=${if (comparable) bytes!!.second - old!!.second else null} " +
                    "httpDeltaTxCompleted=${if (comparable) httpTx - oldHttp!!.first else null} " +
                    "httpDeltaRx=${if (comparable) httpRx - oldHttp!!.second else null} ${DiagnosticContext.describe()}"
        }
    }
}

internal class TrafficSamplingQueue(private val sampler: TrafficSampler) {

    private val skipped = AtomicLong()
    private val epoch = AtomicLong()
    private var sampledEpoch = -1L
    private var lastSubmitted = 0L
    private val worker = ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(4),
            { task -> Thread(task, "traffic-diagnostics").apply { isDaemon = true } },
            RejectedExecutionHandler { _, _ -> skipped.incrementAndGet() },
    )

    @Synchronized
    fun sample(label: String, httpTx: Long, httpRx: Long, force: Boolean = false) {
        if (!DebugLog.enabled) return
        val submitted = System.nanoTime()
        if (!force && submitted - lastSubmitted < 1_000_000_000L) return
        lastSubmitted = submitted
        val token = epoch.get()
        val origin = DiagnosticContext.current()
        worker.execute {
            if (token == epoch.get() && DebugLog.enabled) {
                if (sampledEpoch != token) {
                    sampler.reset()
                    sampledEpoch = token
                }
                DiagnosticContext.run(origin) {
                    sampler.sample("$label sampleDelayMs=${(System.nanoTime() - submitted) / 1_000_000} skippedSamples=${skipped.getAndSet(0)}", httpTx, httpRx, force) { token == epoch.get() }
                }
            }
        }
    }

    @Synchronized
    fun reset() {
        epoch.incrementAndGet()
        lastSubmitted = 0
        worker.queue.clear()
        skipped.set(0)
    }

    internal fun awaitIdle(): Boolean = try {
        worker.submit {}.get(5, TimeUnit.SECONDS)
        true
    } catch (failure: Exception) {
        false
    }
}

// UID counters include concurrent traffic and socket overhead; they cannot measure one action in isolation.
internal object AppTrafficDiagnostics {
    private val samples = TrafficSamplingQueue(TrafficSampler({
        TrafficStats.getUidTxBytes(Process.myUid()) to TrafficStats.getUidRxBytes(Process.myUid())
    }))

    fun sample(label: String, httpTx: Long, httpRx: Long, force: Boolean = false) = samples.sample(label, httpTx, httpRx, force)
    fun reset() = samples.reset()
}
