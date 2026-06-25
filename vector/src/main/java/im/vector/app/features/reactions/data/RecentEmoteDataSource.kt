/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions.data

import im.vector.app.core.di.ActiveSessionHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.api.session.events.model.Content
import javax.inject.Inject
import javax.inject.Singleton

/** A recently-used custom emote: its `mxc://` and its shortcode (for insertion). */
data class RecentEmote(val mxcUrl: String, val shortcode: String)

/**
 * Reads and updates the user's frequently-used custom emoticons, persisted in `io.element.recent_emoticons`
 * account data as a list of `[[mxc, shortcode], count]` entries. Separate from the unicode `recent_emoji`.
 * Mirrors [RecentEmojiDataSource]: buffered, debounced writes.
 */
@Singleton
class RecentEmoteDataSource @Inject constructor(
        private val activeSessionHolder: ActiveSessionHolder,
) {

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writeMutex = Mutex()
    private val pending = mutableListOf<RecentEmote>()
    private var flushJob: Job? = null

    /** Frequently-used emotes with their use count, most-used first. */
    fun getRecentEmotesSnapshot(): List<Pair<RecentEmote, Int>> {
        val current = read().toMutableList()
        synchronized(pending) { pending.toList() }.forEach { applyUse(current, it) }
        return current.sortedByDescending { it.second }
    }

    fun recordEmoteUse(emote: RecentEmote) {
        synchronized(pending) {
            pending.add(emote)
            flushJob?.cancel()
            flushJob = coroutineScope.launch {
                delay(FLUSH_DEBOUNCE_MS)
                withContext(NonCancellable) { flushPending() }
            }
        }
    }

    private suspend fun flushPending() {
        val session = activeSessionHolder.getSafeActiveSession() ?: return
        writeMutex.withLock {
            val toApply = synchronized(pending) { pending.toList().also { pending.clear() } }
            if (toApply.isEmpty()) return@withLock
            val current = parse(session.accountDataService().getUserAccountDataEvent(UserAccountDataTypes.TYPE_RECENT_EMOTICONS)?.content)
                    .toMutableList()
            toApply.forEach { applyUse(current, it) }
            val payload = current
                    .sortedByDescending { it.second }
                    .take(STORAGE_LIMIT)
                    .map { listOf(listOf(it.first.mxcUrl, it.first.shortcode), it.second) }
            session.accountDataService().updateUserAccountData(
                    UserAccountDataTypes.TYPE_RECENT_EMOTICONS,
                    mapOf(CONTENT_KEY to payload)
            )
        }
    }

    /**
     * Rewrite stored shortcodes to the emote's current (disambiguated) form, looked up by its stable mxc.
     * Disambiguation is global, so a given emote's form doesn't depend on the room — this just keeps the
     * stored hint current after a duplicate is added/removed. Only writes when something actually changed.
     */
    fun migrateShortcodes(currentByMxc: Map<String, String>) {
        coroutineScope.launch {
            val session = activeSessionHolder.getSafeActiveSession() ?: return@launch
            writeMutex.withLock {
                val current = parse(session.accountDataService().getUserAccountDataEvent(UserAccountDataTypes.TYPE_RECENT_EMOTICONS)?.content)
                var changed = false
                val updated = current.map { (emote, count) ->
                    val newShortcode = currentByMxc[emote.mxcUrl]
                    if (newShortcode != null && newShortcode != emote.shortcode) {
                        changed = true
                        RecentEmote(emote.mxcUrl, newShortcode) to count
                    } else {
                        emote to count
                    }
                }
                if (!changed) return@withLock
                val payload = updated
                        .sortedByDescending { it.second }
                        .take(STORAGE_LIMIT)
                        .map { listOf(listOf(it.first.mxcUrl, it.first.shortcode), it.second) }
                session.accountDataService().updateUserAccountData(
                        UserAccountDataTypes.TYPE_RECENT_EMOTICONS,
                        mapOf(CONTENT_KEY to payload)
                )
            }
        }
    }

    private fun read(): List<Pair<RecentEmote, Int>> {
        val session = activeSessionHolder.getSafeActiveSession() ?: return emptyList()
        return parse(session.accountDataService().getUserAccountDataEvent(UserAccountDataTypes.TYPE_RECENT_EMOTICONS)?.content)
    }

    private fun applyUse(current: MutableList<Pair<RecentEmote, Int>>, emote: RecentEmote) {
        val index = current.indexOfFirst { it.first.mxcUrl == emote.mxcUrl }
        val newCount = if (index >= 0) current.removeAt(index).second + 1 else 1
        current.add(0, emote to newCount)
    }

    private fun parse(content: Content?): List<Pair<RecentEmote, Int>> {
        val list = content?.get(CONTENT_KEY) as? List<*> ?: return emptyList()
        return list.mapNotNull { entry ->
            val pair = entry as? List<*> ?: return@mapNotNull null
            val ref = pair.getOrNull(0) as? List<*> ?: return@mapNotNull null
            val mxc = ref.getOrNull(0) as? String ?: return@mapNotNull null
            val shortcode = ref.getOrNull(1) as? String ?: return@mapNotNull null
            val count = (pair.getOrNull(1) as? Number)?.toInt() ?: return@mapNotNull null
            RecentEmote(mxc, shortcode) to count
        }
    }

    companion object {
        private const val CONTENT_KEY = "recent_emoticons"
        private const val STORAGE_LIMIT = 32
        private const val FLUSH_DEBOUNCE_MS = 2_000L
    }
}
