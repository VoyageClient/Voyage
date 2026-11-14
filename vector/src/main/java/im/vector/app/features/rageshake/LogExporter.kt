/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.rageshake

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import im.vector.app.core.dispatchers.CoroutineDispatchers
import im.vector.app.core.extensions.safeOpenOutputStream
import im.vector.app.core.resources.BuildMeta
import im.vector.app.core.utils.saveMedia
import im.vector.app.features.notifications.NotificationUtils
import im.vector.lib.core.utils.timer.Clock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/** Packs the app's log files into one zip, saved wherever the user picks. */
class LogExporter @Inject constructor(
        private val context: Context,
        private val buildMeta: BuildMeta,
        private val dispatchers: CoroutineDispatchers,
        private val clock: Clock,
        private val notificationUtils: NotificationUtils,
) {

    // No colons: FAT-formatted storage rejects them in file names.
    fun fileName(): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(clock.epochMillis()))
        return "${buildMeta.applicationName.lowercase(Locale.US).replace(' ', '-')}-logs-$timestamp.zip"
    }

    @RequiresApi(Build.VERSION_CODES.KITKAT)
    suspend fun exportTo(uri: Uri) = withContext(dispatchers.io) {
        try {
            val output = context.safeOpenOutputStream(uri) ?: throw IOException("Cannot open $uri")
            writeZip(output)
        } catch (failure: Throwable) {
            // The picker creates the document up front, so a failed export must not leave it behind.
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            throw failure
        }
    }

    /** For when there is no document picker, as before KitKat: saves straight to Downloads. */
    suspend fun exportToDownloads(fileName: String) = withContext(dispatchers.io) {
        val file = File(context.cacheDir, fileName)
        try {
            writeZip(file.outputStream())
            saveMedia(context, file, fileName, MIME_TYPE, notificationUtils, clock.epochMillis())
        } finally {
            file.delete()
        }
    }

    @VisibleForTesting
    internal fun writeZip(output: OutputStream) {
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.putNextEntry(ZipEntry("info.txt"))
            zip.write(info().toByteArray())
            zip.closeEntry()
            logFiles().forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun logFiles(): List<File> {
        val logs = File(context.cacheDir, LOG_DIR).listFiles { file -> file.isFile && !file.name.endsWith(".lck") }.orEmpty()
        val crash = File(context.cacheDir, CRASH_FILE).takeIf { it.isFile }
        return logs.sortedBy { it.name } + listOfNotNull(crash)
    }

    private fun info() = buildString {
        appendLine("${buildMeta.applicationName} ${buildMeta.versionName} (${if (buildMeta.isDebug) "debug" else "release"})")
        appendLine("Revision: ${buildMeta.gitRevision} ${buildMeta.gitRevisionDate}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Exported: ${Date(clock.epochMillis())}")
    }

    companion object {
        const val MIME_TYPE = "application/zip"
        private const val LOG_DIR = "logs"
        private const val CRASH_FILE = "crash.log"
    }
}
