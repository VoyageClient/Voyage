/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.picker

import im.vector.app.ActiveSessionDataSource
import im.vector.app.features.imagepack.ResolvedImage
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
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.imagepack.ImagePackUsage
import org.matrix.android.sdk.api.session.room.model.message.ImageInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and updates the user's frequently-used stickers, persisted in the `io.element.recent_stickers`
 * account data as a list of `[stickerContent, count]` pairs (mirroring `io.element.recent_emoji`).
 * Recorded uses are buffered and written in a single debounced request.
 */
@Singleton
class RecentStickerDataSource @Inject constructor(
        private val activeSessionDataSource: ActiveSessionDataSource,
) {

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writeMutex = Mutex()
    private val pending = mutableListOf<ResolvedImage>()
    private var flushJob: Job? = null

    /** Frequently-used stickers, most-used first. */
    fun getRecentStickersSnapshot(): List<ResolvedImage> {
        val current = read().toMutableList()
        synchronized(pending) { pending.toList() }.forEach { applyUse(current, it) }
        return current.sortedByDescending { it.second }.map { it.first }
    }

    fun recordStickerUse(image: ResolvedImage) {
        val session = activeSessionDataSource.currentValue?.orNull() ?: return
        synchronized(pending) {
            pending.add(image)
            flushJob?.cancel()
            flushJob = coroutineScope.launch {
                delay(FLUSH_DEBOUNCE_MS)
                withContext(NonCancellable) { flushPending(session) }
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
            val current = parse(session.accountDataService().getUserAccountDataEvent(UserAccountDataTypes.TYPE_RECENT_STICKERS)?.content)
                    .toMutableList()
            toApply.forEach { applyUse(current, it) }
            val payload = current
                    .sortedByDescending { it.second }
                    .take(STORAGE_LIMIT)
                    .map { listOf(it.first.toStickerContent(), it.second) }
            session.accountDataService().updateUserAccountData(
                    UserAccountDataTypes.TYPE_RECENT_STICKERS,
                    mapOf(CONTENT_KEY to payload)
            )
        }
    }

    // Drop recents whose sticker was deleted from the packs (they'd render blank).
    // No-op on empty [validMxcs] (packs not loaded yet) so a transient doesn't wipe the list.
    fun pruneToValidMxcs(validMxcs: Set<String>) {
        if (validMxcs.isEmpty()) return
        val session = activeSessionDataSource.currentValue?.orNull() ?: return
        coroutineScope.launch {
            writeMutex.withLock {
                val current = parse(session.accountDataService().getUserAccountDataEvent(UserAccountDataTypes.TYPE_RECENT_STICKERS)?.content)
                val kept = current.filter { it.first.mxcUrl in validMxcs }
                if (kept.size == current.size) return@withLock
                val payload = kept
                        .sortedByDescending { it.second }
                        .take(STORAGE_LIMIT)
                        .map { listOf(it.first.toStickerContent(), it.second) }
                session.accountDataService().updateUserAccountData(
                        UserAccountDataTypes.TYPE_RECENT_STICKERS,
                        mapOf(CONTENT_KEY to payload)
                )
            }
        }
    }

    private fun read(): List<Pair<ResolvedImage, Int>> {
        val session = activeSessionDataSource.currentValue?.orNull() ?: return emptyList()
        return parse(session.accountDataService().getUserAccountDataEvent(UserAccountDataTypes.TYPE_RECENT_STICKERS)?.content)
    }

    private fun applyUse(current: MutableList<Pair<ResolvedImage, Int>>, image: ResolvedImage) {
        val index = current.indexOfFirst { it.first.mxcUrl == image.mxcUrl }
        val newCount = if (index >= 0) current.removeAt(index).second + 1 else 1
        current.add(0, image to newCount)
    }

    private fun parse(content: Content?): List<Pair<ResolvedImage, Int>> {
        val list = content?.get(CONTENT_KEY) as? List<*> ?: return emptyList()
        return list.mapNotNull { entry ->
            val pair = entry as? List<*> ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val stickerContent = pair.getOrNull(0) as? Map<String, Any> ?: return@mapNotNull null
            val count = (pair.getOrNull(1) as? Number)?.toInt() ?: return@mapNotNull null
            val image = stickerContent.toResolvedImage() ?: return@mapNotNull null
            image to count
        }
    }

    private fun ResolvedImage.toStickerContent(): Map<String, Any> {
        return buildMap {
            put("url", mxcUrl)
            (body ?: shortcode).takeIf { it.isNotEmpty() }?.let { put("body", it) }
            info?.let { put("info", it.toContent()) }
        }
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

    companion object {
        private const val CONTENT_KEY = "recent_stickers"
        private const val STORAGE_LIMIT = 32
        private const val FLUSH_DEBOUNCE_MS = 2_000L
    }
}
