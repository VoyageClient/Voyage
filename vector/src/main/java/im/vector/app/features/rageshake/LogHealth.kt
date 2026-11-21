/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.rageshake

import android.util.Log
import org.matrix.android.sdk.api.debug.DebugLog
import java.io.File

// Bypass Timber: reporting a failed logger through that logger recurses.
internal object LogHealth {
    val storageLock = Any()
    private val counts = HashMap<String, Long>()
    private val lastReported = HashMap<String, Long>()
    private val writer = LogWorkQueue("log-health", maxEntries = 16, maxCharacters = 32768)
    @Volatile private var epoch = 0L

    @Synchronized
    fun reset() {
        epoch++
        counts.clear()
        lastReported.clear()
    }

    fun record(directory: File, source: String, reason: String, queueDepth: Int = 0, failure: Exception? = null) {
        if (!DebugLog.enabled) return
        val token = epoch
        val message = synchronized(this) { report(source, reason, queueDepth, failure) } ?: return
        writer.offer(message) { bounded ->
            if (DebugLog.enabled && token == epoch) write(directory, bounded)
        }
    }

    private fun report(source: String, reason: String, queueDepth: Int, failure: Exception?): String? {
        val key = "$source:$reason"
        val count = (counts[key] ?: 0L) + 1L
        counts[key] = count
        val now = System.nanoTime()
        val previous = lastReported[key]
        if (previous != null && now - previous < 30_000_000_000L) return null
        lastReported[key] = now
        return "LOGDBG logger=$source reason=$reason count=$count queueDepth=$queueDepth error=${failure?.javaClass?.simpleName} totals=${counts.toSortedMap()}"
    }

    // Report filesystem free space, excluding caches Android might reclaim.
    @Suppress("LogNotTimber", "UsableSpace")
    private fun write(directory: File, message: String) = synchronized(storageLock) {
        try {
            if (!directory.exists() && !directory.mkdirs() && !directory.exists()) throw java.io.IOException("Log directory unavailable")
            val file = File(directory, "log-health.txt")
            if (file.length() > 65536) {
                val backup = File(directory, "log-health.1.txt")
                if (backup.exists() && !backup.delete()) throw java.io.IOException("Log health rotation unavailable")
                if (!file.renameTo(backup)) throw java.io.IOException("Log health rotation unavailable")
            }
            file.appendText("${System.currentTimeMillis()} $message freeBytes=${directory.usableSpace}\n")
        } catch (error: Exception) {
            if (DebugLog.enabled) Log.w("LogHealth", "$message healthWriteFailed=${error.javaClass.simpleName}")
        }
    }

    internal fun afterPendingWrites(action: () -> Unit) = writer.executeControl(action)
    internal fun awaitIdle(): Boolean = writer.awaitIdle()
}
