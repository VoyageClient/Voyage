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
package org.matrix.android.sdk.internal.session.pushers

import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.session.pushrules.RuleIds
import org.matrix.android.sdk.api.session.pushrules.RuleScope
import org.matrix.android.sdk.api.session.pushrules.RuleSetKey
import org.matrix.android.sdk.api.session.pushrules.rest.PushRule
import org.matrix.android.sdk.internal.database.mapper.PushRulesMapper
import org.matrix.android.sdk.internal.database.model.PushRulesEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

internal interface SavePushRulesTask : Task<SavePushRulesTask.Params, Unit> {
    data class Params(val pushRules: GetPushRulesResponse)
}

internal class DefaultSavePushRulesTask @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
) : SavePushRulesTask {

    override suspend fun execute(params: SavePushRulesTask.Params) {
        val globalRules = params.pushRules.global
        database.awaitDbTransaction(dispatcher) {
            fun save(kind: RuleSetKey, rules: List<PushRule>?) {
                val entity = PushRulesEntity(RuleScope.GLOBAL).apply { this.kind = kind }
                rules?.filterNot { it.ruleId in RuleIds.LEGACY_MENTION_RULE_IDS }
                        ?.forEach { entity.pushRules.add(PushRulesMapper.map(it)) }
                stores.pushRules.upsert(entity)
            }
            save(RuleSetKey.CONTENT, globalRules.content)
            save(RuleSetKey.OVERRIDE, globalRules.override)
            save(RuleSetKey.ROOM, globalRules.room)
            save(RuleSetKey.SENDER, globalRules.sender)
            save(RuleSetKey.UNDERRIDE, globalRules.underride)
        }
    }
}
