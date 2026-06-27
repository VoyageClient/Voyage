/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.store.db.sql

import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.crypto.crosssigning.CryptoCrossSigningKey
import org.matrix.android.sdk.api.session.crypto.crosssigning.KeyUsage
import org.matrix.android.sdk.api.session.crypto.crosssigning.MXCrossSigningInfo
import org.matrix.android.sdk.internal.crypto.store.db.mapper.CrossSigningKeysMapper
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CrossSigningSqlStoreTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var store: CrossSigningSqlStore

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = CryptoSqlDatabase.Schema)
        store = CrossSigningSqlStore(CryptoSqlDatabase(driver), CrossSigningKeysMapper(MoshiProvider.providesMoshi()))
    }

    @After
    fun tearDown() {
        driver.close()
    }

    private fun key(usage: KeyUsage, pubKey: String) = CryptoCrossSigningKey(
            userId = "@a:hs",
            usages = listOf(usage.value),
            keys = mapOf("ed25519:$pubKey" to pubKey),
            signatures = mapOf("@a:hs" to mapOf("ed25519:DEV" to "signature")),
    )

    private fun info(userId: String) = MXCrossSigningInfo(
            userId = userId,
            crossSigningKeys = listOf(
                    key(KeyUsage.MASTER, "MK"),
                    key(KeyUsage.SELF_SIGNING, "SSK"),
                    key(KeyUsage.USER_SIGNING, "USK"),
            ),
            wasTrustedOnce = false,
    )

    @Test
    fun `set and get cross-signing info round-trips`() {
        store.setCrossSigningInfo("@a:hs", info("@a:hs"))

        val loaded = store.getCrossSigningInfo("@a:hs")!!
        loaded.userId shouldBeEqualTo "@a:hs"
        loaded.crossSigningKeys.size shouldBeEqualTo 3
        loaded.masterKey()!!.unpaddedBase64PublicKey shouldBeEqualTo "MK"
        loaded.selfSigningKey()!!.unpaddedBase64PublicKey shouldBeEqualTo "SSK"
        loaded.userKey()!!.unpaddedBase64PublicKey shouldBeEqualTo "USK"
        loaded.masterKey()!!.trustLevel shouldBe null
    }

    @Test
    fun `setting null cross-signing info deletes it`() {
        store.setCrossSigningInfo("@a:hs", info("@a:hs"))
        store.setCrossSigningInfo("@a:hs", null)

        store.getCrossSigningInfo("@a:hs") shouldBe null
    }

    @Test
    fun `setUserKeysAsTrusted marks all keys verified`() {
        store.setCrossSigningInfo("@a:hs", info("@a:hs"))

        store.setUserKeysAsTrusted("@a:hs", true)

        val mk = store.getCrossSigningInfo("@a:hs")!!.masterKey()!!
        mk.trustLevel!!.crossSigningVerified shouldBe true
        mk.trustLevel!!.locallyVerified shouldBe true
    }

    @Test
    fun `clearOtherUserTrust clears everyone except me`() {
        store.setCrossSigningInfo("@a:hs", info("@a:hs"))
        store.setCrossSigningInfo("@b:hs", info("@b:hs"))
        store.setUserKeysAsTrusted("@a:hs", true)
        store.setUserKeysAsTrusted("@b:hs", true)

        store.clearOtherUserTrust("@a:hs")

        store.getCrossSigningInfo("@a:hs")!!.masterKey()!!.trustLevel!!.crossSigningVerified shouldBe true
        store.getCrossSigningInfo("@b:hs")!!.masterKey()!!.trustLevel shouldBe null
    }

    @Test
    fun `markMasterKeyAsLocallyTrusted only touches local trust on the master key`() {
        store.setCrossSigningInfo("@a:hs", info("@a:hs"))

        store.markMasterKeyAsLocallyTrusted("@a:hs", true)

        val mk = store.getCrossSigningInfo("@a:hs")!!.masterKey()!!
        mk.trustLevel!!.locallyVerified shouldBe true
        mk.trustLevel!!.crossSigningVerified shouldBe false
    }
}
