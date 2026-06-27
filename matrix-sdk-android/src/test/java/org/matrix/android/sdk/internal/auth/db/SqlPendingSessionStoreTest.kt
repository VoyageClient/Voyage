/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.auth.db

import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.auth.data.HomeServerConnectionConfig
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.matrix.android.sdk.internal.database.sqldelight.newDatabaseDispatcher
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SqlPendingSessionStoreTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var store: SqlPendingSessionStore

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = AuthSqlDatabase.Schema)
        val mapper = PendingSessionMapper(MoshiProvider.providesMoshi())
        store = SqlPendingSessionStore(mapper, AuthSqlDatabase(driver), newDatabaseDispatcher("test-pending"))
    }

    @After
    fun tearDown() {
        driver.close()
    }

    private fun pendingSessionData(clientSecret: String = "secret", sendAttempt: Int = 3) = PendingSessionData(
            homeServerConnectionConfig = HomeServerConnectionConfig.Builder().withHomeServerUri("https://homeserver.org").build(),
            clientSecret = clientSecret,
            sendAttempt = sendAttempt,
            resetPasswordData = null,
            currentSession = "current-session",
            isRegistrationStarted = true,
            currentThreePidData = null,
    )

    @Test
    fun `save then get round-trips the stored fields`() {
        runBlocking {
            store.savePendingSessionData(pendingSessionData(clientSecret = "secret", sendAttempt = 5))

            val loaded = store.getPendingSessionData()!!
            loaded.clientSecret shouldBeEqualTo "secret"
            loaded.sendAttempt shouldBeEqualTo 5
            loaded.currentSession shouldBeEqualTo "current-session"
            loaded.isRegistrationStarted shouldBe true
        }
    }

    @Test
    fun `save replaces the singleton pending session`() {
        runBlocking {
            store.savePendingSessionData(pendingSessionData(clientSecret = "first"))
            store.savePendingSessionData(pendingSessionData(clientSecret = "second"))

            store.getPendingSessionData()!!.clientSecret shouldBeEqualTo "second"
        }
    }

    @Test
    fun `delete clears the pending session`() {
        runBlocking {
            store.savePendingSessionData(pendingSessionData())

            store.delete()

            store.getPendingSessionData() shouldBe null
        }
    }
}
