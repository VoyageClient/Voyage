/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.rageshake

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class LogWorkQueue(
        name: String,
        private val maxEntries: Int = 1024,
        private val maxCharacters: Int = 512 * 1024,
        private val afterBatch: () -> Unit = {},
) {
    private val budget = Any()
    private var entries = 0
    private var characters = 0
    private var lastFlush = 0L
    private val executor = object : ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>(),
            { task -> Thread(task, name).apply { isDaemon = true } },
    ) {
        override fun afterExecute(task: Runnable, failure: Throwable?) {
            val now = System.nanoTime()
            if (queue.isEmpty() || now - lastFlush >= 250_000_000L) {
                lastFlush = now
                afterBatch()
            }
        }
    }

    val pendingCount: Int get() = synchronized(budget) { entries }

    fun offer(message: String, action: (String) -> Unit): Boolean {
        val length = minOf(message.length, MAX_LINE_CHARACTERS)
        synchronized(budget) {
            if (entries >= maxEntries || characters + length > maxCharacters) return false
            entries++
            characters += length
        }
        val bounded = if (message.length > length) message.take(length - 16) + " [log truncated]" else message
        executor.execute {
            try {
                action(bounded)
            } finally {
                synchronized(budget) {
                    entries--
                    characters -= length
                }
            }
        }
        return true
    }

    // Lifecycle commands must run even when log records have exhausted the queue budget.
    fun executeControl(action: () -> Unit) = executor.execute(action)

    internal fun awaitIdle(timeoutSeconds: Long = 5): Boolean = try {
        executor.submit { afterBatch() }.get(timeoutSeconds, TimeUnit.SECONDS)
        true
    } catch (failure: Exception) {
        false
    }

    private companion object {
        const val MAX_LINE_CHARACTERS = 16 * 1024
    }
}
