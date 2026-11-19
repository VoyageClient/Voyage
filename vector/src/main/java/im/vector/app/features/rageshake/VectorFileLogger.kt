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
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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
        private const val MAX_PENDING_LINES = 10_000
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
        if (fileHandler != null) return
        if (!cacheDirectory.exists()) {
            cacheDirectory.mkdirs()
        }

        for (i in 0..15) {
            val file = File(cacheDirectory, "elementLogs.${i}.txt")
            tryOrNull { file.delete() }
        }

        fileHandler = tryOrNull("Failed to initialize FileLogger") {
            FileHandler(
                    cacheDirectory.absolutePath + "/" + fileNamePrefix + ".%g.txt",
                    MAX_LOG_SIZE_BYTES,
                    LOG_ROTATION_COUNT,
                    // Without append every process start rotates, so a few background wake-ups push a
                    // session's log out behind files holding nothing but the startup banner.
                    true
            )
                    .also { it.formatter = LogFormatter() }
                    .also { logger.addHandler(it) }
        }
    }

    @Synchronized
    fun close() {
        val handler = fileHandler ?: return
        fileHandler = null
        logger.removeHandler(handler)
        tryOrNull { handler.close() }
    }

    fun deleteLogFiles() {
        cacheDirectory.listFiles()?.forEach { tryOrNull { it.delete() } }
    }

    // One writer thread: the handler serializes every write on its own lock anyway, and a task per line on
    // Dispatchers.IO let a log flood (crypto during a search crawl) occupy the whole IO pool and starve
    // unrelated work queued behind it. Bounded so a flood drops lines instead of growing without limit.
    private val writer = ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(MAX_PENDING_LINES),
            { runnable -> Thread(runnable, "file-logger").apply { isDaemon = true } },
            ThreadPoolExecutor.DiscardPolicy()
    )

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        fileHandler ?: return
        writer.execute {
            if (t != null) {
                logToFile(t)
            }
            logToFile(prioPrefixes[priority] ?: "$priority ", tag ?: "Tag", message)
        }
    }

    /**
     * Log an Throwable.
     *
     * @param throwable the throwable to log
     */
    private fun logToFile(throwable: Throwable?) {
        throwable ?: return

        val errors = StringWriter()
        throwable.printStackTrace(PrintWriter(errors))

        logger.info(errors.toString())
    }

    private fun logToFile(level: String, tag: String, content: String) {
        val b = StringBuilder()
        b.append(Thread.currentThread().id)
        b.append(" ")
        b.append(level)
        b.append("/")
        b.append(tag)
        b.append(": ")
        b.append(content)
        logger.info(b.toString())
    }
}
