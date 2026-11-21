/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.rageshake

import android.content.Context
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebugTagFileLogger @Inject constructor(context: Context) : Timber.Tree() {
    private val directory = File(context.cacheDir, "logs")
    private val epoch = AtomicLong()
    @Volatile private var accepting = true
    private val outputs = LinkedHashMap<String, Output>(16, 0.75f, true)
    private val timestampFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    internal val writer = LogWorkQueue("diagnostic-file-logger", afterBatch = { flushOutputs() })

    override fun isLoggable(tag: String?, priority: Int) = accepting

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (!accepting) return
        val dbgTag = category(message) ?: return
        val token = epoch.get()
        val at = System.currentTimeMillis()
        if (!writer.offer(message) { bounded ->
                    if (accepting && token == epoch.get()) write(dbgTag, at, bounded)
                }) LogHealth.record(directory, "tags", "queue-drop", writer.pendingCount)
    }

    fun open() {
        epoch.incrementAndGet()
        accepting = true
    }

    fun close() {
        accepting = false
        epoch.incrementAndGet()
        writer.executeControl {
            synchronized(LogHealth.storageLock) {
                outputs.values.forEach { it.close() }
                outputs.clear()
            }
        }
    }

    internal fun afterPendingWrites(action: () -> Unit) = writer.executeControl(action)

    private fun write(tag: String, at: Long, message: String) = synchronized(LogHealth.storageLock) {
        val name = tag.lowercase(Locale.US)
        val output = outputs.getOrPut(name) {
            if (outputs.size >= 32) {
                val oldest = outputs.entries.iterator()
                oldest.next().value.close()
                oldest.remove()
            }
            Output(name)
        }
        output.write("${timestampFormat.format(Date(at))} $message\n")
    }

    private fun flushOutputs() = synchronized(LogHealth.storageLock) { outputs.values.forEach { it.flush() } }

    private inner class Output(private val name: String) {
        private val file = File(directory, "$name.txt")
        private var stream: BufferedWriter? = null
        private var bytes = 0L
        private var dirty = false
        private var retryAfter = 0L

        fun write(line: String) {
            if (System.nanoTime() < retryAfter) {
                LogHealth.record(directory, "tags", "write-backoff", writer.pendingCount)
                return
            }
            try {
                if (stream == null) {
                    if (!directory.exists() && !directory.mkdirs() && !directory.exists()) throw java.io.IOException("Log directory unavailable")
                    bytes = file.length()
                }
                if (bytes > MAX_SIZE_BYTES) rotate()
                val target = stream ?: java.io.FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).also { stream = it }
                target.write(line)
                bytes += line.toByteArray(Charsets.UTF_8).size
                dirty = true
            } catch (failure: Exception) {
                failed("write", failure)
            }
        }

        private fun rotate() {
            close()
            val backup = File(directory, "$name.1.txt")
            if (backup.exists() && !backup.delete()) throw java.io.IOException("Log rotation unavailable")
            if (file.exists() && !file.renameTo(backup)) throw java.io.IOException("Log rotation unavailable")
            bytes = 0
        }

        fun flush() {
            if (!dirty) return
            try {
                stream?.flush()
                dirty = false
            } catch (failure: Exception) {
                failed("flush", failure)
            }
        }

        fun close() {
            try {
                stream?.close()
            } catch (failure: Exception) {
                LogHealth.record(directory, "tags", "close", failure = failure)
            } finally {
                stream = null
                dirty = false
            }
        }

        private fun failed(reason: String, failure: Exception) {
            retryAfter = System.nanoTime() + 30_000_000_000L
            close()
            LogHealth.record(directory, "tags", reason, writer.pendingCount, failure)
        }
    }

    private companion object {
        const val MAX_SIZE_BYTES = 4L * 1024 * 1024

        fun category(message: String): String? {
            var end = 0
            while (end < message.length && end <= 32 && (message[end] in 'A'..'Z' || message[end] in '0'..'9')) end++
            if (end !in 4..32) return null
            if (end < message.length && (message[end].isLetterOrDigit() || message[end] == '_')) return null
            val tag = message.substring(0, end)
            return tag.takeIf { it.endsWith("DBG") && it.first() in 'A'..'Z' && it.all { char -> char in 'A'..'Z' || char in '0'..'9' } }
        }
    }
}
