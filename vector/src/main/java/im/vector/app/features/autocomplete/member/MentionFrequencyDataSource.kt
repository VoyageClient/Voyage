/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.autocomplete.member

import im.vector.app.ActiveSessionDataSource
import im.vector.app.core.di.ActiveSessionHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-room mention counts (`{ roomId: { userId: count } }`), backed up to the
 * `im.voyage.setting.mention_frequency` account data so the @-autocomplete ranking follows the
 * account across devices. Writes are debounced so rapid mentions don't spam the server.
 */
@Singleton
class MentionFrequencyDataSource @Inject constructor(
        private val activeSessionDataSource: ActiveSessionDataSource,
        private val activeSessionHolder: ActiveSessionHolder,
) {

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writeMutex = Mutex()
    private val lock = Any()

    // Held in memory so the autocomplete query path can rank synchronously.
    private val counts = mutableMapOf<String, MutableMap<String, Int>>()

    // Increments not yet flushed to the server.
    private val pending = mutableMapOf<String, MutableMap<String, Int>>()
    private var flushJob: Job? = null

    init {
        // Keeps the ranking in sync when another device updates the count.
        coroutineScope.launch {
            activeSessionDataSource.stream().collectLatest { optionalSession ->
                val session = optionalSession.orNull() ?: return@collectLatest
                session.flow().liveUserAccountData(UserAccountDataTypes.TYPE_MENTION_FREQUENCY)
                        .collectLatest { optionalEvent ->
                            val server = parse(optionalEvent.getOrNull()?.content)
                            synchronized(lock) {
                                counts.clear()
                                counts.putAll(server)
                                // Don't lose local increments the server echo doesn't reflect yet.
                                pending.forEach { (roomId, users) ->
                                    val target = counts.getOrPut(roomId) { mutableMapOf() }
                                    users.forEach { (userId, delta) -> target[userId] = (target[userId] ?: 0) + delta }
                                }
                            }
                        }
            }
        }
    }

    fun frequencies(roomId: String): Map<String, Int> = synchronized(lock) {
        counts[roomId]?.toMap().orEmpty()
    }

    fun record(roomId: String, userId: String) {
        synchronized(lock) {
            counts.getOrPut(roomId) { mutableMapOf() }.merge(userId, 1, Int::plus)
            pending.getOrPut(roomId) { mutableMapOf() }.merge(userId, 1, Int::plus)
            flushJob?.cancel()
            flushJob = coroutineScope.launch {
                delay(FLUSH_DEBOUNCE_MS)
                // Once the debounce elapsed, complete the write even if another mention is recorded meanwhile.
                withContext(NonCancellable) { flushPending() }
            }
        }
    }

    private suspend fun flushPending() {
        val session = activeSessionHolder.getSafeActiveSession() ?: return
        writeMutex.withLock {
            val toApply = synchronized(lock) {
                pending.mapValues { it.value.toMap() }.also { pending.clear() }
            }
            if (toApply.isEmpty()) return@withLock
            val current = parse(session.accountDataService().getUserAccountDataEvent(UserAccountDataTypes.TYPE_MENTION_FREQUENCY)?.content)
            toApply.forEach { (roomId, users) ->
                val target = current.getOrPut(roomId) { mutableMapOf() }
                users.forEach { (userId, delta) -> target[userId] = (target[userId] ?: 0) + delta }
            }
            session.accountDataService().updateUserAccountData(
                    UserAccountDataTypes.TYPE_MENTION_FREQUENCY,
                    mapOf(CONTENT_KEY to current)
            )
        }
    }

    private fun parse(content: Content?): MutableMap<String, MutableMap<String, Int>> {
        val root = content?.get(CONTENT_KEY) as? Map<*, *> ?: return mutableMapOf()
        val result = mutableMapOf<String, MutableMap<String, Int>>()
        root.forEach { (roomId, users) ->
            if (roomId !is String) return@forEach
            val userMap = users as? Map<*, *> ?: return@forEach
            val inner = mutableMapOf<String, Int>()
            userMap.forEach { (userId, count) ->
                val c = (count as? Number)?.toInt()
                if (userId is String && c != null && c > 0) inner[userId] = c
            }
            if (inner.isNotEmpty()) result[roomId] = inner
        }
        return result
    }

    companion object {
        private const val CONTENT_KEY = "mention_frequency"
        private const val FLUSH_DEBOUNCE_MS = 2_000L
    }
}
