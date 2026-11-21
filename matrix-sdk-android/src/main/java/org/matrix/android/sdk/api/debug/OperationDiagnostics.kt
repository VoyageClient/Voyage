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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object OperationDiagnostics {
    private val sequence = AtomicLong()
    private val progressTimer by lazy {
        Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "operation-diagnostics").apply { isDaemon = true } }
    }

    fun id(value: String?): String? = value?.let { Integer.toHexString(it.hashCode()) }

    fun start(tag: String, stage: String, fields: String = ""): Span? =
            if (DebugLog.enabled) Span(sequence.incrementAndGet(), tag, stage, fields) else null

    inline fun start(tag: String, stage: String, fields: () -> String): Span? =
            if (DebugLog.enabled) start(tag, stage, fields()) else null

    fun <T> run(tag: String, stage: String, fields: String = "", block: () -> T): T {
        val span = start(tag, stage, fields)
        try {
            return block().also { span?.finish("succeeded") }
        } catch (failure: Throwable) {
            span?.fail(failure)
            throw failure
        }
    }

    suspend fun <T> trace(tag: String, stage: String, fields: String = "", block: suspend () -> T): T = DiagnosticContext.withOrigin(stage) {
        val span = start(tag, stage, fields)
        try {
            block().also { span?.finish("succeeded") }
        } catch (failure: Throwable) {
            span?.fail(failure)
            throw failure
        }
    }

    class Span internal constructor(private val id: Long, private val tag: String, private val stage: String, private val fields: String) {
        private val startedAt = System.nanoTime()
        private val origin = DiagnosticContext.current()
        private val finished = AtomicBoolean()
        private val lastProgress = AtomicLong(startedAt)
        @Volatile private var currentCount = -1
        @Volatile private var totalCount = -1
        private var progressWatch: ScheduledFuture<*>? = null

        init { event("started") }

        fun event(outcome: String, details: String = "") {
            lastProgress.set(System.nanoTime())
            emit(outcome, details)
        }

        private fun emit(outcome: String, details: String = "") {
            DebugLog.i { "$tag op=$id stage=$stage outcome=$outcome elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000} $fields $details ${DiagnosticContext.describe(origin)}" }
        }

        @Synchronized
        fun watchProgress(timeoutMillis: Long = 60_000L) {
            if (finished.get() || progressWatch != null) return
            progressWatch = progressTimer.scheduleWithFixedDelay({
                synchronized(this) {
                    if (!DebugLog.enabled) progressWatch?.cancel(false)
                    if (!finished.get() && System.nanoTime() - lastProgress.get() >= timeoutMillis * 1_000_000L) {
                        emit("stalled", "idleMs=${(System.nanoTime() - lastProgress.get()) / 1_000_000} progress=$currentCount total=$totalCount")
                    }
                }
            }, timeoutMillis, timeoutMillis, TimeUnit.MILLISECONDS)
        }

        @Synchronized
        fun progress(current: Int, total: Int) {
            if (finished.get() || current == currentCount && total == totalCount) return
            val previousBucket = if (totalCount > 0) currentCount.toLong() * 10 / totalCount else -1
            val bucket = if (total > 0) current.toLong() * 10 / total else -1
            currentCount = current
            totalCount = total
            lastProgress.set(System.nanoTime())
            if (bucket != previousBucket || current == total) emit("progress", "progress=$current total=$total")
        }

        @Synchronized
        fun finish(outcome: String, details: String = "") {
            if (finished.compareAndSet(false, true)) {
                progressWatch?.cancel(false)
                event(outcome, details)
            }
        }

        fun fail(failure: Throwable) = finish(
                if (failure is java.util.concurrent.CancellationException) "canceled" else "failed",
                "error=${failure.javaClass.simpleName} cause=${failure.cause?.javaClass?.simpleName}"
        )
    }
}
