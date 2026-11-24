/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.profile

import android.content.Context
import dagger.Lazy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.SessionLifecycleObserver
import org.matrix.android.sdk.api.session.accountdata.EncryptedAccountDataService
import org.matrix.android.sdk.api.session.profile.IdentityPresentation
import org.matrix.android.sdk.api.session.profile.ProfileOverrides
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.room.summary.SqlRoomSummaryUpdater
import org.matrix.android.sdk.internal.task.TaskExecutor
import javax.inject.Inject

/** Seeds the static [ProfileOverrides] from persisted account data when the session opens. */
@SessionScope
internal class ProfileOverridesLoader @Inject constructor(
        private val stores: SessionStores,
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val context: Context,
        private val summaryUpdater: Lazy<SqlRoomSummaryUpdater>,
        private val taskExecutor: TaskExecutor,
        private val encryptedAccountDataService: Lazy<EncryptedAccountDataService>,
) : SessionLifecycleObserver {

    private var presentationJob: Job? = null

    override fun onSessionStarted(session: Session) {
        ProfileOverrides.claim(session.sessionId)
        IdentityPresentation.initialize(context, session.myUserId, session.sessionId)
        presentationJob?.cancel()
        presentationJob = taskExecutor.executorScope.launch {
            // Only rooms drawn from someone whose presentation changed: the write lock is held for the whole pass.
            var shown = IdentityPresentation.config(session.myUserId)
            IdentityPresentation.changes.drop(1).debounce(PRESENTATION_DEBOUNCE_MS).collect {
                if (IdentityPresentation.activeAccount != session.myUserId) return@collect
                val current = IdentityPresentation.config(session.myUserId)
                val roomIds = stores.roomSummary.activeRoomsWithOtherMembers()
                        .filter { (roomId, members) ->
                            members.any { IdentityPresentation.presentation(shown, it, roomId) != IdentityPresentation.presentation(current, it, roomId) }
                        }
                        .map { it.first }
                if (roomIds.isNotEmpty()) {
                    database.awaitDbTransaction(dispatcher) { roomIds.forEach { roomId -> summaryUpdater.get().refreshDisplay(stores, roomId, onlyIfChanged = true) } }
                }
                shown = current
                DebugLog.i { "IDPDBG summaries refreshed rooms=${roomIds.size}" }
            }
        }
        taskExecutor.executorScope.launch {
            val encryption = encryptedAccountDataService.get()
            stores.storedProfileOverrides(encryption).let { stored ->
                ProfileOverrides.set(session.sessionId, ProfileOverrides.parse(stored?.content, stored?.encrypted == true))
            }
            // Encrypted overrides with no ADK: the 4S key may be cached from an earlier unlock, so
            // try to acquire the ADK silently; success re-applies through setAccountDataKey.
            if (stores.hasLockedProfileOverrides(encryption)) {
                encryption.ensureAccountDataKey()
            }
        }
    }

    override fun onSessionStopped(session: Session) {
        presentationJob?.cancel()
        IdentityPresentation.release(session.sessionId)
        ProfileOverrides.release(session.sessionId)
    }

    private companion object {
        // Settings change one tap at a time; a run of taps is refreshed once.
        const val PRESENTATION_DEBOUNCE_MS = 250L
    }
}
