/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.profile

import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.SessionLifecycleObserver
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.api.session.profile.ProfileOverrides
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.task.TaskExecutor
import javax.inject.Inject

/** Seeds the static [ProfileOverrides] from persisted account data when the session opens. */
@SessionScope
internal class ProfileOverridesLoader @Inject constructor(
        private val stores: SessionStores,
        private val taskExecutor: TaskExecutor,
) : SessionLifecycleObserver {

    override fun onSessionStarted(session: Session) {
        ProfileOverrides.claim(session.sessionId)
        taskExecutor.executorScope.launch {
            val content = stores.accountData.getUserAccountData(UserAccountDataTypes.TYPE_PROFILE_OVERRIDES)
                    ?.contentStr
                    ?.let(ContentMapper::map)
            ProfileOverrides.set(session.sessionId, ProfileOverrides.parse(content))
        }
    }

    override fun onSessionStopped(session: Session) {
        ProfileOverrides.release(session.sessionId)
    }
}
