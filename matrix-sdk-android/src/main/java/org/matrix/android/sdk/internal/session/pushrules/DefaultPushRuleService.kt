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
package org.matrix.android.sdk.internal.session.pushrules

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.pushrules.Action
import org.matrix.android.sdk.api.session.pushrules.ConditionResolver
import org.matrix.android.sdk.api.session.pushrules.PushEvents
import org.matrix.android.sdk.api.session.pushrules.PushRuleService
import org.matrix.android.sdk.api.session.pushrules.RuleKind
import org.matrix.android.sdk.api.session.pushrules.RuleScope
import org.matrix.android.sdk.api.session.pushrules.RuleSetKey
import org.matrix.android.sdk.api.session.pushrules.SenderNotificationPermissionCondition
import org.matrix.android.sdk.api.session.pushrules.getActions
import org.matrix.android.sdk.api.session.pushrules.rest.PushRule
import org.matrix.android.sdk.api.session.pushrules.rest.RuleSet
import org.matrix.android.sdk.internal.database.mapper.PushRulesMapper
import org.matrix.android.sdk.internal.database.model.PushRuleEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.pushers.AddPushRuleTask
import org.matrix.android.sdk.internal.session.pushers.GetPushRulesTask
import org.matrix.android.sdk.internal.session.pushers.RemovePushRuleTask
import org.matrix.android.sdk.internal.session.pushers.UpdatePushRuleActionsTask
import org.matrix.android.sdk.internal.session.pushers.UpdatePushRuleEnableStatusTask
import org.matrix.android.sdk.internal.task.TaskExecutor
import org.matrix.android.sdk.internal.task.configureWith
import timber.log.Timber
import javax.inject.Inject

@SessionScope
internal class DefaultPushRuleService @Inject constructor(
        private val getPushRulesTask: GetPushRulesTask,
        private val updatePushRuleEnableStatusTask: UpdatePushRuleEnableStatusTask,
        private val addPushRuleTask: AddPushRuleTask,
        private val updatePushRuleActionsTask: UpdatePushRuleActionsTask,
        private val removePushRuleTask: RemovePushRuleTask,
        private val pushRuleFinder: PushRuleFinder,
        private val taskExecutor: TaskExecutor,
        private val conditionResolver: ConditionResolver,
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        private val stores: SessionStores,
) : PushRuleService {

    private var listeners = mutableSetOf<PushRuleService.PushRuleListener>()

    override fun fetchPushRules(scope: String) {
        getPushRulesTask
                .configureWith(GetPushRulesTask.Params(scope))
                .executeBy(taskExecutor)
    }

    override fun getPushRules(scope: String): RuleSet {
        var contentRules: List<PushRule> = emptyList()
        var overrideRules: List<PushRule> = emptyList()
        var roomRules: List<PushRule> = emptyList()
        var senderRules: List<PushRule> = emptyList()
        var underrideRules: List<PushRule> = emptyList()

        stores.pushRules.get(scope, RuleSetKey.CONTENT)?.let { contentRules = it.pushRules.map { r -> PushRulesMapper.mapContentRule(r) } }
        stores.pushRules.get(scope, RuleSetKey.OVERRIDE)?.let { overrideRules = it.pushRules.map { r -> PushRulesMapper.map(r) } }
        stores.pushRules.get(scope, RuleSetKey.ROOM)?.let { roomRules = it.pushRules.map { r -> PushRulesMapper.mapRoomRule(r) } }
        stores.pushRules.get(scope, RuleSetKey.SENDER)?.let { senderRules = it.pushRules.map { r -> PushRulesMapper.mapSenderRule(r) } }
        stores.pushRules.get(scope, RuleSetKey.UNDERRIDE)?.let { underrideRules = it.pushRules.map { r -> PushRulesMapper.map(r) } }

        return RuleSet(
                content = contentRules.withElementCallPushRules(),
                override = overrideRules,
                room = roomRules,
                sender = senderRules,
                underride = underrideRules
        )
    }

    override suspend fun updatePushRuleEnableStatus(kind: RuleKind, pushRule: PushRule, enabled: Boolean) {
        // The rules will be updated, and will come back from the next sync response
        updatePushRuleEnableStatusTask.execute(UpdatePushRuleEnableStatusTask.Params(kind, pushRule, enabled))
    }

    override suspend fun addPushRule(kind: RuleKind, pushRule: PushRule) {
        addPushRuleTask.execute(AddPushRuleTask.Params(kind, pushRule))
    }

    override suspend fun updatePushRuleActions(kind: RuleKind, ruleId: String, enable: Boolean, actions: List<Action>?) {
        updatePushRuleActionsTask.execute(UpdatePushRuleActionsTask.Params(kind, ruleId, enable, actions))
    }

    override suspend fun removePushRule(kind: RuleKind, ruleId: String) {
        removePushRuleTask.execute(RemovePushRuleTask.Params(kind, ruleId))
    }

    override fun removePushRuleListener(listener: PushRuleService.PushRuleListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    override fun addPushRuleListener(listener: PushRuleService.PushRuleListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    override fun getActions(event: Event): List<Action> {
        val rules = getPushRules(RuleScope.GLOBAL).getAllRules()

        return pushRuleFinder.fulfilledBingRule(event, rules)?.getActions().orEmpty()
    }

    override fun resolveSenderNotificationPermissionCondition(event: Event, condition: SenderNotificationPermissionCondition): Boolean {
        return conditionResolver.resolveSenderNotificationPermissionCondition(event, condition)
    }

    override fun getKeywordsFlow(): Flow<Set<String>> {
        // Keywords are all content rules that don't start with '.'
        return database.pushRulesQueries.selectRulesByScopeAndKind(RuleScope.GLOBAL, RuleSetKey.CONTENT.name).asFlow().mapToList(dispatcher)
                .map {
                    stores.pushRules.get(RuleScope.GLOBAL, RuleSetKey.CONTENT)
                            ?.pushRules
                            ?.map(PushRuleEntity::ruleId)
                            ?.filter { !it.startsWith(".") }
                            .orEmpty()
                            .toSet()
                }
    }

    fun dispatchEvents(pushEvents: PushEvents) {
        synchronized(listeners) {
            listeners.forEach {
                try {
                    it.onEvents(pushEvents)
                } catch (e: Throwable) {
                    Timber.e(e, "Error while dispatching push events")
                }
            }
        }
    }
}
