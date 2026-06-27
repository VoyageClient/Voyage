/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.contentscanner.db

import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.contentscanner.ScanState
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.matrix.android.sdk.internal.database.sqldelight.newDatabaseDispatcher
import org.matrix.android.sdk.internal.util.time.Clock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SqlContentScannerStoreTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var store: SqlContentScannerStore

    private val scannerUrl = "https://scanner.example.org"
    private val mediaUrl = "mxc://example.org/media123"

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = ContentScannerSqlDatabase.Schema)
        store = SqlContentScannerStore(
                ContentScannerSqlDatabase(driver),
                newDatabaseDispatcher("test-content-scanner"),
                object : Clock {
                    override fun epochMillis() = 1234L
                },
        )
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `scanner url round-trips`() {
        store.getScannerUrl() shouldBe null

        store.setScannerUrl(scannerUrl)

        store.getScannerUrl() shouldBeEqualTo scannerUrl
    }

    @Test
    fun `isScanEnabled requires the scanner to be enabled with a valid url`() {
        store.setScannerUrl(scannerUrl)
        store.isScanEnabled() shouldBe false

        store.enableScanner(true)
        store.isScanEnabled() shouldBe true

        store.setScannerUrl("not-a-valid-url")
        store.isScanEnabled() shouldBe false

        store.setScannerUrl(scannerUrl)
        store.enableScanner(false)
        store.isScanEnabled() shouldBe false
    }

    @Test
    fun `scan result round-trips`() {
        store.setScannerUrl(scannerUrl)
        store.updateScanResultForContent(mediaUrl, scannerUrl, ScanState.TRUSTED, "All good")

        val result = store.getScanResult(mediaUrl)!!
        result.state shouldBeEqualTo ScanState.TRUSTED
        result.humanReadableMessage shouldBeEqualTo "All good"
    }

    @Test
    fun `updateStateForContent changes the state but preserves the message`() {
        store.setScannerUrl(scannerUrl)
        store.updateScanResultForContent(mediaUrl, scannerUrl, ScanState.IN_PROGRESS, "Scanning")

        store.updateStateForContent(mediaUrl, ScanState.INFECTED, scannerUrl)

        val result = store.getScanResult(mediaUrl)!!
        result.state shouldBeEqualTo ScanState.INFECTED
        result.humanReadableMessage shouldBeEqualTo "Scanning"
    }

    @Test
    fun `isScanResultKnownOrInProgress reflects the state`() {
        store.isScanResultKnownOrInProgress(mediaUrl, scannerUrl) shouldBe false

        store.updateStateForContent(mediaUrl, ScanState.IN_PROGRESS, scannerUrl)
        store.isScanResultKnownOrInProgress(mediaUrl, scannerUrl) shouldBe true

        store.updateStateForContent(mediaUrl, ScanState.UNKNOWN, scannerUrl)
        store.isScanResultKnownOrInProgress(mediaUrl, scannerUrl) shouldBe false
    }

    @Test
    fun `getLiveScanResult reflects the stored result`() {
        store.setScannerUrl(scannerUrl)
        store.updateScanResultForContent(mediaUrl, scannerUrl, ScanState.TRUSTED, "ok")

        val value = store.getLiveScanResult(mediaUrl).awaitValue()

        value.getOrNull()?.state shouldBeEqualTo ScanState.TRUSTED
    }

    private fun <T> LiveData<T>.awaitValue(timeoutMs: Long = 3000): T {
        var result: Any? = NO_VALUE
        val observer = Observer<T> { result = it }
        observeForever(observer)
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (result === NO_VALUE && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                if (result === NO_VALUE) Thread.sleep(10)
            }
        } finally {
            removeObserver(observer)
        }
        check(result !== NO_VALUE) { "LiveData did not emit within ${timeoutMs}ms" }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    companion object {
        private val NO_VALUE = Any()
    }
}
