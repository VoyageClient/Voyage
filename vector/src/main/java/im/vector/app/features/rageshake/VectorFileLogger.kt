/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.rageshake

import android.content.Context
import android.util.Log
import org.matrix.android.sdk.api.extensions.tryOrNull
import timber.log.Timber
import java.io.File
import java.util.logging.ErrorManager
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VectorFileLogger @Inject constructor(
        context: Context,
) : Timber.Tree() {

    companion object {
        private const val MAX_LOG_SIZE_BYTES = 20 * 1024 * 1024
        private const val LOG_ROTATION_COUNT = 10
    }

    private val logger = Logger.getLogger(context.packageName).apply {
        tryOrNull {
            useParentHandlers = false
            level = Level.ALL
        }
    }

    @Volatile
    private var fileHandler: FileHandler? = null
    private val cacheDirectory = File(context.cacheDir, "logs")
    private var fileNamePrefix = "logs"
    private var written = 0L
    private val epoch = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var accepting = false

    private val prioPrefixes = mapOf(
            Log.VERBOSE to "V/ ",
            Log.DEBUG to "D/ ",
            Log.INFO to "I/ ",
            Log.WARN to "W/ ",
            Log.ERROR to "E/ ",
            Log.ASSERT to "WTF/ "
    )

    @Synchronized
    fun open() {
        if (accepting) return
        val token = epoch.incrementAndGet()
        writer.executeControl { if (token == epoch.get()) openOnWorker() }
        accepting = true
    }

    private fun openOnWorker() {
        if (fileHandler != null) return
        if (!cacheDirectory.exists()) {
            if (!cacheDirectory.mkdirs() && !cacheDirectory.exists()) LogHealth.record(cacheDirectory, "main", "mkdir")
        }

        for (i in 0..15) {
            val file = File(cacheDirectory, "elementLogs.${i}.txt")
            tryOrNull { file.delete() }
        }

        fileHandler = try {
            FileHandler(
                    cacheDirectory.absolutePath + "/" + fileNamePrefix + ".%g.txt",
                    MAX_LOG_SIZE_BYTES,
                    LOG_ROTATION_COUNT,
                    // Without append every process start rotates, so a few background wake-ups push a
                    // session's log out behind files holding nothing but the startup banner.
                    true
            )
                    .also { it.formatter = LogFormatter() }
                    .also { handler ->
                        handler.errorManager = object : ErrorManager() {
                            override fun error(msg: String?, ex: Exception?, code: Int) {
                                LogHealth.record(cacheDirectory, "main", "handler-$code", writer.pendingCount, ex)
                            }
                        }
                    }
                    .also { logger.addHandler(it) }
        } catch (failure: Exception) {
            LogHealth.record(cacheDirectory, "main", "open", failure = failure)
            null
        }
    }

    @Synchronized
    fun close() {
        accepting = false
        epoch.incrementAndGet()
        writer.executeControl { closeOnWorker() }
    }

    private fun closeOnWorker() {
        val handler = fileHandler ?: return
        LogHealth.record(cacheDirectory, "main", "closed", writer.pendingCount)
        fileHandler = null
        logger.removeHandler(handler)
        try {
            handler.close()
        } catch (failure: Exception) {
            LogHealth.record(cacheDirectory, "main", "close", failure = failure)
        }
    }

    fun deleteLogFiles(shouldDelete: () -> Boolean = { true }) = writer.executeControl {
        synchronized(LogHealth.storageLock) {
            if (shouldDelete()) {
                closeOnWorker()
                cacheDirectory.listFiles()?.forEach { it.delete() }
            }
        }
    }

    internal val writer = LogWorkQueue("file-logger")

    override fun isLoggable(tag: String?, priority: Int) = accepting

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (!accepting) return
        val token = epoch.get()
        val thread = Thread.currentThread().id
        if (!writer.offer(message) { bounded ->
                    if (accepting && token == epoch.get() && fileHandler != null) {
                        try {
                            logToFile(prioPrefixes[priority] ?: "$priority ", tag ?: "Tag", bounded, thread)
                            if (++written % 256L == 0L) LogHealth.record(cacheDirectory, "main", "writer-sample", writer.pendingCount)
                        } catch (failure: Exception) {
                            LogHealth.record(cacheDirectory, "main", "write", writer.pendingCount, failure)
                        }
                    }
                }) LogHealth.record(cacheDirectory, "main", "queue-drop", writer.pendingCount)
    }

    private fun logToFile(level: String, tag: String, content: String, thread: Long) {
        logger.info("$thread $level/$tag: $content")
    }
}
