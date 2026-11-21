/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.debug

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal class RecoveryTracker(
        private val now: () -> Long = System::nanoTime,
        private val timeoutMillis: Long = 60_000L,
) {
    private class Pending(val id: Long, val startedAt: Long, val span: OperationDiagnostics.Span?) {
        val events = HashSet<String>()
    }
    private val sequence = AtomicLong()
    private val pending = LinkedHashMap<String, Pending>()
    @Volatile private var pendingCount = 0

    @Synchronized
    fun missing(key: String, event: String) {
        if (!DebugLog.enabled) return
        expire()
        val entry = pending[key] ?: Pending(sequence.incrementAndGet(), now(), OperationDiagnostics.start("RECOVERYDBG", "missing-key") { "key=$key" }).also {
            if (pending.size >= 256) pending.remove(pending.keys.first())?.span?.finish("tracking-capacity")
            pending[key] = it
            pendingCount = pending.size
        }
        if (entry.events.size < 256 && entry.events.add(event)) entry.span?.event("waiting", "recovery=${entry.id} event=$event pendingEvents=${entry.events.size}")
    }

    @Synchronized
    fun phase(key: String, stage: String) {
        if (!DebugLog.enabled) return
        pending[key]?.span?.event(stage)
    }

    @Synchronized
    fun decrypted(key: String, event: String) {
        if (!DebugLog.enabled) return
        val entry = pending[key] ?: return
        if (!entry.events.remove(event)) return
        entry.span?.event("decrypted", "event=$event remaining=${entry.events.size}")
        if (entry.events.isEmpty()) {
            pending.remove(key)
            pendingCount = pending.size
            entry.span?.finish("recovered")
        }
    }

    @Synchronized
    fun expire() {
        val time = now()
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (!DebugLog.enabled || time - entry.startedAt >= timeoutMillis * 1_000_000L) {
                iterator.remove()
                pendingCount = pending.size
                entry.span?.finish("timeout", "pendingEvents=${entry.events.size}")
            }
        }
    }

    fun hasPending(): Boolean = pendingCount != 0
}

object CryptoRecoveryDiagnostics {
    private val tracker = RecoveryTracker()
    private val timer by lazy {
        Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "crypto-recovery-diagnostics").apply { isDaemon = true } }
    }
    private var timeoutTask: ScheduledFuture<*>? = null

    private fun key(room: String?, session: String?, sender: String?): String =
            "${OperationDiagnostics.id(room)}:${OperationDiagnostics.id(session)}:${OperationDiagnostics.id(sender)}"

    fun missing(room: String?, session: String?, sender: String?, event: String?) {
        if (!DebugLog.enabled || room == null || session == null || event == null) return
        tracker.missing(key(room, session, sender), OperationDiagnostics.id(event)!!)
        synchronized(this) {
            if (timeoutTask == null) timeoutTask = timer.scheduleWithFixedDelay({
                tracker.expire()
                synchronized(this) {
                    if (!tracker.hasPending()) {
                        timeoutTask?.cancel(false)
                        timeoutTask = null
                    }
                }
            }, 5, 5, TimeUnit.SECONDS)
        }
    }

    fun phase(room: String?, session: String?, sender: String?, stage: String) {
        if (DebugLog.enabled && tracker.hasPending()) tracker.phase(key(room, session, sender), stage)
    }

    fun decrypted(room: String?, session: String?, sender: String?, event: String?) {
        if (DebugLog.enabled && tracker.hasPending() && event != null) tracker.decrypted(key(room, session, sender), OperationDiagnostics.id(event)!!)
    }
}
