/*
 * Copyright 2024 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.media

import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.SqlLiveEntityObserver
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.session.SessionScope
import timber.log.Timber
import javax.inject.Inject

@SessionScope
internal class DefaultIsAuthenticatedMediaSupported @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
) : IsAuthenticatedMediaSupported, SqlLiveEntityObserver(dispatcher) {

    override val query = database.homeServerCapabilitiesQueries.selectFirst()

    private var canUseAuthenticatedMedia = stores.homeServerCapabilities.get()?.canUseAuthenticatedMedia ?: false

    override fun invoke(): Boolean = canUseAuthenticatedMedia

    override suspend fun onChange() = refresh()

    /**
     * Re-reads the capability. Called directly when it is written, because the observer above does not
     * reliably fire for the first write of the row — and until this catches up every media request goes to
     * the unauthenticated endpoint, which servers that require authenticated media answer with 404.
     */
    fun refresh() {
        canUseAuthenticatedMedia = stores.homeServerCapabilities.get()?.canUseAuthenticatedMedia ?: false
        Timber.d("canUseAuthenticatedMedia: $canUseAuthenticatedMedia")
    }
}
