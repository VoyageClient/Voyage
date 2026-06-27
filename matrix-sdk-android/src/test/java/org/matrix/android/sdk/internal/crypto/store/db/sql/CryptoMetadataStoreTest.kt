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
import org.matrix.android.sdk.internal.crypto.store.db.model.KeysBackupDataEntity
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CryptoMetadataStoreTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var store: CryptoMetadataStore

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = CryptoSqlDatabase.Schema)
        store = CryptoMetadataStore(CryptoSqlDatabase(driver))
        store.ensureExists("@me:hs", "MYDEVICE")
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `ensureExists creates a single metadata row`() {
        store.hasData() shouldBe true
        store.getStoredUserId() shouldBeEqualTo "@me:hs"
        store.getDeviceId() shouldBeEqualTo "MYDEVICE"

        // Idempotent
        store.ensureExists("@me:hs", "MYDEVICE")
        store.hasData() shouldBe true
    }

    @Test
    fun `device id round-trips`() {
        store.storeDeviceId("NEWDEVICE")
        store.getDeviceId() shouldBeEqualTo "NEWDEVICE"
    }

    @Test
    fun `olm account data round-trips`() {
        store.getOlmAccountData() shouldBe null
        store.setOlmAccountData("serialized-account-blob")
        store.getOlmAccountData() shouldBeEqualTo "serialized-account-blob"
    }

    @Test
    fun `global flags round-trip with the correct defaults`() {
        // Defaults from the schema
        store.isKeyGossipingEnabled() shouldBe true
        store.getGlobalBlacklistUnverifiedDevices() shouldBe false
        store.isShareKeysOnInviteEnabled() shouldBe false

        store.enableKeyGossiping(false)
        store.setGlobalBlacklistUnverifiedDevices(true)
        store.enableShareKeyOnInvite(true)

        store.isKeyGossipingEnabled() shouldBe false
        store.getGlobalBlacklistUnverifiedDevices() shouldBe true
        store.isShareKeysOnInviteEnabled() shouldBe true

        val config = store.getGlobalCryptoConfig()
        config.globalBlockUnverifiedDevices shouldBe true
        config.globalEnableKeyGossiping shouldBe false
        config.enableKeyForwardingOnInvite shouldBe true
    }

    @Test
    fun `device keys uploaded flag round-trips`() {
        store.areDeviceKeysUploaded() shouldBe false
        store.setDeviceKeysUploaded(true)
        store.areDeviceKeysUploaded() shouldBe true
    }

    @Test
    fun `cross-signing private keys round-trip`() {
        store.getCrossSigningPrivateKeys()!!.master shouldBe null

        store.storePrivateKeysInfo(msk = "MSK", usk = "USK", ssk = "SSK")
        store.getCrossSigningPrivateKeys()!!.let {
            it.master shouldBeEqualTo "MSK"
            it.user shouldBeEqualTo "USK"
            it.selfSigned shouldBeEqualTo "SSK"
        }

        store.storeMSKPrivateKey("MSK2")
        store.storeSSKPrivateKey("SSK2")
        store.storeUSKPrivateKey("USK2")
        store.getCrossSigningPrivateKeys()!!.let {
            it.master shouldBeEqualTo "MSK2"
            it.selfSigned shouldBeEqualTo "SSK2"
            it.user shouldBeEqualTo "USK2"
        }
    }

    @Test
    fun `key backup version round-trips`() {
        store.getKeyBackupVersion() shouldBe null
        store.setKeyBackupVersion("3")
        store.getKeyBackupVersion() shouldBeEqualTo "3"
        store.setKeyBackupVersion(null)
        store.getKeyBackupVersion() shouldBe null
    }

    @Test
    fun `keys backup data set, get and clear`() {
        store.getKeysBackupData() shouldBe null

        store.setKeysBackupData(KeysBackupDataEntity(0, "server-hash", 42))
        store.getKeysBackupData()!!.let {
            it.backupLastServerHash shouldBeEqualTo "server-hash"
            it.backupLastServerNumberOfKeys shouldBeEqualTo 42
        }

        store.setKeysBackupData(null)
        store.getKeysBackupData() shouldBe null
    }
}
