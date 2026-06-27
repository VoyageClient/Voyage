/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.store.db.sql

import org.matrix.android.sdk.api.session.crypto.model.CryptoDeviceInfo
import org.matrix.android.sdk.api.session.crypto.model.DeviceInfo
import org.matrix.android.sdk.internal.crypto.store.db.mapper.MyDeviceLastSeenInfoEntityMapper
import org.matrix.android.sdk.internal.crypto.store.db.model.CryptoMapper
import org.matrix.android.sdk.internal.crypto.store.db.model.DeviceInfoEntity
import org.matrix.android.sdk.internal.crypto.store.db.model.createPrimaryKey
import org.matrix.android.sdk.internal.crypto.store.db.model.MyDeviceLastSeenInfoEntity
import org.matrix.android.sdk.internal.crypto.store.db.model.TrustLevelEntity

/**
 * SQL layer for users' devices, the current user's own devices, and per-user device tracking.
 * Reuses the existing [CryptoMapper] by converting SQL rows to/from unmanaged Realm entity objects,
 * keeping the JSON (de)serialization logic identical to the legacy store.
 */
internal class DeviceSqlStore(
        private val database: CryptoSqlDatabase,
        private val myDeviceMapper: MyDeviceLastSeenInfoEntityMapper,
) {

    private val queries get() = database.cryptoDevicesQueries

    // ==================== Users' devices ====================

    fun storeUserDevices(userId: String, devices: Map<String, CryptoDeviceInfo>?, nowMs: Long) {
        database.transaction {
            if (devices == null) {
                queries.deviceDeleteByUserId(userId)
                queries.userDelete(userId)
            } else {
                queries.userInsertIgnore(userId)
                val newDeviceIds = devices.keys
                queries.deviceSelectByUserId(userId).executeAsList().forEach { row ->
                    if (row.device_id !in newDeviceIds) {
                        queries.deviceDeleteByPrimaryKey(row.primary_key)
                    }
                }
                devices.values.forEach { cryptoDeviceInfo ->
                    val primaryKey = DeviceInfoEntity.createPrimaryKey(cryptoDeviceInfo.userId, cryptoDeviceInfo.deviceId)
                    val existing = queries.deviceSelectByPrimaryKey(primaryKey).executeAsOneOrNull()
                    val entity = CryptoMapper.mapToEntity(cryptoDeviceInfo).apply {
                        // A fresh insert is timestamped now; an update preserves the original timestamp.
                        firstTimeSeenLocalTs = if (existing == null) nowMs else existing.first_time_seen_local_ts
                        identityKey = existing?.identity_key
                    }
                    upsertEntity(entity)
                }
            }
        }
    }

    fun getUserDevice(userId: String, deviceId: String): CryptoDeviceInfo? =
            queries.deviceSelectByPrimaryKey(DeviceInfoEntity.createPrimaryKey(userId, deviceId))
                    .executeAsOneOrNull()
                    ?.let { CryptoMapper.mapToModel(it.toEntity()) }

    fun getUserDevices(userId: String): Map<String, CryptoDeviceInfo>? {
        if (!userKnown(userId)) return null
        return queries.deviceSelectByUserId(userId).executeAsList()
                .map { CryptoMapper.mapToModel(it.toEntity()) }
                .associateBy { it.deviceId }
    }

    fun getUserDeviceList(userId: String): List<CryptoDeviceInfo>? {
        if (!userKnown(userId)) return null
        return queries.deviceSelectByUserId(userId).executeAsList()
                .map { CryptoMapper.mapToModel(it.toEntity()) }
    }

    fun deviceWithIdentityKey(identityKey: String): CryptoDeviceInfo? =
            queries.deviceSelectByKeysMapContains(identityKey).executeAsList()
                    .map { CryptoMapper.mapToModel(it.toEntity()) }
                    .firstOrNull { it.identityKey() == identityKey }

    fun deviceWithIdentityKey(userId: String, identityKey: String): CryptoDeviceInfo? =
            queries.deviceSelectByUserIdAndKeysMapContains(userId, identityKey).executeAsList()
                    .map { CryptoMapper.mapToModel(it.toEntity()) }
                    .firstOrNull { it.identityKey() == identityKey }

    /** Update the device's flattened trust columns in place (used by setDeviceTrust). */
    fun setDeviceTrust(userId: String, deviceId: String, crossSignedVerified: Boolean, locallyVerified: Boolean?) {
        database.transaction {
            val row = queries.deviceSelectByPrimaryKey(DeviceInfoEntity.createPrimaryKey(userId, deviceId)).executeAsOneOrNull()
                    ?: return@transaction
            val entity = row.toEntity()
            val trust = entity.trustLevelEntity
            if (trust == null) {
                entity.trustLevelEntity = TrustLevelEntity(crossSignedVerified = crossSignedVerified, locallyVerified = locallyVerified)
            } else {
                locallyVerified?.let { trust.locallyVerified = it }
                trust.crossSignedVerified = crossSignedVerified
            }
            upsertEntity(entity)
        }
    }

    // ==================== Device tracking ====================

    fun getDeviceTrackingStatuses(): Map<String, Int> =
            queries.userSelectAll().executeAsList().associate { it.user_id to it.device_tracking_status.toInt() }

    fun saveDeviceTrackingStatuses(statuses: Map<String, Int>) {
        database.transaction {
            statuses.forEach { (userId, status) -> queries.userUpsertTracking(userId, status.toLong()) }
        }
    }

    fun getDeviceTrackingStatus(userId: String, defaultValue: Int): Int =
            queries.userSelectByUserId(userId).executeAsOneOrNull()?.device_tracking_status?.toInt() ?: defaultValue

    fun resetMyDevicesLocalTrust(myUserId: String, myDeviceId: String) {
        queries.deviceResetMyTrust(deviceId = myDeviceId, userId = myUserId)
    }

    // ==================== Current user's own devices ====================

    fun getMyDevicesInfo(): List<DeviceInfo> =
            queries.myDeviceSelectAll().executeAsList().map { myDeviceMapper.map(it.toEntity()) }

    fun saveMyDevicesInfo(info: List<DeviceInfo>) {
        val entities = info.map { myDeviceMapper.map(it) }
        database.transaction {
            queries.myDeviceDeleteAll()
            entities.forEach { entity ->
                val deviceId = entity.deviceId ?: return@forEach
                queries.myDeviceUpsert(deviceId, entity.displayName, entity.lastSeenTs, entity.lastSeenIp, entity.lastSeenUserAgent)
            }
        }
    }

    fun mapDeviceRow(row: Device_info): CryptoDeviceInfo = CryptoMapper.mapToModel(row.toEntity())

    fun mapMyDeviceRow(row: My_device_last_seen_info): DeviceInfo = myDeviceMapper.map(row.toEntity())

    private fun userKnown(userId: String): Boolean =
            queries.userSelectByUserId(userId).executeAsOneOrNull() != null

    private fun upsertEntity(e: DeviceInfoEntity) {
        queries.deviceUpsert(
                e.primaryKey,
                e.deviceId,
                e.identityKey,
                e.userId,
                e.isBlocked?.toLong(),
                e.algorithmListJson,
                e.keysMapJson,
                e.signatureMapJson,
                e.unsignedMapJson,
                if (e.trustLevelEntity != null) 1L else 0L,
                e.trustLevelEntity?.crossSignedVerified?.toLong(),
                e.trustLevelEntity?.locallyVerified?.toLong(),
                e.firstTimeSeenLocalTs,
        )
    }

    private fun Device_info.toEntity(): DeviceInfoEntity = DeviceInfoEntity(
            primaryKey = primary_key,
            deviceId = device_id,
            identityKey = identity_key,
            userId = user_id,
            isBlocked = is_blocked?.let { it != 0L },
            algorithmListJson = algorithm_list_json,
            keysMapJson = keys_map_json,
            signatureMapJson = signature_map_json,
            unsignedMapJson = unsigned_map_json,
            trustLevelEntity = if (has_trust_level == 1L) {
                TrustLevelEntity(
                        crossSignedVerified = trust_cross_signed_verified?.let { it != 0L },
                        locallyVerified = trust_locally_verified?.let { it != 0L },
                )
            } else {
                null
            },
            firstTimeSeenLocalTs = first_time_seen_local_ts,
    )

    private fun My_device_last_seen_info.toEntity(): MyDeviceLastSeenInfoEntity = MyDeviceLastSeenInfoEntity(
            deviceId = device_id,
            displayName = display_name,
            lastSeenTs = last_seen_ts,
            lastSeenIp = last_seen_ip,
            lastSeenUserAgent = last_seen_user_agent,
    )

    private fun Boolean.toLong(): Long = if (this) 1L else 0L
}
