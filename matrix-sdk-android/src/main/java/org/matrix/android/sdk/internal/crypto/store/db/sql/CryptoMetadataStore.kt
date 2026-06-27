/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.store.db.sql

import org.matrix.android.sdk.api.session.crypto.GlobalCryptoConfig
import org.matrix.android.sdk.api.session.crypto.crosssigning.PrivateKeysInfo
import org.matrix.android.sdk.api.session.crypto.keysbackup.BackupUtils
import org.matrix.android.sdk.api.session.crypto.keysbackup.SavedKeyBackupKeyInfo
import org.matrix.android.sdk.internal.crypto.store.db.model.KeysBackupDataEntity

/**
 * The crypto_metadata singleton (OlmAccount blob, global flags, cross-signing private keys, key
 * backup) and the keys_backup_data singleton. All accessors are synchronous, matching the legacy
 * Realm store. The OlmAccount itself is held/managed by the owning store (it is a native object);
 * here we only persist its serialized blob via [getOlmAccountData]/[setOlmAccountData].
 */
internal class CryptoMetadataStore(private val database: CryptoSqlDatabase) {

    private val queries get() = database.cryptoMetadataQueries
    private val backupQueries get() = database.cryptoBackupAuditQueries

    fun ensureExists(userId: String, deviceId: String) {
        database.transaction {
            if (queries.count().executeAsOne() == 0L) {
                queries.insert(userId, deviceId)
            }
        }
    }

    fun hasData(): Boolean = queries.count().executeAsOne() > 0L

    fun getStoredUserId(): String? = queries.selectFirst().executeAsOneOrNull()?.user_id

    fun getDeviceId(): String = queries.selectFirst().executeAsOneOrNull()?.device_id ?: ""

    fun storeDeviceId(deviceId: String) = queries.updateDeviceId(deviceId)

    fun getOlmAccountData(): String? = queries.selectFirst().executeAsOneOrNull()?.olm_account_data

    fun setOlmAccountData(data: String?) = queries.updateOlmAccountData(data)

    fun getDeviceSyncToken(): String? = queries.selectFirst().executeAsOneOrNull()?.device_sync_token

    fun setDeviceSyncToken(token: String?) = queries.updateDeviceSyncToken(token)

    // ==================== Global flags ====================

    fun isKeyGossipingEnabled(): Boolean =
            queries.selectFirst().executeAsOneOrNull()?.global_enable_key_gossiping?.let { it != 0L } ?: true

    fun enableKeyGossiping(enable: Boolean) = queries.updateKeyGossiping(enable.toLong())

    fun getGlobalBlacklistUnverifiedDevices(): Boolean =
            queries.selectFirst().executeAsOneOrNull()?.global_blacklist_unverified_devices == 1L

    fun setGlobalBlacklistUnverifiedDevices(block: Boolean) = queries.updateGlobalBlacklist(block.toLong())

    fun isShareKeysOnInviteEnabled(): Boolean =
            queries.selectFirst().executeAsOneOrNull()?.enable_key_forwarding_on_invite == 1L

    fun enableShareKeyOnInvite(enable: Boolean) = queries.updateShareKeyOnInvite(enable.toLong())

    fun getGlobalCryptoConfig(): GlobalCryptoConfig {
        val row = queries.selectFirst().executeAsOneOrNull() ?: return GlobalCryptoConfig(false, false, false)
        return GlobalCryptoConfig(
                globalBlockUnverifiedDevices = row.global_blacklist_unverified_devices == 1L,
                globalEnableKeyGossiping = row.global_enable_key_gossiping == 1L,
                enableKeyForwardingOnInvite = row.enable_key_forwarding_on_invite == 1L,
        )
    }

    // ==================== Device keys upload ====================

    fun setDeviceKeysUploaded(uploaded: Boolean) = queries.updateDeviceKeysUploaded(uploaded.toLong())

    fun areDeviceKeysUploaded(): Boolean =
            queries.selectFirst().executeAsOneOrNull()?.device_keys_sent_to_server == 1L

    // ==================== Cross-signing private keys ====================

    fun getCrossSigningPrivateKeys(): PrivateKeysInfo? =
            queries.selectFirst().executeAsOneOrNull()?.let {
                PrivateKeysInfo(
                        master = it.x_sign_master_private_key,
                        selfSigned = it.x_sign_self_signed_private_key,
                        user = it.x_sign_user_private_key,
                )
            }

    fun storePrivateKeysInfo(msk: String?, usk: String?, ssk: String?) = queries.updatePrivateKeys(msk, usk, ssk)

    fun storeMSKPrivateKey(msk: String?) = queries.updateMskPrivateKey(msk)

    fun storeSSKPrivateKey(ssk: String?) = queries.updateSskPrivateKey(ssk)

    fun storeUSKPrivateKey(usk: String?) = queries.updateUskPrivateKey(usk)

    // ==================== Key backup ====================

    fun getKeyBackupVersion(): String? = queries.selectFirst().executeAsOneOrNull()?.backup_version

    fun setKeyBackupVersion(keyBackupVersion: String?) = queries.updateBackupVersion(keyBackupVersion)

    fun saveBackupRecoveryKey(recoveryKey: String?, version: String?) = queries.updateRecoveryKey(recoveryKey, version)

    fun getKeyBackupRecoveryKeyInfo(): SavedKeyBackupKeyInfo? {
        val row = queries.selectFirst().executeAsOneOrNull() ?: return null
        val key = row.key_backup_recovery_key
        val version = row.key_backup_recovery_key_version
        return if (!key.isNullOrBlank() && !version.isNullOrBlank()) {
            BackupUtils.recoveryKeyFromBase58(key)?.let { recoveryKey ->
                SavedKeyBackupKeyInfo(recoveryKey = recoveryKey, version = version)
            }
        } else {
            null
        }
    }

    fun getKeysBackupData(): KeysBackupDataEntity? =
            backupQueries.backupDataSelectFirst().executeAsOneOrNull()?.let {
                KeysBackupDataEntity(
                        it.primary_key.toInt(),
                        it.backup_last_server_hash,
                        it.backup_last_server_number_of_keys?.toInt(),
                )
            }

    fun setKeysBackupData(keysBackupData: KeysBackupDataEntity?) {
        if (keysBackupData == null) {
            backupQueries.backupDataDeleteAll()
        } else {
            backupQueries.backupDataUpsert(
                    keysBackupData.backupLastServerHash,
                    keysBackupData.backupLastServerNumberOfKeys?.toLong(),
            )
        }
    }

    private fun Boolean.toLong(): Long = if (this) 1L else 0L
}
