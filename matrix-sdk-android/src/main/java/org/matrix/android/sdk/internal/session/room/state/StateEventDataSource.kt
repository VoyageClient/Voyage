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

package org.matrix.android.sdk.internal.session.room.state

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.query.QueryStateEventValue
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOptional
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.query.matches
import javax.inject.Inject
import org.matrix.android.sdk.internal.database.sql.Current_state_event as CurrentStateEventRow

internal class StateEventDataSource @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
) {

    fun getStateEvent(roomId: String, eventType: String, stateKey: QueryStateEventValue): Event? {
        return query(roomId, setOf(eventType), stateKey).firstOrNull()
    }

    fun getStateEventFlow(roomId: String, eventType: String, stateKey: QueryStateEventValue): Flow<Optional<Event>> {
        return queryFlow(roomId, setOf(eventType), stateKey).map { it.firstOrNull().toOptional() }
    }

    fun getStateEvents(roomId: String, eventTypes: Set<String>, stateKey: QueryStateEventValue): List<Event> {
        return query(roomId, eventTypes, stateKey)
    }

    fun getStateEventsFlow(roomId: String, eventTypes: Set<String>, stateKey: QueryStateEventValue): Flow<List<Event>> {
        return queryFlow(roomId, eventTypes, stateKey)
    }

    private fun query(roomId: String, eventTypes: Set<String>, stateKey: QueryStateEventValue): List<Event> {
        return database.currentStateEventQueries.selectByRoom(roomId).executeAsList()
                .filter { it.matches(eventTypes, stateKey) }
                .mapNotNull { it.rootEvent() }
    }

    private fun queryFlow(roomId: String, eventTypes: Set<String>, stateKey: QueryStateEventValue): Flow<List<Event>> {
        return database.currentStateEventQueries.selectByRoom(roomId)
                .asFlow()
                .mapToList(dispatcher)
                .map { rows -> rows.filter { it.matches(eventTypes, stateKey) }.mapNotNull { it.rootEvent() } }
    }

    private fun CurrentStateEventRow.rootEvent(): Event? =
            root_event_id?.let { stores.event.getByEventIdInRoom(room_id, it) }?.asDomain()

    private fun CurrentStateEventRow.matches(eventTypes: Set<String>, stateKey: QueryStateEventValue): Boolean {
        if (eventTypes.isNotEmpty() && type !in eventTypes) return false
        return (stateKey as QueryStringValue).matches(state_key)
    }
}
