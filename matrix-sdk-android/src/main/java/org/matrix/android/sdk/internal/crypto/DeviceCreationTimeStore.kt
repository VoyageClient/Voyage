/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto

import org.matrix.android.sdk.internal.platform.KeyValueStoreFactory
import javax.inject.Inject

/**
 * When this device logged in. Devices that logged in before this was recorded have no value.
 */
internal class DeviceCreationTimeStore @Inject constructor(
        keyValueStoreFactory: KeyValueStoreFactory,
) {

    private val store by lazy { keyValueStoreFactory.create("matrix_device_creation_time") }

    fun recordIfAbsent(userId: String, deviceId: String, timestamp: Long) {
        val key = key(userId, deviceId)
        if (!store.contains(key)) store.putLong(key, timestamp)
    }

    fun get(userId: String, deviceId: String?): Long? {
        deviceId ?: return null
        return store.getLong(key(userId, deviceId), -1L).takeIf { it >= 0 }
    }

    private fun key(userId: String, deviceId: String) = "$userId|$deviceId"
}
