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

package org.matrix.android.sdk.internal.session.room.notification

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.matrix.android.sdk.api.session.pushrules.Action
import org.matrix.android.sdk.api.session.pushrules.RuleIds
import org.matrix.android.sdk.api.session.pushrules.RuleScope
import org.matrix.android.sdk.api.session.pushrules.getActions
import org.matrix.android.sdk.api.session.room.notification.RoomNotificationState
import org.matrix.android.sdk.api.session.room.notification.RoomPushRuleService
import org.matrix.android.sdk.internal.database.mapper.PushRulesMapper
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.di.SessionDatabase

internal class DefaultRoomPushRuleService @AssistedInject constructor(
        @Assisted private val roomId: String,
        private val setRoomNotificationStateTask: SetRoomNotificationStateTask,
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
) : RoomPushRuleService {

    @AssistedFactory
    interface Factory {
        fun create(roomId: String): DefaultRoomPushRuleService
    }

    override fun getRoomNotificationStateFlow(): Flow<RoomNotificationState> {
        return getPushRuleForRoom().map {
            it?.toRoomNotificationState() ?: defaultRoomNotificationState()
        }
    }

    override suspend fun setRoomNotificationState(roomNotificationState: RoomNotificationState) {
        setRoomNotificationStateTask.execute(SetRoomNotificationStateTask.Params(roomId, roomNotificationState))
    }

    private fun getPushRuleForRoom(): Flow<RoomPushRule?> {
        return database.pushRulesQueries.selectRuleByScopeAndRuleId(RuleScope.GLOBAL, roomId)
                .asFlow()
                .mapToList(dispatcher)
                .map { _ ->
                    stores.pushRules.findRule(RuleScope.GLOBAL, roomId)?.let { (kind, entity) -> entity.toRoomPushRule(kind) }
                }
    }

    private fun defaultRoomNotificationState(): RoomNotificationState {
        val summary = stores.roomSummary.get(roomId)
        val ruleIds = when {
            summary?.isDirect == true && summary.isEncrypted -> listOf(
                    RuleIds.RULE_ID_ONE_TO_ONE_ENCRYPTED_ROOM,
                    RuleIds.RULE_ID_ONE_TO_ONE_ROOM
            )
            summary?.isDirect == true -> listOf(RuleIds.RULE_ID_ONE_TO_ONE_ROOM)
            summary?.isEncrypted == true -> listOf(
                    RuleIds.RULE_ID_ENCRYPTED,
                    RuleIds.RULE_ID_ALL_OTHER_MESSAGES_ROOMS
            )
            else -> listOf(RuleIds.RULE_ID_ALL_OTHER_MESSAGES_ROOMS)
        }
        val actions = ruleIds.mapNotNull { ruleId ->
            val (_, entity) = stores.pushRules.findRule(RuleScope.GLOBAL, ruleId) ?: return@mapNotNull null
            PushRulesMapper.map(entity).getActions().takeIf { entity.enabled && Action.Notify in it }
        }
        return if (actions.any { ruleActions -> ruleActions.any { it is Action.Sound } }) {
            RoomNotificationState.ALL_MESSAGES_NOISY
        } else if (actions.isNotEmpty()) {
            RoomNotificationState.ALL_MESSAGES
        } else {
            RoomNotificationState.MENTIONS_ONLY
        }
    }
}
