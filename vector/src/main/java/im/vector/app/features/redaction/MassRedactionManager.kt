/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.redaction

import android.content.SharedPreferences
import androidx.core.content.edit
import im.vector.app.ActiveSessionDataSource
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.di.DefaultPreferences
import im.vector.app.core.utils.BehaviorDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.getRoom
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives the single, global "redact every event from a user" job. Lives at app scope so it keeps running
 * when the room is closed or the app is backgrounded (process alive). It redacts what's already in the local
 * DB first, then pages the server for the rest. Progress is streamed to the room banner; a compact resume
 * record is persisted so a process kill leaves the job paused where it stopped.
 */
@Singleton
class MassRedactionManager @Inject constructor(
        private val activeSessionHolder: ActiveSessionHolder,
        activeSessionDataSource: ActiveSessionDataSource,
        @DefaultPreferences private val preferences: SharedPreferences,
) {

    sealed interface StartResult {
        object Started : StartResult
        object AlreadyRunning : StartResult
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val progress = BehaviorDataSource<MassRedactionState?>()
    private var job: Job? = null

    // The account (userId) whose job is currently shown/running. Each account has its own persisted record,
    // so switching accounts parks one batch and surfaces the other's — only the active account's job runs.
    @Volatile private var shownOwner: String? = null
    @Volatile private var paused = false

    init {
        // The job belongs to the account that started it. Re-evaluate whenever the active session changes
        // (login/logout/switch) so it never shows or runs against a different account.
        activeSessionDataSource.stream().onEach { onActiveSessionChanged() }.launchIn(scope)
    }

    private fun currentUserId(): String? = activeSessionHolder.getSafeActiveSession()?.myUserId

    @Synchronized
    private fun onActiveSessionChanged() {
        val current = currentUserId()
        // Only react to an actual account change; a re-post of the same session must not disturb a running job.
        if (current == shownOwner) return
        // Park the previous account's loop (its record stays persisted) and surface the new account's own
        // record (paused) if it has one.
        job?.cancel()
        job = null
        paused = true
        shownOwner = current
        progress.post(current?.let { readRecord(it)?.copy(paused = true) })
    }

    fun stream() = progress.stream()
    fun currentValue() = progress.currentValue

    @Synchronized
    fun start(roomId: String, userId: String, displayName: String, delayMs: Long): StartResult {
        if (progress.currentValue != null) return StartResult.AlreadyRunning
        val owner = currentUserId() ?: return StartResult.AlreadyRunning
        val initial = MassRedactionState(roomId, userId, displayName, completed = 0, total = 0, paused = false)
        shownOwner = owner
        paused = false
        progress.post(initial)
        saveRecord(owner, initial, delayMs = delayMs, token = null, remoteDone = false)
        launchLoop(owner, roomId, userId, displayName, delayMs, startCompleted = 0, startToken = null, remoteDone = false)
        return StartResult.Started
    }

    /** Resume a job that was paused (including one restored paused after a process kill). */
    @Synchronized
    fun resume() {
        val state = progress.currentValue ?: return
        if (!state.paused) return
        val owner = shownOwner ?: return
        val record = readRawRecord(owner) ?: return
        paused = false
        progress.post(state.copy(paused = false))
        saveState(owner, state.copy(paused = false))
        launchLoop(owner, state.roomId, state.targetUserId, state.targetDisplayName, record.delayMs,
                startCompleted = state.completed, startToken = record.token, remoteDone = record.remoteDone)
    }

    @Synchronized
    fun pause() {
        val state = progress.currentValue ?: return
        val owner = shownOwner ?: return
        paused = true
        job?.cancel()
        job = null
        progress.post(state.copy(paused = true))
        saveState(owner, state.copy(paused = true))
    }

    @Synchronized
    fun togglePause() {
        val state = progress.currentValue ?: return
        if (state.paused) resume() else pause()
    }

    @Synchronized
    fun cancel() {
        val owner = shownOwner
        job?.cancel()
        job = null
        paused = false
        progress.post(null)
        if (owner != null) clearRecord(owner)
    }

    private fun launchLoop(
            owner: String, roomId: String, userId: String, displayName: String, delayMs: Long,
            startCompleted: Int, startToken: String?, remoteDone: Boolean,
    ) {
        job?.cancel()
        job = scope.launch {
            val session = activeSessionHolder.getSafeActiveSession() ?: return@launch
            val room = session.getRoom(roomId) ?: return@launch
            val relations = room.relationService()
            val seen = HashSet<String>()
            var completed = startCompleted
            var total = startCompleted
            // Extra floor between redactions on top of the network round-trip, in case one returns instantly.
            val cooldown = delayMs.coerceAtLeast(MIN_COOLDOWN_MS)

            suspend fun redact(id: String) {
                if (!seen.add(id)) return
                total++
                post(owner, roomId, userId, displayName, completed, total)
                if (isActive) {
                    // Redact directly against the server — no local echo. Awaiting each keeps the timeline
                    // clean, self-paces, and never leaves hundreds of echoes stuck in "sending".
                    try {
                        relations.redactEventNoEcho(id, null)
                    } catch (t: Throwable) {
                        Timber.w(t, "massredact: failed to redact $id")
                    }
                    completed++
                    if (completed % PERSIST_EVERY == 0) saveProgress(owner, completed, total)
                    post(owner, roomId, userId, displayName, completed, total)
                    delay(cooldown)
                }
            }

            // Clear any redactions left stuck in "sending" by a previous echo-based run.
            try {
                relations.clearSendingRedactions()
            } catch (t: Throwable) {
                Timber.w(t, "massredact: failed to clear stuck sending redactions")
            }

            // Phase 1: everything already in the local DB (excludes already-redacted).
            relations.getLocalEventIdsFromUser(userId).forEach { redact(it) }

            // Phase 2: page the server for older history, stopping at the user's first event.
            val floorTs = try {
                relations.getMassRedactionFloorTs(userId)
            } catch (t: Throwable) {
                Timber.w(t, "massredact: floor resolution failed, will page fully")
                null
            }
            var token = startToken
            var done = remoteDone
            var pages = 0
            while (!done && isActive && pages++ < MAX_REMOTE_PAGES) {
                val page = try {
                    relations.fetchMoreEventIdsFromUser(userId, token, floorTs)
                } catch (t: Throwable) {
                    Timber.w(t, "massredact: server paging failed, stopping remote phase")
                    break
                }
                page.eventIds.forEach { redact(it) }
                token = page.nextToken
                done = token == null
                saveRemote(owner, completed, total, token, done)
            }

            // The loop only exits by finishing (reached the start/floor), erroring, or hitting the cap —
            // in every non-paused/cancelled case the job is complete, so clear it. Pause/cancel cancel the
            // job (isActive == false) and keep the record for resume.
            if (isActive) {
                finish(owner, roomId)
            }
        }
    }

    @Synchronized
    private fun finish(owner: String, roomId: String) {
        clearRecord(owner)
        if (owner == shownOwner && progress.currentValue?.roomId == roomId) progress.post(null)
    }

    private fun post(owner: String, roomId: String, userId: String, displayName: String, completed: Int, total: Int) {
        // Don't post from a parked loop after an account switch.
        if (paused || owner != shownOwner) return
        progress.post(MassRedactionState(roomId, userId, displayName, completed, total, paused = false))
    }

    // region persistence
    private data class RawRecord(val delayMs: Long, val token: String?, val remoteDone: Boolean)

    // Keys are namespaced by owner (account userId) so each account keeps its own independent batch.
    private fun key(owner: String, suffix: String) = "massredact_${owner}_$suffix"

    // commit (not apply) for the lifecycle writes: a paused job must survive an immediate process kill,
    // which drops apply()'s not-yet-flushed background write.
    private fun saveRecord(owner: String, state: MassRedactionState, delayMs: Long, token: String?, remoteDone: Boolean) {
        preferences.edit(commit = true) {
            putBoolean(key(owner, ACTIVE), true)
            putString(key(owner, ROOM), state.roomId)
            putString(key(owner, TARGET), state.targetUserId)
            putString(key(owner, NAME), state.targetDisplayName)
            putInt(key(owner, COMPLETED), state.completed)
            putInt(key(owner, TOTAL), state.total)
            putBoolean(key(owner, PAUSED), state.paused)
            putLong(key(owner, DELAY), delayMs)
            putString(key(owner, TOKEN), token)
            putBoolean(key(owner, REMOTE_DONE), remoteDone)
        }
    }

    private fun saveState(owner: String, state: MassRedactionState) {
        preferences.edit(commit = true) {
            putInt(key(owner, COMPLETED), state.completed)
            putInt(key(owner, TOTAL), state.total)
            putBoolean(key(owner, PAUSED), state.paused)
        }
    }

    private fun saveProgress(owner: String, completed: Int, total: Int) {
        preferences.edit { putInt(key(owner, COMPLETED), completed); putInt(key(owner, TOTAL), total) }
    }

    private fun saveRemote(owner: String, completed: Int, total: Int, token: String?, remoteDone: Boolean) {
        preferences.edit(commit = true) {
            putInt(key(owner, COMPLETED), completed)
            putInt(key(owner, TOTAL), total)
            putString(key(owner, TOKEN), token)
            putBoolean(key(owner, REMOTE_DONE), remoteDone)
        }
    }

    private fun readRecord(owner: String): MassRedactionState? {
        if (!preferences.getBoolean(key(owner, ACTIVE), false)) return null
        val roomId = preferences.getString(key(owner, ROOM), null) ?: return null
        val userId = preferences.getString(key(owner, TARGET), null) ?: return null
        val name = preferences.getString(key(owner, NAME), null) ?: userId
        return MassRedactionState(
                roomId = roomId,
                targetUserId = userId,
                targetDisplayName = name,
                completed = preferences.getInt(key(owner, COMPLETED), 0),
                total = preferences.getInt(key(owner, TOTAL), 0),
                paused = preferences.getBoolean(key(owner, PAUSED), true),
        )
    }

    private fun readRawRecord(owner: String): RawRecord? {
        if (!preferences.getBoolean(key(owner, ACTIVE), false)) return null
        return RawRecord(
                delayMs = preferences.getLong(key(owner, DELAY), 0L),
                token = preferences.getString(key(owner, TOKEN), null),
                remoteDone = preferences.getBoolean(key(owner, REMOTE_DONE), false),
        )
    }

    private fun clearRecord(owner: String) {
        preferences.edit(commit = true) {
            remove(key(owner, ACTIVE)); remove(key(owner, ROOM)); remove(key(owner, TARGET)); remove(key(owner, NAME))
            remove(key(owner, COMPLETED)); remove(key(owner, TOTAL)); remove(key(owner, PAUSED))
            remove(key(owner, DELAY)); remove(key(owner, TOKEN)); remove(key(owner, REMOTE_DONE))
        }
    }
    // endregion

    companion object {
        private const val PERSIST_EVERY = 20
        private const val MAX_REMOTE_PAGES = 500
        // Floor between redactions so a burst can't saturate the session dispatcher and stall sync app-wide.
        private const val MIN_COOLDOWN_MS = 30L
        private const val ACTIVE = "active"
        private const val ROOM = "room"
        private const val TARGET = "target"
        private const val NAME = "name"
        private const val COMPLETED = "completed"
        private const val TOTAL = "total"
        private const val PAUSED = "paused"
        private const val DELAY = "delay"
        private const val TOKEN = "token"
        private const val REMOTE_DONE = "remote_done"
    }
}
