/*
 * Copyright 2023, 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.notification

import im.vector.app.features.session.coroutineScope
import im.vector.app.features.settings.notifications.usecase.UpdatePushRulesIfNeededUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.api.session.pushrules.Kind
import org.matrix.android.sdk.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Listen changes in Account Data to update the push rules if needed.
 */
@Singleton
class PushRulesUpdater @Inject constructor(
        private val updatePushRulesIfNeededUseCase: UpdatePushRulesIfNeededUseCase,
) {

    private var job: Job? = null

    fun onSessionStarted(session: Session) {
        repairConditionsMissingTheirValue(session)
        updatePushRulesOnChange(session)
    }

    /** Refetch legacy conditions with missing values; unchanged account rules may never repair themselves via sync. */
    private fun repairConditionsMissingTheirValue(session: Session) {
        session.coroutineScope.launch(Dispatchers.Default) {
            val suspect = session.pushRuleService().getPushRules().getAllRules().any { rule ->
                rule.conditions.orEmpty().any { it.kind in VALUE_CONDITION_KINDS && it.value == null }
            }
            if (suspect) {
                DebugLog.w { "NOTIFDBG ${session.myUserId}: stored push conditions lost their value, re-fetching" }
                session.pushRuleService().fetchPushRules()
            }
        }
    }

    private fun updatePushRulesOnChange(session: Session) {
        job?.cancel()
        // Default, not the scope's main dispatcher: the use case reads every push rule from the DB.
        job = session.coroutineScope.launch(Dispatchers.Default) {
            session.flow()
                    .liveUserAccountData(UserAccountDataTypes.TYPE_PUSH_RULES)
                    .onEach { updatePushRulesIfNeededUseCase.execute(session) }
                    .collect()
        }
    }

    private companion object {
        private val VALUE_CONDITION_KINDS = setOf(Kind.EventPropertyIs.value, Kind.EventPropertyContains.value)
    }
}
