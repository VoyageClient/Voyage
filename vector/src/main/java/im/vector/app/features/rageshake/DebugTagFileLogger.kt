/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.rageshake

import android.content.Context
import org.matrix.android.sdk.api.extensions.tryOrNull
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Collects every `*DBG` diagnostic trace into a file of its own under `cacheDir/logs`.
 *
 * One file per tag rather than one shared log: these traces are read days apart and a busy tag would
 * otherwise rotate a quiet one away long before anyone looked at it. The tag is taken from the message
 * rather than from a list, so a new one needs nothing here — `GAPDBG …` lands in `gapdbg.txt`.
 *
 * Only planted for a debug build (see VectorApplication), alongside [org.matrix.android.sdk.api.debug.DebugLog].
 */
@Singleton
class DebugTagFileLogger @Inject constructor(private val context: Context) : Timber.Tree() {

    private val directory by lazy { File(context.cacheDir, "logs") }

    // Per tag, so a chatty one does not serialise the others behind it.
    private val locks = ConcurrentHashMap<String, Any>()

    // SimpleDateFormat is not thread-safe and the per-tag locks let two tags format at once.
    private val timestampFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    private fun now(): String = timestampFormat.get()!!.format(Date())

    override fun isLoggable(tag: String?, priority: Int) = true

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val dbgTag = TAG_PATTERN.find(message)?.groupValues?.get(1) ?: return
        val name = dbgTag.lowercase(Locale.US)
        synchronized(locks.getOrPut(name) { Any() }) {
            tryOrNull("Failed to write the $dbgTag log") {
                if (!directory.exists()) directory.mkdirs()
                val file = File(directory, "$name.txt")
                if (file.length() > MAX_SIZE_BYTES) {
                    File(directory, "$name.1.txt").delete()
                    file.renameTo(File(directory, "$name.1.txt"))
                }
                file.appendText("${now()} $message\n")
                t?.let { file.appendText("${now()} $dbgTag ${it.stackTraceToString()}\n") }
            }
        }
    }

    private companion object {
        private val TAG_PATTERN = Regex("^([A-Z][A-Z0-9]*DBG)\\b")
        private const val MAX_SIZE_BYTES = 4L * 1024 * 1024
    }
}
