/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.contentscanner.db

import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.contentscanner.ScanState
import org.matrix.android.sdk.api.session.contentscanner.ScanStatusInfo
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOptional
import org.matrix.android.sdk.internal.database.sqldelight.asLiveOneOrNull
import org.matrix.android.sdk.internal.di.ContentScannerDatabase
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.contentscanner.data.ContentScannerStore
import org.matrix.android.sdk.internal.util.isValidUrl
import org.matrix.android.sdk.internal.util.time.Clock
import javax.inject.Inject

@SessionScope
internal class SqlContentScannerStore @Inject constructor(
        @ContentScannerDatabase private val database: ContentScannerSqlDatabase,
        @ContentScannerDatabase private val dispatcher: CoroutineDispatcher,
        private val clock: Clock,
) : ContentScannerStore {

    private val infoQueries get() = database.contentScannerInfoQueries
    private val scanResultQueries get() = database.contentScanResultQueries

    override fun getScannerUrl(): String? =
            infoQueries.selectFirst().executeAsOneOrNull()?.server_url

    override fun setScannerUrl(url: String?) {
        database.transaction {
            if (infoQueries.selectFirst().executeAsOneOrNull() != null) {
                infoQueries.updateServerUrl(url)
            } else {
                infoQueries.insert(server_url = url, enabled = null)
            }
        }
    }

    override fun enableScanner(enabled: Boolean) {
        database.transaction {
            if (infoQueries.selectFirst().executeAsOneOrNull() != null) {
                infoQueries.updateEnabled(enabled.toLong())
            } else {
                infoQueries.insert(server_url = null, enabled = enabled.toLong())
            }
        }
    }

    override fun isScanEnabled(): Boolean {
        val info = infoQueries.selectFirst().executeAsOneOrNull() ?: return false
        return info.enabled == 1L && info.server_url?.isValidUrl().orFalse()
    }

    override fun updateStateForContent(mxcUrl: String, state: ScanState, scannerUrl: String?) {
        database.transaction {
            val existing = findScanResult(mxcUrl, scannerUrl)
            if (existing != null) {
                scanResultQueries.updateState(state.name, existing.media_url, existing.scanner_url)
            } else {
                scanResultQueries.insert(mxcUrl, state.name, null, clock.epochMillis(), scannerUrl)
            }
        }
    }

    override fun updateScanResultForContent(mxcUrl: String, scannerUrl: String?, state: ScanState, humanReadable: String) {
        database.transaction {
            val now = clock.epochMillis()
            val existing = findScanResult(mxcUrl, scannerUrl)
            if (existing != null) {
                scanResultQueries.updateResult(state.name, now, humanReadable, existing.media_url, existing.scanner_url)
            } else {
                scanResultQueries.insert(mxcUrl, state.name, humanReadable, now, scannerUrl)
            }
        }
    }

    override fun isScanResultKnownOrInProgress(mxcUrl: String, scannerUrl: String?): Boolean {
        return when (findScanResult(mxcUrl, scannerUrl)?.toScanState()) {
            ScanState.IN_PROGRESS,
            ScanState.TRUSTED,
            ScanState.INFECTED -> true
            else -> false
        }
    }

    override fun getScanResult(mxcUrl: String): ScanStatusInfo? =
            findScanResult(mxcUrl, getScannerUrl())?.toScanStatusInfo()

    override fun getLiveScanResult(mxcUrl: String): LiveData<Optional<ScanStatusInfo>> {
        // Matches the Realm version: always an exact (null-safe) scanner_url match.
        return scanResultQueries.selectByMediaAndScanner(mxcUrl, getScannerUrl())
                .asLiveOneOrNull(dispatcher)
                .map { it?.toScanStatusInfo().toOptional() }
    }

    private fun findScanResult(mediaUrl: String, scannerUrl: String?): Content_scan_result_entity? =
            if (scannerUrl != null) {
                scanResultQueries.selectByMediaAndScanner(mediaUrl, scannerUrl).executeAsOneOrNull()
            } else {
                scanResultQueries.selectByMedia(mediaUrl).executeAsOneOrNull()
            }

    private fun Content_scan_result_entity.toScanState(): ScanState =
            scan_status_string?.let { tryOrNull { ScanState.valueOf(it) } } ?: ScanState.UNKNOWN

    private fun Content_scan_result_entity.toScanStatusInfo(): ScanStatusInfo =
            ScanStatusInfo(
                    state = toScanState(),
                    humanReadableMessage = human_readable_message,
                    scanDateTimestamp = scan_date_timestamp,
            )

    private fun Boolean.toLong(): Long = if (this) 1L else 0L
}
