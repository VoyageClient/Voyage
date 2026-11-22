/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.attachmentviewer

internal class VideoPlaybackPool {
    private val cached = LinkedHashMap<String, VideoPlayback>()
    private val active = ArrayList<VideoPlayback>()
    private var closed = false
    private var retainMultipleSources = true

    fun acquire(source: String): VideoPlayback? {
        if (closed) return null
        val prepared = cached.remove(source)
        val replacement = if (prepared == null) {
            cached.entries.firstOrNull { !retainMultipleSources || !it.value.canRetainPreparedSource }
                    ?: cached.entries.firstOrNull()?.takeIf { cached.size >= 2 }
        } else null
        val reusable = prepared ?: replacement?.let { cached.remove(it.key) }
        AttachmentViewerDebug.i { "stage=player-acquire cached=${reusable != null} sameSource=${prepared != null}" }
        return (reusable ?: VideoPlayback.create()).also { active.add(it) }
    }

    fun recycle(player: VideoPlayback, source: String) {
        if (closed) return
        active.remove(player)
        if (player.prepareForReuse()) {
            cached.put(source, player)?.takeIf { it !== player }?.release()
            while (cached.size > if (retainMultipleSources) 2 else 1) {
                val oldest = cached.entries.first()
                cached.remove(oldest.key)?.release()
            }
            AttachmentViewerDebug.i { "stage=player-cached count=${cached.size} retainSource=${player.canRetainPreparedSource}" }
        } else {
            player.release()
        }
    }

    fun fallbackToSingleDecoder(): Boolean {
        retainMultipleSources = false
        if (cached.isEmpty()) return false
        cached.values.forEach { it.release() }
        cached.clear()
        AttachmentViewerDebug.i { "stage=player-decoder-fallback" }
        return true
    }

    fun close() {
        closed = true
        cached.values.forEach { it.release() }
        cached.clear()
        active.forEach { it.release() }
        active.clear()
    }
}
