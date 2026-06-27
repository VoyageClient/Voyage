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

import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilities
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOptional
import org.matrix.android.sdk.internal.database.mapper.HomeServerCapabilitiesMapper
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sql.store.toHomeServerCapabilitiesEntity
import org.matrix.android.sdk.internal.database.sqldelight.asLiveList
import org.matrix.android.sdk.internal.di.SessionDatabase
import javax.inject.Inject

internal class HomeServerCapabilitiesDataSource @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
) {
    fun getHomeServerCapabilities(): HomeServerCapabilities? =
            stores.homeServerCapabilities.get()?.let { HomeServerCapabilitiesMapper.map(it) }

    fun getHomeServerCapabilitiesLive(): LiveData<Optional<HomeServerCapabilities>> {
        return database.homeServerCapabilitiesQueries.selectFirst()
                .asLiveList(dispatcher)
                .map { rows -> rows.firstOrNull()?.toHomeServerCapabilitiesEntity()?.let { HomeServerCapabilitiesMapper.map(it) }.toOptional() }
    }
}
