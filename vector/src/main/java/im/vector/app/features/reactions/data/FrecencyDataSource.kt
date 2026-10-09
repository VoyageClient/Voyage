/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions.data

import im.vector.app.ActiveSessionDataSource
import im.vector.app.features.imagepack.EmojiShortNames
import im.vector.app.features.imagepack.ResolvedImage
import im.vector.lib.core.utils.timer.Clock
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
import org.matrix.android.sdk.api.MatrixUrls.isMxcUrl
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.imagepack.ImagePackUsage
import org.matrix.android.sdk.api.session.room.model.message.ImageInfo
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/**
 * Frecency of unicode emojis, custom emotes and stickers, in `im.voyage.setting.frecency`
 * account data: `{"emoji"|"emoticons"|"stickers": {key: {"total_uses", "recent_uses": [ts…], …}}}`.
 * The MSC4356 `m.recent_emoji` other clients share has counts but no times, so it can't rank by recency;
 * this does, and replaces our former `io.element.recent_emoticons` / `io.element.recent_stickers`.
 */
@Singleton
class FrecencyDataSource @Inject constructor(
        private val activeSessionDataSource: ActiveSessionDataSource,
        private val clock: Clock,
        private val emojiShortNames: EmojiShortNames,
) {

    /** Keys are an emote's mxc URI, or a unicode emoji in its canonical form (see EmojiShortNames.canonical). */
    class Snapshot internal constructor(private val history: History, private val now: Long, private val canonical: (String) -> String) {

        /** The search multiplier (÷100) for an emoji or emote mxc, null when never used here. */
        fun score(emojiOrMxc: String): Int? = entryOf(emojiOrMxc)?.let { score(it.recentUses, now) }

        /**
         * Every emoji and emote in [shared] (`m.recent_emoji`, which other clients add to) or used here, highest
         * frecency first. A use counted elsewhere has no time, so it counts as one just now.
         */
        fun rank(shared: List<Pair<String, Int>>): List<Pair<String, Int>> {
            val totals = LinkedHashMap<String, Int>()
            shared.forEach { (value, total) ->
                val key = keyOf(value)
                totals[key] = maxOf(totals[key] ?: 0, total)
            }
            (history.emoji.keys + history.emoticons.keys).forEach { if (it !in totals) totals[it] = 0 }
            return totals.entries
                    .map { (key, sharedTotal) ->
                        val entry = entryOf(key)
                        val totalUses = maxOf(sharedTotal, entry?.totalUses ?: 0)
                        key to frecency(totalUses, entry?.recentUses.orEmpty().ifEmpty { listOf(now) }, now)
                    }
                    .filter { it.second > 0 }
                    .sortedByDescending { it.second }
        }

        fun frequentStickers(): List<ResolvedImage> = history.stickers.entries
                .mapNotNull { (_, entry) -> entry.sticker?.let { it to entry.frecency(now) } }
                .sortedByDescending { it.second }
                .map { it.first }

        private fun keyOf(emojiOrMxc: String) = if (emojiOrMxc.isMxcUrl()) emojiOrMxc else canonical(emojiOrMxc)

        private fun entryOf(emojiOrMxc: String): Entry? {
            val key = keyOf(emojiOrMxc)
            return if (key.isMxcUrl()) history.emoticons[key] else history.emoji[key]
        }
    }

    private val coroutineScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Timber.w(e, "Failed to write frecency") }
    )
    private val writeMutex = Mutex()
    private val pending = mutableListOf<(History) -> Unit>()
    private var flushJob: Job? = null

    fun snapshot(): Snapshot {
        val now = clock.epochMillis()
        val session = activeSessionDataSource.currentValue?.orNull() ?: return Snapshot(History(), now, emojiShortNames::canonical)
        val history = session.readHistory(now)
        synchronized(pending) { pending.toList() }.forEach { it(history) }
        if (history.migrated) schedule(session) {}
        return Snapshot(history, now, emojiShortNames::canonical)
    }

    /** Records uses of unicode emojis and/or custom emotes (by mxc URI). Go through RecentEmojiDataSource instead. */
    internal fun recordUses(emojisOrMxcs: List<String>) {
        val now = clock.epochMillis()
        enqueue { history ->
            emojisOrMxcs.forEach {
                if (it.isMxcUrl()) history.emoticons.use(it, now) else history.emoji.use(emojiShortNames.canonical(it), now)
            }
        }
    }

    fun recordStickerUse(image: ResolvedImage) {
        val now = clock.epochMillis()
        enqueue { history -> history.stickers.use(image.mxcUrl, now) { it.copy(stickerContent = image.toStickerContent()) } }
    }

    // A no-op on an empty set (packs not loaded yet), so a transient doesn't wipe the history.
    fun pruneEmotes(validMxcs: Set<String>) {
        if (validMxcs.isNotEmpty()) enqueue { history -> history.emoticons.keys.retainAll(validMxcs) }
    }

    fun pruneStickers(validMxcs: Set<String>) {
        if (validMxcs.isNotEmpty()) enqueue { history -> history.stickers.keys.retainAll(validMxcs) }
    }

    private fun enqueue(change: (History) -> Unit) {
        val session = activeSessionDataSource.currentValue?.orNull() ?: return
        schedule(session, change)
    }

    private fun schedule(session: Session, change: (History) -> Unit) {
        synchronized(pending) {
            pending.add(change)
            flushJob?.cancel()
            flushJob = coroutineScope.launch {
                delay(FLUSH_DEBOUNCE_MS)
                withContext(NonCancellable) { flushPending(session) }
            }
        }
    }

    private suspend fun flushPending(session: Session) {
        // The account may have switched during the debounce; the buffered changes belong to the
        // previous account and must not be written into the new one's data.
        if (activeSessionDataSource.currentValue?.orNull()?.sessionId != session.sessionId) {
            synchronized(pending) { pending.clear() }
            return
        }
        writeMutex.withLock {
            val changes = synchronized(pending) { pending.toList().also { pending.clear() } }
            val now = clock.epochMillis()
            val history = session.readHistory(now)
            // Compared as rebuilt content: what's stored comes back from JSON with every number a Double.
            val before = history.toContent(now)
            changes.forEach { it(history) }
            val after = history.toContent(now)
            if (after != before || history.migrated) {
                session.accountDataService().updateUserAccountData(UserAccountDataTypes.TYPE_FRECENCY, after)
            }
            session.deleteLegacyRecents()
        }
    }

    private fun Session.readHistory(now: Long): History {
        val accountData = accountDataService()
        return History.parse(
                content = accountData.getUserAccountDataEvent(UserAccountDataTypes.TYPE_FRECENCY)?.content,
                legacyEmotes = { accountData.getUserAccountDataEvent(UserAccountDataTypes.TYPE_RECENT_EMOTICONS)?.content },
                legacyStickers = { accountData.getUserAccountDataEvent(UserAccountDataTypes.TYPE_RECENT_STICKERS)?.content },
                now = now,
        )
    }

    // Only once their entries are in the frecency event, which the caller has just written.
    private suspend fun Session.deleteLegacyRecents() {
        listOf(UserAccountDataTypes.TYPE_RECENT_EMOTICONS, UserAccountDataTypes.TYPE_RECENT_STICKERS).forEach { type ->
            if (accountDataService().getUserAccountDataEvent(type)?.content.isNullOrEmpty()) return@forEach
            try {
                accountDataService().deleteUserAccountData(type)
            } catch (failure: Throwable) {
                // MSC3391 deletion is unstable and often disabled; empty content is how clearing reads elsewhere.
                Timber.w(failure, "Deleting $type failed, clearing it instead")
                accountDataService().updateUserAccountData(type, emptyMap())
            }
        }
    }

    internal data class Entry(
            val totalUses: Int,
            val recentUses: List<Long>,
            val stickerContent: Map<String, Any>? = null,
    ) {
        val sticker: ResolvedImage? get() = stickerContent?.toResolvedImage()

        fun frecency(now: Long) = frecency(totalUses, recentUses, now)
    }

    internal class History(
            val emoji: MutableMap<String, Entry> = mutableMapOf(),
            val emoticons: MutableMap<String, Entry> = mutableMapOf(),
            val stickers: MutableMap<String, Entry> = mutableMapOf(),
            /** Filled from the legacy recents, which still need writing here and deleting. */
            val migrated: Boolean = false,
    ) {

        fun toContent(now: Long): Content = mapOf(
                EMOJI_KEY to emoji.toJson(EMOJI_LIMIT, now),
                EMOTICONS_KEY to emoticons.toJson(EMOTICONS_LIMIT, now),
                STICKERS_KEY to stickers.toJson(STICKERS_LIMIT, now),
        )

        private fun Map<String, Entry>.toJson(limit: Int, now: Long): Map<String, Any> {
            return entries
                    .sortedByDescending { it.value.frecency(now) }
                    .take(limit)
                    .associate { (key, entry) ->
                        key to buildMap {
                            put(TOTAL_USES_KEY, entry.totalUses)
                            put(RECENT_USES_KEY, entry.recentUses)
                            entry.stickerContent?.let { put(CONTENT_KEY, it) }
                        }
                    }
        }

        companion object {
            fun parse(content: Content?, legacyEmotes: () -> Content?, legacyStickers: () -> Content?, now: Long): History {
                val emoticons = (content?.get(EMOTICONS_KEY) as? Map<*, *>)?.let { parseEntries(it) }
                val stickers = (content?.get(STICKERS_KEY) as? Map<*, *>)?.let { parseEntries(it) }
                val migratedEmotes = if (emoticons == null) parseLegacyEmotes(legacyEmotes(), now) else null
                val migratedStickers = if (stickers == null) parseLegacyStickers(legacyStickers(), now) else null
                return History(
                        emoji = (content?.get(EMOJI_KEY) as? Map<*, *>)?.let { parseEntries(it) } ?: mutableMapOf(),
                        emoticons = emoticons ?: migratedEmotes ?: mutableMapOf(),
                        stickers = stickers ?: migratedStickers ?: mutableMapOf(),
                        migrated = !migratedEmotes.isNullOrEmpty() || !migratedStickers.isNullOrEmpty(),
                )
            }

            private fun parseEntries(map: Map<*, *>): MutableMap<String, Entry> = map.entries.mapNotNull { (key, value) ->
                val name = key as? String ?: return@mapNotNull null
                val fields = value as? Map<*, *> ?: return@mapNotNull null
                val recentUses = (fields[RECENT_USES_KEY] as? List<*>)?.mapNotNull { (it as? Number)?.toLong() }?.sorted()?.takeLast(MAX_SAMPLES)
                        ?: return@mapNotNull null
                @Suppress("UNCHECKED_CAST")
                name to Entry(
                        totalUses = (fields[TOTAL_USES_KEY] as? Number)?.toInt() ?: recentUses.size,
                        recentUses = recentUses,
                        stickerContent = fields[CONTENT_KEY] as? Map<String, Any>,
                )
            }.toMap(mutableMapOf())

            // `[[mxc, shortcode], count]`. The old format kept no times, so treat the last use as the migration.
            private fun parseLegacyEmotes(content: Content?, now: Long): MutableMap<String, Entry> =
                    (content?.get(LEGACY_EMOTICONS_KEY) as? List<*>).orEmpty().mapNotNull { item ->
                        val pair = item as? List<*> ?: return@mapNotNull null
                        val ref = pair.getOrNull(0) as? List<*> ?: return@mapNotNull null
                        val mxcUrl = ref.getOrNull(0) as? String ?: return@mapNotNull null
                        val count = (pair.getOrNull(1) as? Number)?.toInt() ?: return@mapNotNull null
                        mxcUrl to Entry(totalUses = count, recentUses = listOf(now))
                    }.toMap(mutableMapOf())

            // `[stickerContent, count]`, migrated like the emotes.
            private fun parseLegacyStickers(content: Content?, now: Long): MutableMap<String, Entry> =
                    (content?.get(LEGACY_STICKERS_KEY) as? List<*>).orEmpty().mapNotNull { item ->
                        val pair = item as? List<*> ?: return@mapNotNull null
                        @Suppress("UNCHECKED_CAST")
                        val stickerContent = pair.getOrNull(0) as? Map<String, Any> ?: return@mapNotNull null
                        val mxcUrl = stickerContent["url"] as? String ?: return@mapNotNull null
                        val count = (pair.getOrNull(1) as? Number)?.toInt() ?: return@mapNotNull null
                        mxcUrl to Entry(totalUses = count, recentUses = listOf(now), stickerContent = stickerContent)
                    }.toMap(mutableMapOf())
        }
    }

    companion object {
        private const val EMOJI_KEY = "emoji"
        private const val EMOTICONS_KEY = "emoticons"
        private const val STICKERS_KEY = "stickers"
        private const val TOTAL_USES_KEY = "total_uses"
        private const val RECENT_USES_KEY = "recent_uses"
        private const val CONTENT_KEY = "content"
        private const val LEGACY_EMOTICONS_KEY = "recent_emoticons"
        private const val LEGACY_STICKERS_KEY = "recent_stickers"
        private const val MAX_SAMPLES = 10
        private const val EMOJI_LIMIT = 200
        private const val EMOTICONS_LIMIT = 64
        private const val STICKERS_LIMIT = 32
        private const val FLUSH_DEBOUNCE_MS = 2_000L
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        private fun MutableMap<String, Entry>.use(key: String, now: Long, update: (Entry) -> Entry = { it }) {
            val entry = this[key]
            val used = Entry(
                    totalUses = (entry?.totalUses ?: 0) + 1,
                    recentUses = (entry?.recentUses.orEmpty() + now).sorted().takeLast(MAX_SAMPLES),
                    stickerContent = entry?.stickerContent,
            )
            this[key] = update(used)
        }

        /** Each of the last [MAX_SAMPLES] uses weighs 100 within 3 days, decaying to 1 after 80. */
        fun score(recentUses: List<Long>, now: Long): Int = recentUses.takeLast(MAX_SAMPLES).sumOf { timestamp ->
            val days = (now - timestamp) / DAY_MS
            val weight: Int = when {
                days <= 3 -> 100
                days <= 15 -> 70
                days <= 30 -> 50
                days <= 45 -> 30
                days <= 80 -> 10
                else -> 1
            }
            weight
        }

        /** Orders frequently-used lists: total uses, weighted by how recent the sampled ones are. */
        fun frecency(totalUses: Int, recentUses: List<Long>, now: Long): Int {
            if (recentUses.isEmpty()) return 0
            return ceil(totalUses * score(recentUses, now).toDouble() / recentUses.size).toInt()
        }

        private fun ResolvedImage.toStickerContent(): Map<String, Any> = buildMap {
            put("url", mxcUrl)
            (body ?: shortcode).takeIf { it.isNotEmpty() }?.let { put("body", it) }
            info?.let { put("info", it.toContent()) }
        }

        private fun Map<String, Any>.toResolvedImage(): ResolvedImage? {
            val url = this["url"] as? String ?: return null
            val body = this["body"] as? String
            @Suppress("UNCHECKED_CAST")
            val info = (this["info"] as? Map<String, Any>)?.toModel<ImageInfo>()
            return ResolvedImage(
                    shortcode = body.orEmpty(),
                    mxcUrl = url,
                    body = body,
                    info = info,
                    usages = setOf(ImagePackUsage.STICKER),
                    packDisplayName = null,
            )
        }
    }
}
