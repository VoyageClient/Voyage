/*
 * Copyright 2018-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.rageshake

import android.content.Context
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the last crash so it can be copied on next launch. Remote bug-report upload has been
 * removed from this fork.
 */
@Singleton
class BugReporter @Inject constructor(
        private val context: Context,
) {

    private companion object {
        const val CRASH_FILENAME = "crash.log"
    }

    private fun getCrashFile(): File {
        return File(context.cacheDir.absolutePath, CRASH_FILENAME)
    }

    /**
     * Remove the crash file.
     */
    fun deleteCrashFile() {
        val crashFile = getCrashFile()
        if (crashFile.exists()) {
            crashFile.delete()
        }
    }

    /**
     * Save the crash report.
     *
     * @param crashDescription the crash description
     */
    fun saveCrashReport(crashDescription: String) {
        val crashFile = getCrashFile()
        if (crashFile.exists()) {
            crashFile.delete()
        }

        if (crashDescription.isNotEmpty()) {
            try {
                crashFile.writeText(crashDescription)
            } catch (e: Exception) {
                Timber.e(e, "## saveCrashReport() : fail to write $e")
            }
        }
    }

    /**
     * Read the crash description file and return its content.
     *
     * @return the crash description
     */
    fun getCrashDescription(): String? {
        val crashFile = getCrashFile()
        if (crashFile.exists()) {
            try {
                return crashFile.readText()
            } catch (e: Exception) {
                Timber.e(e, "## getCrashDescription() : fail to read $e")
            }
        }
        return null
    }
}
