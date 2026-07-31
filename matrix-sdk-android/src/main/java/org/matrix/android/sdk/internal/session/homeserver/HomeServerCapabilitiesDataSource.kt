/*
 * Copyright (c) 2021 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.homeserver

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilities
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOptional
import org.matrix.android.sdk.internal.database.mapper.HomeServerCapabilitiesMapper
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sql.store.toHomeServerCapabilitiesEntity
import org.matrix.android.sdk.internal.di.SessionDatabase
import javax.inject.Inject

internal class HomeServerCapabilitiesDataSource @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
) {
    fun getHomeServerCapabilities(): HomeServerCapabilities? =
            stores.homeServerCapabilities.get()?.let { HomeServerCapabilitiesMapper.map(it) }

    fun getHomeServerCapabilitiesFlow(): Flow<Optional<HomeServerCapabilities>> {
        return database.homeServerCapabilitiesQueries.selectFirst()
                .asFlow()
                .mapToList(dispatcher)
                .map { rows -> rows.firstOrNull()?.toHomeServerCapabilitiesEntity()?.let { HomeServerCapabilitiesMapper.map(it) }.toOptional() }
    }
}
