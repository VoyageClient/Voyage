/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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
package org.matrix.android.sdk.internal.database

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.SqlLiveEntityObserver
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.session.EventInsertLiveProcessor
import timber.log.Timber
import javax.inject.Inject

/**
 * SQLDelight live-processing pipeline: observes the `event_insert` queue and runs the
 * [EventInsertLiveProcessor]s. Replaces the Realm RealmLiveEntityObserver-based version.
 */
internal class EventInsertLiveObserver @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        private val processors: Set<@JvmSuppressWildcards EventInsertLiveProcessor>,
) : SqlLiveEntityObserver(dispatcher) {

    private val lock = Mutex()
    override val query = database.eventInsertQueries.selectAll()

    override suspend fun onChange() {
        lock.withLock {
            database.awaitDbTransaction(dispatcher) {
                val inserts = stores.eventInsert.getAll().filter { it.canBeProcessed }
                if (inserts.isEmpty()) return@awaitDbTransaction
                Timber.v("EventInsert processing ${inserts.size} events")
                val idsToDelete = ArrayList<String>()
                val idsEncrypted = ArrayList<String>()
                inserts.forEach { insert ->
                    val event = stores.event.getByEventId(insert.eventId)?.asDomain()
                    if (event == null) {
                        idsToDelete.add(insert.eventId)
                        return@forEach
                    }
                    if (event.getClearType() == EventType.ENCRYPTED) {
                        idsEncrypted.add(insert.eventId)
                    } else {
                        idsToDelete.add(insert.eventId)
                    }
                    processors
                            .filter { it.shouldProcess(insert.eventId, event.getClearType(), insert.insertType) }
                            .forEach { it.process(stores, event) }
                }
                idsToDelete.forEach { stores.eventInsert.deleteByEventId(it) }
                // Encrypted events stay non-processable; they are processed again after decryption.
                idsEncrypted.forEach { stores.eventInsert.setCanBeProcessed(it, false) }
            }
            processors.forEach { it.onPostProcess() }
        }
    }
}
