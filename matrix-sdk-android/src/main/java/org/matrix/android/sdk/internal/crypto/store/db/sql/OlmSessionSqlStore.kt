/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.store.db.sql

/**
 * SQL layer for olm (1-to-1) sessions. The (de)serialization of the native OlmSession blob is done
 * by the caller (it requires the native library); this store only persists/queries the columns.
 */
internal class OlmSessionSqlStore(private val database: CryptoSqlDatabase) {

    private val queries get() = database.olmSessionQueries

    fun upsert(primaryKey: String, sessionId: String?, deviceKey: String?, olmSessionData: String?, lastReceivedMessageTs: Long) {
        queries.upsert(primaryKey, sessionId, deviceKey, olmSessionData, lastReceivedMessageTs)
    }

    fun get(primaryKey: String): Olm_session? = queries.selectByPrimaryKey(primaryKey).executeAsOneOrNull()

    fun getDeviceSessionIds(deviceKey: String): List<String> =
            queries.selectIdsByDeviceKey(deviceKey).executeAsList().mapNotNull { it.session_id }

    fun getLastUsedSessionId(deviceKey: String): String? =
            queries.selectLastUsedByDeviceKey(deviceKey).executeAsOneOrNull()?.session_id
}
