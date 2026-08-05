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
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.utils.BehaviorDataSource
import im.vector.app.features.popup.DefaultVectorAlert
import im.vector.app.features.popup.PopupAlertManager
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.model.relation.PagedEventIds
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
        private val popupAlertManager: PopupAlertManager,
        private val stringProvider: StringProvider,
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
        if (progress.currentValue != null || job?.isActive == true) return StartResult.AlreadyRunning
        val owner = currentUserId() ?: return StartResult.AlreadyRunning
        val initial = MassRedactionState(roomId, userId, displayName, completed = 0, total = 0, paused = false)
        shownOwner = owner
        paused = false
        // Show the banner right away, even at 0/0 — the probe over a large room can take a while and the
        // banner is the only way to cancel it.
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
            // Every redaction target we know of, from the local DB and from redaction events encountered
            // while paging. A candidate in this set is already redacted no matter what the server claims
            // when serving the event itself — re-redacting it is exactly the reported bug.
            val knownRedactionTargets = HashSet<String>()
            try {
                knownRedactionTargets.addAll(relations.getKnownRedactionTargets())
            } catch (t: Throwable) {
                Timber.w(t, "massredact: failed to preseed known redaction targets")
            }
            var completed = startCompleted
            var total = startCompleted
            var lastPersisted = startCompleted
            // Extra floor between redaction waves on top of the network round-trip, in case one returns instantly.
            val cooldown = delayMs.coerceAtLeast(MIN_COOLDOWN_MS)

            suspend fun sendOne(id: String) {
                // Redact directly against the server — no local echo. Awaiting each keeps the timeline
                // clean, self-paces, and never leaves hundreds of echoes stuck in "sending".
                try {
                    relations.redactEventNoEcho(id, null)
                } catch (e: CancellationException) {
                    // Swallowing this would fall through to post() and resurrect the banner cancel() just cleared.
                    throw e
                } catch (t: Throwable) {
                    Timber.w(t, "massredact: failed to redact $id")
                }
            }

            suspend fun redactBatch(ids: List<String>) {
                // A candidate with a known redaction targeting it is already redacted, no matter how the
                // server serves the event itself — never redact it twice.
                val batch = ids.filter { seen.add(it) && it !in knownRedactionTargets }
                if (batch.isEmpty()) return
                // Count the whole batch up front so the banner shows the real backlog, not a moving n/n+1.
                total += batch.size
                post(owner, roomId, userId, displayName, completed, total)
                // With no user-chosen delay, run a small parallel window — the HTTP round-trip dominates,
                // and a few in flight multiply throughput without hammering the server. An explicit delay
                // means the user wants rate-limit-safe pacing, so honor it sequentially.
                val wave = if (delayMs <= MIN_COOLDOWN_MS) REDACT_PARALLELISM else 1
                for (chunk in batch.chunked(wave)) {
                    if (!isActive) return
                    coroutineScope {
                        chunk.map { id -> async { sendOne(id) } }.awaitAll()
                    }
                    completed += chunk.size
                    if (completed - lastPersisted >= PERSIST_EVERY) {
                        saveProgress(owner, completed, total)
                        lastPersisted = completed
                    }
                    post(owner, roomId, userId, displayName, completed, total)
                    delay(cooldown)
                }
                // Persist at batch end too, so a crash mid-run restores accurate counts instead of the
                // last PERSIST_EVERY multiple (or 0/0 for a short run).
                saveProgress(owner, completed, total)
                lastPersisted = completed
            }

            // Clear any redactions left stuck in "sending" by a previous echo-based run.
            try {
                relations.clearSendingRedactions()
            } catch (t: Throwable) {
                Timber.w(t, "massredact: failed to clear stuck sending redactions")
            }

            var token = startToken
            var done = remoteDone
            var pages = 0
            // Resolve the floor and prefetch the first server page concurrently with the local sweep, so
            // phase 2 starts the moment the sweep finishes instead of stalling on two round trips.
            val floorDeferred = async {
                try {
                    relations.getMassRedactionFloor(userId)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Timber.w(t, "massredact: floor resolution failed, will page fully")
                    null
                }
            }

            // Prefetch the next page while the current one's redactions run, so the network round-trip
            // overlaps the redaction work instead of serializing with it. The result MUST be wrapped in
            // runCatching inside the async: a failed async child fails the whole job even when nobody
            // awaits it, which crashed the app when a prefetch timed out while redactions were running.
            fun prefetchPage(fromToken: String?): Deferred<Result<PagedEventIds>> = async {
                runCatching { relations.fetchMoreEventIdsFromUser(userId, fromToken, floorDeferred.await()) }
            }
            var prefetch: Deferred<Result<PagedEventIds>>? = if (!done) prefetchPage(token) else null

            // Phase 1: everything already in the local DB — instant start, no network needed to begin.
            // The sweep excludes events known-redacted locally (unsigned markers or a stored redaction
            // event); a stale row it can't know about just costs one no-op redaction, which also marks
            // the row so it never repeats.
            redactBatch(relations.getLocalEventIdsFromUser(userId))

            // Phase 2: walk the server's history for the user's events — forwards from their join when
            // the floor anchor resolved, else backwards from the live edge. Server history is
            // authoritative — a bulk redaction makes sync gappy, so the local DB misses most of the
            // room; [seen] keeps the two phases from redacting the same event twice.
            while (!done && isActive && pages++ < MAX_REMOTE_PAGES) {
                val page = prefetch!!.await().getOrElse { t ->
                    if (t is CancellationException) throw t
                    Timber.w(t, "massredact: server paging failed, stopping remote phase")
                    null
                } ?: break
                token = page.nextToken
                done = token == null
                prefetch = if (!done) prefetchPage(token) else null
                // Collect redaction targets seen while paging (a backwards walk meets redactions before
                // their targets), covering redactions the local DB never received.
                knownRedactionTargets.addAll(page.redactionTargets)
                knownRedactionTargets.addAll(page.alreadyRedactedIds)
                // Server truth flows back into the local DB, so future local sweeps stay accurate.
                try {
                    relations.markRedactedLocally(page.alreadyRedactedIds)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Timber.w(t, "massredact: reconciling local rows failed")
                }
                redactBatch(page.eventIds)
                saveRemote(owner, completed, total, token, done)
            }
            prefetch?.cancel()

            // The loop only exits by finishing (reached the start/floor), erroring, or hitting the cap —
            // in every non-paused/cancelled case the job is complete, so clear it. Pause/cancel cancel the
            // job (isActive == false) and keep the record for resume.
            if (isActive) {
                finish(owner, roomId, displayName, completed)
            }
        }
    }

    @Synchronized
    private fun finish(owner: String, roomId: String, displayName: String, completed: Int) {
        // A cancel/park can slip in between the loop's last isActive check and this lock — the job's
        // state is already gone then, and a "finished" popup for a cancelled job would be a lie.
        if (owner != shownOwner || progress.currentValue?.roomId != roomId) return
        clearRecord(owner)
        progress.post(null)
        // PopupAlertManager queues and shows on whatever activity is foreground (or the next one to
        // resume), so this feedback reaches the user anywhere in the app.
        val (title, description) = if (completed == 0) {
            stringProvider.getString(CommonStrings.mass_redaction_nothing_found_title) to
                    stringProvider.getString(CommonStrings.mass_redaction_nothing_found, displayName)
        } else {
            stringProvider.getString(CommonStrings.mass_redaction_finished_title) to
                    stringProvider.getString(CommonStrings.mass_redaction_finished, completed, displayName)
        }
        popupAlertManager.postVectorAlert(
                DefaultVectorAlert(
                        uid = "mass_redaction_result",
                        title = title,
                        description = description,
                        iconId = null,
                )
        )
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

        // Concurrent redactions in flight when the user chose no delay; enough to hide round-trip
        // latency, small enough not to trip typical rate limits.
        private const val REDACT_PARALLELISM = 5

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
