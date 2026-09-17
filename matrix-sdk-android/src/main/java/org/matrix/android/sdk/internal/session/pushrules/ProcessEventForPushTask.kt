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

import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.isEdition
import org.matrix.android.sdk.api.session.events.model.isInvitation
import org.matrix.android.sdk.api.session.pushrules.Action
import org.matrix.android.sdk.api.session.pushrules.PushEvents
import org.matrix.android.sdk.api.session.pushrules.RuleIds
import org.matrix.android.sdk.api.session.pushrules.getActions
import org.matrix.android.sdk.api.session.pushrules.rest.PushRule
import org.matrix.android.sdk.api.session.sync.model.RoomsSyncResponse
import org.matrix.android.sdk.internal.crypto.EventDecryptor
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.user.UserDataSource
import org.matrix.android.sdk.internal.task.Task
import timber.log.Timber
import javax.inject.Inject

internal interface ProcessEventForPushTask : Task<ProcessEventForPushTask.Params, Unit> {
    data class Params(
            val syncResponse: RoomsSyncResponse,
            val rules: List<PushRule>
    )
}

internal class DefaultProcessEventForPushTask @Inject constructor(
        private val defaultPushRuleService: DefaultPushRuleService,
        private val pushRuleFinder: PushRuleFinder,
        @UserId private val userId: String,
        private val eventDecryptor: EventDecryptor,
        private val userDataSource: UserDataSource,
) : ProcessEventForPushTask {

    override suspend fun execute(params: ProcessEventForPushTask.Params) {
        val newJoinEvents = params.syncResponse.join
                .mapNotNull { (key, value) ->
                    value.timeline?.events?.mapNotNull {
                        it.takeIf { !it.isInvitation() }?.copyAll(roomId = key)
                    }
                }
                .flatten()

        val inviteEvents = params.syncResponse.invite
                .mapNotNull { (key, value) ->
                    value.inviteState?.events?.map { it.copyAll(roomId = key) }
                }
                .flatten()

        // An ignored user's event should never reach us, but one that does must not notify.
        val ignoredUserIds = userDataSource.getIgnoredUserIds().toSet()

        val allEvents = (newJoinEvents + inviteEvents).onEach { event ->
            if (event.isEncrypted()) {
                eventDecryptor.decryptEventAndSaveResult(event, timeline = "")
            }
        }.filter { event ->
            when (event.type) {
                in EventType.POLL_START.values,
                in EventType.POLL_END.values,
                in EventType.STATE_ROOM_BEACON_INFO.values,
                in EventType.ELEMENT_CALL_NOTIFY.values,
                EventType.MESSAGE,
                EventType.REDACTION,
                EventType.ENCRYPTED,
                EventType.STATE_ROOM_MEMBER -> true
                else -> false
            }
        }.filter {
            !it.isEdition() && it.senderId != userId && it.senderId !in ignoredUserIds
        }
        Timber.v(
                "[PushRules] Found ${allEvents.size} out of ${(newJoinEvents + inviteEvents).size}" +
                        " to check for push rules with ${params.rules.size} rules"
        )
        logRuleSetIfChanged(params.rules)
        val matchedEvents = allEvents.mapNotNull { event ->
            pushRuleFinder.fulfilledBingRule(event, params.rules)?.let {
                Timber.v("[PushRules] Rule $it match for event ${event.eventId}")
                event to it
            }
        }
        Timber.d("[PushRules] matched ${matchedEvents.size} out of ${allEvents.size}")

        val allRedactedEvents = params.syncResponse.join
                .asSequence()
                .mapNotNull { it.value.timeline?.events }
                .flatten()
                .filter { it.type == EventType.REDACTION }
                .mapNotNull { it.redacts }
                .toList()

        Timber.v("[PushRules] Found ${allRedactedEvents.size} redacted events")

        defaultPushRuleService.dispatchEvents(
                PushEvents(
                        matchedEvents = matchedEvents,
                        roomsJoined = params.syncResponse.join.keys,
                        roomsLeft = params.syncResponse.leave.keys,
                        redactedEventIds = allRedactedEvents
                )
        )
    }

    private var loggedRuleSetDigest: Int? = null

    /** Log the evaluated ruleset on changes so delayed notification reports can be traced. */
    private fun logRuleSetIfChanged(rules: List<PushRule>) {
        val notifying = rules.filter { it.enabled && it.getActions().any { action -> action is Action.Notify } }
        val digest = notifying.map { it.ruleId to it.actions }.hashCode()
        if (digest == loggedRuleSetDigest) return
        loggedRuleSetDigest = digest
        DebugLog.i { "NOTIFDBG rule set for $userId: ${rules.size} rules, ${notifying.size} enabled and notifying" }
        notifying.forEach { rule ->
            DebugLog.i { "NOTIFDBG   notifying rule=${rule.ruleId} default=${rule.default} actions=${rule.actions}" }
        }
        // The rules the settings screen claims to control, whatever they are doing: "mentions and
        // keywords" notifying nothing at all looks identical to a rule set that is simply quiet.
        INTERESTING_RULE_IDS.forEach { ruleId ->
            val rule = rules.find { it.ruleId == ruleId }
            DebugLog.i { "NOTIFDBG   default rule=$ruleId ($userId) " +
                            (rule?.let { "enabled=${it.enabled} actions=${it.actions} conditions=${it.conditions}" } ?: "ABSENT") }
        }
    }

    private companion object {
        private val INTERESTING_RULE_IDS = listOf(
                RuleIds.RULE_ID_IS_USER_MENTION,
                RuleIds.RULE_ID_IS_ROOM_MENTION,
                RuleIds.RULE_ID_KEYWORDS,
                ".m.rule.contains_display_name",
                ".m.rule.contains_user_name",
                RuleIds.RULE_ID_ONE_TO_ONE_ROOM,
                RuleIds.RULE_ID_ONE_TO_ONE_ENCRYPTED_ROOM,
                RuleIds.RULE_ID_ALL_OTHER_MESSAGES_ROOMS,
                RuleIds.RULE_ID_ENCRYPTED,
        )
    }
}
