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
import org.matrix.android.sdk.api.auth.data.Credentials
import org.matrix.android.sdk.api.auth.data.HomeServerConnectionConfig
import org.matrix.android.sdk.api.auth.data.SessionParams
import org.matrix.android.sdk.api.auth.data.sessionId
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.matrix.android.sdk.internal.database.sqldelight.newDatabaseDispatcher
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.matrix.android.sdk.test.fixtures.CredentialsFixture.aCredentials
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SqlSessionParamsStoreTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var store: SqlSessionParamsStore

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = AuthSqlDatabase.Schema)
        val mapper = SessionParamsMapper(MoshiProvider.providesMoshi())
        store = SqlSessionParamsStore(mapper, AuthSqlDatabase(driver), newDatabaseDispatcher("test-auth"))
    }

    @After
    fun tearDown() {
        driver.close()
    }

    private fun sessionParams(userId: String, accessToken: String = "", deviceId: String = "DEV", isTokenValid: Boolean = false) = SessionParams(
            credentials = aCredentials(userId = userId, accessToken = accessToken, deviceId = deviceId),
            homeServerConnectionConfig = HomeServerConnectionConfig.Builder().withHomeServerUri("https://homeserver.org").build(),
            isTokenValid = isTokenValid,
            loginType = org.matrix.android.sdk.api.auth.LoginType.PASSWORD,
    )

    private fun credentials(userId: String, accessToken: String = "", deviceId: String = "DEV"): Credentials =
            aCredentials(userId = userId, accessToken = accessToken, deviceId = deviceId)

    @Test
    fun `save then get round-trips the stored fields`() {
        runBlocking {
            val params = sessionParams(userId = "@a:hs", accessToken = "tokA", deviceId = "DEVA", isTokenValid = true)
            store.save(params)

            val loaded = store.get(params.credentials.sessionId())
            loaded shouldBeEqualTo params
        }
    }

    @Test
    fun `getAll and getLast reflect inserts`() {
        runBlocking {
            store.getAll() shouldBeEqualTo emptyList()

            store.save(sessionParams(userId = "@a:hs", deviceId = "DEVA"))
            store.save(sessionParams(userId = "@b:hs", deviceId = "DEVB"))

            store.getAll().size shouldBeEqualTo 2
            store.getLast()!!.credentials.userId shouldBeEqualTo "@b:hs"
        }
    }

    @Test
    fun `setTokenInvalid flips the flag`() {
        runBlocking {
            val params = sessionParams(userId = "@a:hs", deviceId = "DEVA", isTokenValid = true)
            store.save(params)

            store.setTokenInvalid(params.credentials.sessionId())

            store.get(params.credentials.sessionId())!!.isTokenValid shouldBe false
        }
    }

    @Test
    fun `updateCredentials replaces credentials and revalidates the token`() {
        runBlocking {
            store.save(sessionParams(userId = "@a:hs", accessToken = "old", deviceId = "DEVA", isTokenValid = false))

            val newCredentials = credentials(userId = "@a:hs", accessToken = "new", deviceId = "DEVA")
            store.updateCredentials(newCredentials)

            val loaded = store.get(newCredentials.sessionId())!!
            loaded.credentials.accessToken shouldBeEqualTo "new"
            loaded.isTokenValid shouldBe true
        }
    }

    @Test
    fun `delete removes a single session`() {
        runBlocking {
            val params = sessionParams(userId = "@a:hs", deviceId = "DEVA")
            store.save(params)

            store.delete(params.credentials.sessionId())

            store.get(params.credentials.sessionId()) shouldBe null
        }
    }

    @Test
    fun `deleteAll clears the store`() {
        runBlocking {
            store.save(sessionParams(userId = "@a:hs", deviceId = "DEVA"))
            store.save(sessionParams(userId = "@b:hs", deviceId = "DEVB"))

            store.deleteAll()

            store.getAll() shouldBeEqualTo emptyList()
        }
    }
}
