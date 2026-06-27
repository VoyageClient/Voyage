/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.identity.db

import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.identity.ThreePid
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.matrix.android.sdk.internal.session.identity.data.IdentityPendingBinding
import org.matrix.android.sdk.internal.session.identity.model.IdentityHashDetailResponse
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SqlIdentityStoreTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var store: SqlIdentityStore

    private val threePid = ThreePid.Email("test@example.com")

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = IdentitySqlDatabase.Schema)
        store = SqlIdentityStore(IdentitySqlDatabase(driver))
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `identity data is null initially`() {
        store.getIdentityData() shouldBe null
    }

    @Test
    fun `setUrl creates the singleton`() {
        store.setUrl("https://identity.example.org")

        store.getIdentityData()!!.identityServerUrl shouldBeEqualTo "https://identity.example.org"
    }

    @Test
    fun `setUrl with null clears the identity data`() {
        store.setUrl("https://identity.example.org")

        store.setUrl(null)

        store.getIdentityData() shouldBe null
    }

    @Test
    fun `token, consent and hash details update the singleton`() {
        store.setUrl("https://identity.example.org")
        store.setToken("token-123")
        store.setUserConsent(true)
        store.setHashDetails(IdentityHashDetailResponse(pepper = "pep", algorithms = listOf("sha256", "none")))

        val data = store.getIdentityData()!!
        data.token shouldBeEqualTo "token-123"
        data.userConsent shouldBe true
        data.hashLookupPepper shouldBeEqualTo "pep"
        data.hashLookupAlgorithm shouldBeEqualTo listOf("sha256", "none")
    }

    @Test
    fun `setToken is a no-op when there is no identity data`() {
        store.setToken("token-123")

        store.getIdentityData() shouldBe null
    }

    @Test
    fun `pending binding round-trips`() {
        store.storePendingBinding(threePid, IdentityPendingBinding(clientSecret = "secret", sendAttempt = 2, sid = "sid-1"))

        val loaded = store.getPendingBinding(threePid)!!
        loaded.clientSecret shouldBeEqualTo "secret"
        loaded.sendAttempt shouldBeEqualTo 2
        loaded.sid shouldBeEqualTo "sid-1"
    }

    @Test
    fun `storePendingBinding replaces an existing binding`() {
        store.storePendingBinding(threePid, IdentityPendingBinding(clientSecret = "s1", sendAttempt = 1, sid = "sid-1"))
        store.storePendingBinding(threePid, IdentityPendingBinding(clientSecret = "s2", sendAttempt = 2, sid = "sid-2"))

        store.getPendingBinding(threePid)!!.clientSecret shouldBeEqualTo "s2"
    }

    @Test
    fun `deletePendingBinding removes the binding`() {
        store.storePendingBinding(threePid, IdentityPendingBinding(clientSecret = "secret", sendAttempt = 1, sid = "sid"))

        store.deletePendingBinding(threePid)

        store.getPendingBinding(threePid) shouldBe null
    }

    @Test
    fun `setUrl clears pending bindings`() {
        store.setUrl("https://identity.example.org")
        store.storePendingBinding(threePid, IdentityPendingBinding(clientSecret = "secret", sendAttempt = 1, sid = "sid"))

        store.setUrl("https://other.example.org")

        store.getPendingBinding(threePid) shouldBe null
    }
}
