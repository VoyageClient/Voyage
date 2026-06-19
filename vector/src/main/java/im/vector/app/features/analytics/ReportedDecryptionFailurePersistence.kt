/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.analytics

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

private const val REPORTED_UTD_FILE_NAME = "im.vector.analytics.reported_utd"
private const val MAX_ENTRIES = 5000

/**
 * This class is used to keep track of the reported decryption failures to avoid double reporting.
 * Reported event ids are kept in a bounded set, persisted as newline-separated ids in the cache dir.
 */
class ReportedDecryptionFailurePersistence @Inject constructor(
        private val context: Context,
) {

    // Insertion-ordered so we can evict the oldest entries once we reach the cap.
    private val reportedFailures = LinkedHashSet<String>()

    /**
     * Mark an event as reported.
     * @param eventId the event id to mark as reported.
     */
    suspend fun markAsReported(eventId: String) {
        if (reportedFailures.add(eventId)) {
            while (reportedFailures.size > MAX_ENTRIES) {
                reportedFailures.remove(reportedFailures.first())
            }
            persist()
        }
    }

    /**
     * Check if an event has been reported.
     * @param eventId the event id to check.
     * @return true if the event has been reported.
     */
    fun hasBeenReported(eventId: String): Boolean {
        return reportedFailures.contains(eventId)
    }

    /**
     * Load the reported failures from disk.
     */
    suspend fun load() {
        withContext(Dispatchers.IO) {
            try {
                val file = File(context.applicationContext.cacheDir, REPORTED_UTD_FILE_NAME)
                if (file.exists()) {
                    reportedFailures.clear()
                    file.readLines().filterTo(reportedFailures) { it.isNotEmpty() }
                }
            } catch (e: Throwable) {
                Timber.e(e, "## Failed to load reported failures")
            }
        }
    }

    /**
     * Persist the reported failures to disk.
     */
    suspend fun persist() {
        withContext(Dispatchers.IO) {
            try {
                val file = File(context.applicationContext.cacheDir, REPORTED_UTD_FILE_NAME)
                file.writeText(reportedFailures.joinToString("\n"))
            } catch (e: Throwable) {
                Timber.e(e, "## Failed to save reported failures")
            }
        }
    }
}
