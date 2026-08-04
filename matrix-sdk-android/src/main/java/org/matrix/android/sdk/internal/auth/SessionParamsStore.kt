/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.auth

import org.matrix.android.sdk.api.auth.data.Credentials
import org.matrix.android.sdk.api.auth.data.HomeServerConnectionConfig
import org.matrix.android.sdk.api.auth.data.SessionParams

internal interface SessionParamsStore {

    fun get(sessionId: String): SessionParams?

    fun getLast(): SessionParams?

    fun getAll(): List<SessionParams>

    suspend fun save(sessionParams: SessionParams)

    suspend fun setTokenInvalid(sessionId: String)

    suspend fun updateCredentials(newCredentials: Credentials)

    /**
     * Read-modify-write inside the transaction, so callers holding a stale copy of the config cannot revert
     * fields they did not mean to touch.
     */
    suspend fun updateHomeServerConnectionConfig(sessionId: String, transform: (HomeServerConnectionConfig) -> HomeServerConnectionConfig)

    suspend fun delete(sessionId: String)

    suspend fun deleteAll()
}
