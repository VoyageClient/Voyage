/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions.data

import im.vector.app.ActiveSessionDataSource
import kotlinx.coroutines.CoroutineExceptionHandler
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
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.api.session.events.model.Content
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's recently used emojis (MSC4356 `m.recent_emoji`): `{"emoji", "total"}` entries, most recently used
 * first, where an "emoji" is a unicode emoji or a custom emote's mxc URI. Like Element Web, entries of the older
 * `io.element.recent_emoji` (`[emoji, total]` arrays) are merged in on read and only the stable type is written.
 * Every use is also handed to [FrecencyDataSource], which is what ranks them.
 */
@Singleton
class RecentEmojiDataSource @Inject constructor(
        private val activeSessionDataSource: ActiveSessionDataSource,
        private val frecencyDataSource: FrecencyDataSource,
) {

    private val coroutineScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Timber.w(e, "Failed to write recent emojis") }
    )
    private val writeMutex = Mutex()
    private val pending = mutableListOf<String>()
    private var flushJob: Job? = null

    fun getRecentEmojisSnapshot(): List<Pair<String, Int>> {
        val session = activeSessionDataSource.currentValue?.orNull() ?: return emptyList()
        val current = session.readRecentEmojis().toMutableList()
        // Reflect not-yet-flushed uses so the pickers show them immediately.
        synchronized(pending) { pending.toList() }.forEach { applyUse(current, it) }
        return current
    }

    /** Emojis and emote mxcs used here or counted by another client, highest frecency first. */
    fun frequentlyUsed(): List<Pair<String, Int>> = frecencyDataSource.snapshot().rank(getRecentEmojisSnapshot())

    /** Records uses of unicode emojis and/or custom emotes (by mxc URI). */
    fun recordEmojiUse(emojis: List<String>) {
        if (emojis.isEmpty()) return
        val session = activeSessionDataSource.currentValue?.orNull() ?: return
        frecencyDataSource.recordUses(emojis)
        synchronized(pending) {
            pending.addAll(emojis)
            flushJob?.cancel()
            flushJob = coroutineScope.launch {
                delay(FLUSH_DEBOUNCE_MS)
                // Once the debounce elapsed, complete the write even if another use is recorded meanwhile.
                withContext(NonCancellable) { flushPending(session) }
            }
        }
    }

    fun clear() {
        val session = activeSessionDataSource.currentValue?.orNull() ?: return
        synchronized(pending) {
            flushJob?.cancel()
            pending.clear()
        }
        coroutineScope.launch {
            writeMutex.withLock {
                session.writeRecentEmojis(emptyList())
                // Read merges the unstable type in, so it has to go too or the entries come back.
                session.accountDataService().updateUserAccountData(UserAccountDataTypes.TYPE_RECENT_EMOJI_UNSTABLE, mapOf(CONTENT_KEY to emptyList<Any>()))
            }
        }
    }

    private suspend fun flushPending(session: Session) {
        // The account may have switched during the debounce; the buffered uses belong to the
        // previous account and must not be written into the new one's data.
        if (activeSessionDataSource.currentValue?.orNull()?.sessionId != session.sessionId) {
            synchronized(pending) { pending.clear() }
            return
        }
        writeMutex.withLock {
            val toApply = synchronized(pending) { pending.toList().also { pending.clear() } }
            if (toApply.isEmpty()) return@withLock
            val current = session.readRecentEmojis().toMutableList()
            toApply.forEach { applyUse(current, it) }
            session.writeRecentEmojis(current.take(STORAGE_LIMIT))
        }
    }

    private suspend fun Session.writeRecentEmojis(emojis: List<Pair<String, Int>>) {
        val content = mapOf(CONTENT_KEY to emojis.map { (emoji, total) -> mapOf(EMOJI_KEY to emoji, TOTAL_KEY to total) })
        accountDataService().updateUserAccountData(UserAccountDataTypes.TYPE_RECENT_EMOJI, content)
    }

    private fun Session.readRecentEmojis(): List<Pair<String, Int>> {
        val stable = parse(accountDataService().getUserAccountDataEvent(UserAccountDataTypes.TYPE_RECENT_EMOJI)?.content).orEmpty()
        val unstable = parse(accountDataService().getUserAccountDataEvent(UserAccountDataTypes.TYPE_RECENT_EMOJI_UNSTABLE)?.content)
        return if (unstable == null) stable else merge(stable, unstable)
    }

    private fun applyUse(current: MutableList<Pair<String, Int>>, emoji: String) {
        val existingIndex = current.indexOfFirst { it.first == emoji }
        val newCount = if (existingIndex >= 0) current.removeAt(existingIndex).second + 1 else 1
        current.add(0, emoji to newCount)
    }

    companion object {
        private const val CONTENT_KEY = "recent_emoji"
        private const val EMOJI_KEY = "emoji"
        private const val TOTAL_KEY = "total"
        private const val STORAGE_LIMIT = 100
        private const val FLUSH_DEBOUNCE_MS = 2_000L
        private const val MAX_TOTAL = 9_007_199_254_740_991.0

        /**
         * Either shape, in either type: the stable `{"emoji", "total"}` objects or the older `[emoji, total]`
         * arrays, which we used to write to `m.recent_emoji` as well. Invalid entries are dropped (MSC4356);
         * emojis we can't show are kept, so writing the list back preserves them.
         */
        internal fun parse(content: Content?): List<Pair<String, Int>>? {
            val entries = content?.get(CONTENT_KEY) as? List<*> ?: return null
            return entries.mapNotNull { entry ->
                val (emoji, total) = when (entry) {
                    is Map<*, *> -> entry[EMOJI_KEY] to entry[TOTAL_KEY]
                    is List<*> -> entry.getOrNull(0) to entry.getOrNull(1)
                    else -> return@mapNotNull null
                }
                val value = (total as? Number)?.toDouble() ?: return@mapNotNull null
                if (emoji !is String || emoji.isEmpty() || value < 0 || value > MAX_TOTAL || value % 1.0 != 0.0) return@mapNotNull null
                emoji to value.coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
            }
        }

        /** Element Web's merge: every emoji once, at its first position, with the higher of its totals. */
        internal fun merge(stable: List<Pair<String, Int>>, unstable: List<Pair<String, Int>>): List<Pair<String, Int>> {
            val merged = LinkedHashMap<String, Int>()
            (stable + unstable).forEach { (emoji, total) ->
                val existing = merged[emoji]
                if (existing == null || total > existing) merged[emoji] = total
            }
            return merged.map { it.key to it.value }
        }
    }
}
