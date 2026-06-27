/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.store.db.sql

import org.matrix.android.sdk.internal.session.cache.ClearCacheTask

internal class SqlClearCacheTask(private val database: CryptoSqlDatabase) : ClearCacheTask {

    override suspend fun execute(params: Unit) {
        database.transaction {
            with(database.cryptoDeleteAllQueries) {
                deleteAllMetadata()
                deleteAllOlmSession()
                deleteAllInbound()
                deleteAllRoom()
                deleteAllUser()
                deleteAllDevice()
                deleteAllMyDevice()
                deleteAllCrossSigning()
                deleteAllKeyInfo()
                deleteAllOutgoingRequest()
                deleteAllKeyRequestReply()
                deleteAllWithheld()
                deleteAllShared()
                deleteAllKeysBackup()
                deleteAllAudit()
            }
        }
    }
}
