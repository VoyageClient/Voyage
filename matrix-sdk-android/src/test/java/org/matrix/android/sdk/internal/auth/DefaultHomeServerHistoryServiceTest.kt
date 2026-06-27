/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.auth

import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.internal.database.global.GlobalSqlDatabase
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DefaultHomeServerHistoryServiceTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var service: DefaultHomeServerHistoryService

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = GlobalSqlDatabase.Schema)
        service = DefaultHomeServerHistoryService(GlobalSqlDatabase(driver))
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `known servers are empty initially`() {
        service.getKnownServersUrls() shouldBeEqualTo emptyList()
    }

    @Test
    fun `add then list returns the url`() {
        service.addHomeServerToHistory("https://matrix.org")

        service.getKnownServersUrls() shouldBeEqualTo listOf("https://matrix.org")
    }

    @Test
    fun `adding the same url twice does not duplicate`() {
        service.addHomeServerToHistory("https://matrix.org")
        service.addHomeServerToHistory("https://matrix.org")

        service.getKnownServersUrls() shouldBeEqualTo listOf("https://matrix.org")
    }

    @Test
    fun `clear history empties the list`() {
        service.addHomeServerToHistory("https://matrix.org")

        service.clearHistory()

        service.getKnownServersUrls() shouldBeEqualTo emptyList()
    }
}
