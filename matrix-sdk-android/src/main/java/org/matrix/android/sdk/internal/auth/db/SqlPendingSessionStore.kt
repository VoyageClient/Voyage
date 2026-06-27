/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.auth.db

import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.internal.auth.PendingSessionStore
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.AuthDatabase
import javax.inject.Inject

internal class SqlPendingSessionStore @Inject constructor(
        private val mapper: PendingSessionMapper,
        @AuthDatabase private val database: AuthSqlDatabase,
        @AuthDatabase private val dispatcher: CoroutineDispatcher,
) : PendingSessionStore {

    private val queries get() = database.pendingSessionQueries

    override suspend fun savePendingSessionData(pendingSessionData: PendingSessionData) {
        val columns = mapper.toColumns(pendingSessionData)
        // Pending session is a singleton: replace any existing row.
        database.awaitDbTransaction(dispatcher) {
            queries.deleteAll()
            queries.insert(
                    columns.homeServerConnectionConfigJson,
                    columns.clientSecret,
                    columns.sendAttempt.toLong(),
                    columns.resetPasswordDataJson,
                    columns.currentSession,
                    if (columns.isRegistrationStarted) 1L else 0L,
                    columns.currentThreePidDataJson,
            )
        }
    }

    override fun getPendingSessionData(): PendingSessionData? =
            queries.selectFirst().executeAsOneOrNull()?.let {
                mapper.map(
                        it.home_server_connection_config_json,
                        it.client_secret,
                        it.send_attempt.toInt(),
                        it.reset_password_data_json,
                        it.current_session,
                        it.is_registration_started != 0L,
                        it.current_three_pid_data_json,
                )
            }

    override suspend fun delete() {
        database.awaitDbTransaction(dispatcher) {
            queries.deleteAll()
        }
    }
}
