/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions.data

import im.vector.app.ActiveSessionDataSource
import im.vector.app.core.di.ActiveSessionHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
 * Reads and updates the user's frequently used emojis, persisted in the `io.element.recent_emoji`
 * account data as a list of `[emoji, count]` pairs (Element web compatible). Recorded uses are
 * buffered and written in a single debounced request so rapid taps don't spam the server.
 */
@Singleton
class RecentEmojiDataSource @Inject constructor(
        private val activeSessionDataSource: ActiveSessionDataSource,
        private val activeSessionHolder: ActiveSessionHolder,
) {

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writeMutex = Mutex()
    private val pending = mutableListOf<String>()
    private var flushJob: Job? = null

    fun stream(): Flow<List<Pair<String, Int>>> {
        return activeSessionDataSource.stream()
                .flatMapLatest { optionalSession ->
                    val session = optionalSession.orNull() ?: return@flatMapLatest flowOf(emptyList())
                    session.flow().liveUserAccountData(UserAccountDataTypes.TYPE_RECENT_EMOJI)
                            .map { parse(it.getOrNull()?.content) }
                }
    }

    fun getRecentEmojisSnapshot(): List<Pair<String, Int>> {
        val current = readFromAccountData().toMutableList()
        // Reflect not-yet-flushed taps so the keyboard shows them immediately.
        synchronized(pending) { pending.toList() }.forEach { applyUse(current, it) }
        return current
    }

    fun recordEmojiUse(emojis: List<String>) {
        if (emojis.isEmpty()) return
        synchronized(pending) {
            pending.addAll(emojis)
            flushJob?.cancel()
            flushJob = coroutineScope.launch {
                delay(FLUSH_DEBOUNCE_MS)
                // Once the debounce elapsed, complete the write even if another use is recorded meanwhile.
                withContext(NonCancellable) { flushPending() }
            }
        }
    }

    fun clear() {
        synchronized(pending) {
            flushJob?.cancel()
            pending.clear()
        }
        coroutineScope.launch {
            val session = activeSessionHolder.getSafeActiveSession() ?: return@launch
            writeMutex.withLock {
                session.accountDataService().updateUserAccountData(
                        UserAccountDataTypes.TYPE_RECENT_EMOJI,
                        mapOf(CONTENT_KEY to emptyList<List<Any>>())
                )
            }
        }
    }

    private suspend fun flushPending() {
        val session = activeSessionHolder.getSafeActiveSession() ?: return
        writeMutex.withLock {
            val toApply = synchronized(pending) { pending.toList().also { pending.clear() } }
            if (toApply.isEmpty()) return@withLock
            val current = parse(session.accountDataService().getUserAccountDataEvent(UserAccountDataTypes.TYPE_RECENT_EMOJI)?.content)
                    .toMutableList()
            toApply.forEach { applyUse(current, it) }
            session.accountDataService().updateUserAccountData(
                    UserAccountDataTypes.TYPE_RECENT_EMOJI,
                    mapOf(CONTENT_KEY to current.take(STORAGE_LIMIT).map { listOf(it.first, it.second) })
            )
        }
    }

    private fun readFromAccountData(): List<Pair<String, Int>> {
        val session = activeSessionHolder.getSafeActiveSession() ?: return emptyList()
        return parse(session.accountDataService().getUserAccountDataEvent(UserAccountDataTypes.TYPE_RECENT_EMOJI)?.content)
    }

    private fun applyUse(current: MutableList<Pair<String, Int>>, emoji: String) {
        val existingIndex = current.indexOfFirst { it.first == emoji }
        val newCount = if (existingIndex >= 0) current.removeAt(existingIndex).second + 1 else 1
        current.add(0, emoji to newCount)
    }

    private fun parse(content: Content?): List<Pair<String, Int>> {
        val pairs = content?.get(CONTENT_KEY) as? List<*> ?: return emptyList()
        return pairs.mapNotNull { entry ->
            val pair = entry as? List<*> ?: return@mapNotNull null
            val emoji = pair.getOrNull(0) as? String ?: return@mapNotNull null
            val count = (pair.getOrNull(1) as? Number)?.toInt() ?: return@mapNotNull null
            emoji to count
        }
    }

    companion object {
        private const val CONTENT_KEY = "recent_emoji"
        private const val STORAGE_LIMIT = 100
        private const val FLUSH_DEBOUNCE_MS = 2_000L
    }
}
