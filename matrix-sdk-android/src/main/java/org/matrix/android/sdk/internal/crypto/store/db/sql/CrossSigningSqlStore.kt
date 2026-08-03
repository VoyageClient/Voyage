/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.store.db.sql

import org.matrix.android.sdk.api.session.crypto.crosssigning.CryptoCrossSigningKey
import org.matrix.android.sdk.api.session.crypto.crosssigning.KeyUsage
import org.matrix.android.sdk.api.session.crypto.crosssigning.MXCrossSigningInfo
import org.matrix.android.sdk.internal.crypto.store.db.mapper.CrossSigningKeysMapper
import org.matrix.android.sdk.internal.crypto.store.db.model.KeyInfoEntity
import org.matrix.android.sdk.internal.crypto.store.db.model.TrustLevelEntity

/**
 * SQL layer for cross-signing info: the cross_signing_info row plus its key_info child rows (one per
 * usage), reusing [CrossSigningKeysMapper] via unmanaged entities. Trust levels are flattened columns.
 */
internal class CrossSigningSqlStore(
        private val database: CryptoSqlDatabase,
        private val crossSigningKeysMapper: CrossSigningKeysMapper,
) {

    private val queries get() = database.cryptoCrossSigningQueries

    fun getCrossSigningInfo(userId: String): MXCrossSigningInfo? {
        val info = queries.xsignSelectByUserId(userId).executeAsOneOrNull() ?: return null
        val keys = queries.keyInfoSelectByUser(userId).executeAsList()
                .mapNotNull { crossSigningKeysMapper.map(userId, it.toEntity()) }
        return MXCrossSigningInfo(
                userId = userId,
                crossSigningKeys = keys,
                wasTrustedOnce = info.was_user_verified_once == 1L,
        )
    }

    fun setCrossSigningInfo(userId: String, info: MXCrossSigningInfo?) {
        database.transaction {
            if (info == null) {
                queries.keyInfoDeleteByUser(userId)
                queries.xsignDelete(userId)
            } else {
                queries.xsignInsertIgnore(userId)
                // Override the existing keys, resetting trust (caller untrusts if needed).
                queries.keyInfoDeleteByUser(userId)
                info.crossSigningKeys.forEach { insertKey(userId, crossSigningKeysMapper.map(it)) }
            }
        }
    }

    /**
     * Replace a user's cross-signing keys, preserving the trust level of any key whose public key is
     * unchanged (used by storeUserIdentity, which must not drop trust on keys that did not rotate).
     */
    fun storeUserIdentityKeys(userId: String, keys: List<CryptoCrossSigningKey>) {
        database.transaction {
            val existingByPubKey = queries.keyInfoSelectByUser(userId).executeAsList().associateBy { it.public_key_base64 }
            queries.xsignInsertIgnore(userId)
            queries.keyInfoDeleteByUser(userId)
            keys.forEach { key ->
                val entity = crossSigningKeysMapper.map(key)
                val existing = existingByPubKey[key.unpaddedBase64PublicKey]
                queries.keyInfoInsert(
                        userId,
                        entity.publicKeyBase64,
                        entity.usages.toUsageString(),
                        entity.signatures,
                        existing?.has_trust_level ?: 0L,
                        existing?.trust_cross_signed_verified,
                        existing?.trust_locally_verified,
                )
            }
        }
    }

    fun getAllUserIds(): List<String> = queries.xsignSelectAllUserIds().executeAsList()

    fun getPinnedMasterKey(userId: String): String? = queries.identityPinSelect(userId).executeAsOneOrNull()

    fun pinMasterKey(userId: String, masterKey: String) = queries.identityPinUpsert(userId, masterKey)

    fun setUserKeysAsTrusted(userId: String, trusted: Boolean) {
        database.transaction {
            queries.keyInfoUpdateTrustByUser(trusted.toLong(), trusted.toLong(), userId)
            // Track that this user was verified at least once (used for the room warning shield).
            if (trusted) queries.xsignSetVerifiedOnce(userId)
        }
    }

    fun clearOtherUserTrust(myUserId: String) {
        queries.keyInfoClearTrustForOthers(myUserId)
    }

    fun markMasterKeyAsLocallyTrusted(myUserId: String, trusted: Boolean) {
        database.transaction {
            val master = queries.keyInfoSelectByUser(myUserId).executeAsList()
                    .firstOrNull { it.usages.toUsageList().contains(KeyUsage.MASTER.value) }
                    ?: return@transaction
            queries.keyInfoUpdateLocalTrustById(trusted.toLong(), master.id)
        }
    }

    fun updateUsersTrust(myUserId: String, check: (String) -> Boolean) {
        database.transaction {
            queries.xsignSelectAll().executeAsList().forEach { info ->
                val userId = info.user_id
                if (userId == myUserId) return@forEach
                val mapped = getCrossSigningInfo(userId) ?: return@forEach
                val newTrust = check(userId)
                if (mapped.isTrusted() != newTrust) {
                    queries.keyInfoUpdateTrustByUser(newTrust.toLong(), newTrust.toLong(), userId)
                }
            }
        }
    }

    private fun insertKey(userId: String, entity: KeyInfoEntity) {
        queries.keyInfoInsert(
                userId,
                entity.publicKeyBase64,
                entity.usages.toUsageString(),
                entity.signatures,
                if (entity.trustLevelEntity != null) 1L else 0L,
                entity.trustLevelEntity?.crossSignedVerified?.toLong(),
                entity.trustLevelEntity?.locallyVerified?.toLong(),
        )
    }

    private fun Key_info.toEntity(): KeyInfoEntity = KeyInfoEntity(
            publicKeyBase64 = public_key_base64,
            usages = ArrayList<String>().apply { addAll(usages.toUsageList()) },
            signatures = signatures,
            trustLevelEntity = if (has_trust_level == 1L) {
                TrustLevelEntity(
                        crossSignedVerified = trust_cross_signed_verified?.let { it != 0L },
                        locallyVerified = trust_locally_verified?.let { it != 0L },
                )
            } else {
                null
            },
    )

    private fun String?.toUsageList(): List<String> = if (isNullOrEmpty()) emptyList() else split(USAGE_SEPARATOR)

    private fun List<String>.toUsageString(): String = joinToString(USAGE_SEPARATOR)

    private fun Boolean.toLong(): Long = if (this) 1L else 0L

    companion object {
        private const val USAGE_SEPARATOR = "\n"
    }
}
