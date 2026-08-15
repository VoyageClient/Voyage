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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Temporary: read-receipt profile diagnostics ("RRDBG"), kept in their own file because the symptom
 * can take days to appear and the shared logs rotate away long before that.
 */
@Singleton
class ReadReceiptDebugLogger @Inject constructor(context: Context) : Timber.Tree() {

    private val directory = File(context.cacheDir, "logs")
    private val file = File(directory, FILE_NAME)
    private val previous = File(directory, PREVIOUS_FILE_NAME)
    private val timestampFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    override fun isLoggable(tag: String?, priority: Int) = true

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (!message.startsWith(PREFIX)) return
        synchronized(this) {
            tryOrNull("Failed to write read-receipt debug log") {
                if (!directory.exists()) directory.mkdirs()
                if (file.length() > MAX_SIZE_BYTES) {
                    previous.delete()
                    file.renameTo(previous)
                }
                file.appendText("${timestampFormat.format(Date())} $message\n")
            }
        }
    }

    private companion object {
        private const val PREFIX = "RRDBG"
        private const val FILE_NAME = "rrdbg.txt"
        private const val PREVIOUS_FILE_NAME = "rrdbg.1.txt"
        private const val MAX_SIZE_BYTES = 4L * 1024 * 1024
    }
}
