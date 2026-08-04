/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.auth.db

import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.auth.data.Credentials
import org.matrix.android.sdk.api.auth.data.HomeServerConnectionConfig
import org.matrix.android.sdk.api.auth.data.SessionParams
import org.matrix.android.sdk.api.auth.data.sessionId
import org.matrix.android.sdk.internal.auth.SessionParamsStore
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.AuthDatabase
import javax.inject.Inject

internal class SqlSessionParamsStore @Inject constructor(
        private val mapper: SessionParamsMapper,
        @AuthDatabase private val database: AuthSqlDatabase,
        @AuthDatabase private val dispatcher: CoroutineDispatcher,
) : SessionParamsStore {

    private val queries get() = database.sessionParamsQueries

    private fun Session_params_entity.toDomain(): SessionParams? =
            mapper.map(credentials_json, home_server_connection_config_json, is_token_valid != 0L, login_type)

    override fun get(sessionId: String): SessionParams? =
            queries.selectById(sessionId).executeAsOneOrNull()?.toDomain()

    override fun getLast(): SessionParams? =
            queries.selectLast().executeAsOneOrNull()?.toDomain()

    override fun getAll(): List<SessionParams> =
            queries.selectAll().executeAsList().mapNotNull { it.toDomain() }

    override suspend fun save(sessionParams: SessionParams) {
        val columns = mapper.toColumns(sessionParams) ?: return
        database.awaitDbTransaction(dispatcher) {
            queries.upsert(columns)
        }
    }

    override suspend fun setTokenInvalid(sessionId: String) {
        database.awaitDbTransaction(dispatcher) {
            if (queries.countById(sessionId).executeAsOne() == 0L) {
                error("Session param not found for id $sessionId")
            }
            queries.setTokenInvalid(sessionId)
        }
    }

    override suspend fun updateCredentials(newCredentials: Credentials) {
        val sessionId = newCredentials.sessionId()
        database.awaitDbTransaction(dispatcher) {
            val current = queries.selectById(sessionId).executeAsOneOrNull()?.toDomain()
                    ?: error("Session param not found for id $sessionId")
            val columns = mapper.toColumns(current.copy(credentials = newCredentials, isTokenValid = true))
                    ?: return@awaitDbTransaction
            queries.upsert(columns)
        }
    }

    override suspend fun updateHomeServerConnectionConfig(sessionId: String, transform: (HomeServerConnectionConfig) -> HomeServerConnectionConfig) {
        database.awaitDbTransaction(dispatcher) {
            val current = queries.selectById(sessionId).executeAsOneOrNull()?.toDomain()
                    ?: error("Session param not found for id $sessionId")
            val columns = mapper.toColumns(current.copy(homeServerConnectionConfig = transform(current.homeServerConnectionConfig)))
                    ?: return@awaitDbTransaction
            queries.upsert(columns)
        }
    }

    override suspend fun delete(sessionId: String) {
        database.awaitDbTransaction(dispatcher) {
            queries.deleteById(sessionId)
        }
    }

    override suspend fun deleteAll() {
        database.awaitDbTransaction(dispatcher) {
            queries.deleteAll()
        }
    }

    private fun SessionParamsQueries.upsert(columns: SessionParamsMapper.Columns) {
        upsert(
                columns.sessionId,
                columns.userId,
                columns.credentialsJson,
                columns.homeServerConnectionConfigJson,
                if (columns.isTokenValid) 1L else 0L,
                columns.loginType,
        )
    }
}
