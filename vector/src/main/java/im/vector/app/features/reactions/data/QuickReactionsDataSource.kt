/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions.data

import dagger.Lazy
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.features.session.coroutineScope
import im.vector.app.features.settings.VectorPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.flow.flow
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's quick reactions, stored account-wide in `im.voyage.setting.quick_reactions`. The local
 * preference mirrors the synced value and is what reads actually hit, so the long-press sheet never blocks on
 * a DB read and still works before the first sync.
 */
@Singleton
class QuickReactionsDataSource @Inject constructor(
        // Lazy: ActiveSessionHolder builds ConfigureAndStartSessionUseCase, which injects this.
        private val activeSessionHolder: Lazy<ActiveSessionHolder>,
        private val vectorPreferences: VectorPreferences,
) {

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val observeJob = AtomicReference<Job?>(null)

    fun onSessionStarted(session: Session) {
        val newJob = session.coroutineScope.launch {
            session.flow()
                    .liveUserAccountData(UserAccountDataTypes.TYPE_QUICK_REACTIONS)
                    .map { parse(it.getOrNull()?.content) }
                    .collect { remote ->
                        val synced = vectorPreferences.hasSyncedQuickReactions()
                        when {
                            remote != null -> vectorPreferences.setQuickReactions(remote)
                            // Only the very first reconciliation may treat a bare account as "not migrated yet";
                            // afterwards an absent event means another device reset it, and we must follow.
                            !synced -> {
                                if (vectorPreferences.hasQuickReactionsOverride()) {
                                    write(session, vectorPreferences.getQuickReactions())
                                }
                            }
                            else -> vectorPreferences.resetQuickReactions()
                        }
                        if (!synced) vectorPreferences.setQuickReactionsSynced()
                    }
        }
        observeJob.getAndSet(newJob)?.cancel()
    }

    fun getQuickReactions(): List<String> = vectorPreferences.getQuickReactions()

    /** Save to the account, throwing if it could not be persisted. The local mirror only follows on success. */
    suspend fun saveQuickReactions(reactions: List<String>) {
        // Saving the built-in set is a reset, not a customisation, so later default changes still apply.
        val toStore = reactions.takeIf { it != EmojiDataSource.quickEmojis }
        val session = activeSessionHolder.get().getSafeActiveSession() ?: throw IllegalStateException("No active session")
        write(session, toStore)
        mirror(toStore)
    }

    /** Best-effort variant for callers with no way to report a failure; the mirror is updated regardless. */
    fun setQuickReactions(reactions: List<String>) {
        val toStore = reactions.takeIf { it != EmojiDataSource.quickEmojis }
        mirror(toStore)
        coroutineScope.launch {
            val session = activeSessionHolder.get().getSafeActiveSession() ?: return@launch
            try {
                write(session, toStore)
            } catch (failure: Throwable) {
                Timber.w(failure, "Unable to save quick reactions account data")
            }
        }
    }

    private fun mirror(reactions: List<String>?) {
        if (reactions == null) {
            vectorPreferences.resetQuickReactions()
        } else {
            vectorPreferences.setQuickReactions(reactions)
        }
    }

    // A null [reactions] clears the customisation. Empty content rather than a delete: the SDK only
    // local-echoes the removal on this path.
    private suspend fun write(session: Session, reactions: List<String>?) {
        val content = if (reactions == null) emptyMap() else mapOf(CONTENT_KEY to reactions)
        session.accountDataService().updateUserAccountData(UserAccountDataTypes.TYPE_QUICK_REACTIONS, content)
    }

    private fun parse(content: Content?): List<String>? {
        val list = content?.get(CONTENT_KEY) as? List<*> ?: return null
        return list.mapNotNull { (it as? String)?.takeIf { entry -> entry.isNotBlank() } }
    }

    companion object {
        private const val CONTENT_KEY = "quick_reactions"
    }
}
