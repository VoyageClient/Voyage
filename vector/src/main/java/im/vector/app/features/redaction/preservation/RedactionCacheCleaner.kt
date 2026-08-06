/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.redaction.preservation

import dagger.Lazy
import im.vector.app.core.di.ActiveSessionHolder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Empties the two preservation caches, which are cleared by different routes.
 *
 * The preserved event data has no button of its own: it goes when the app cache is cleared, if the
 * room (or the account default) allows it. The preserved media files are the opposite — never touched
 * by an app-cache clear, only by the explicit account-wide and per-room actions.
 *
 * Nothing expires on its own, so these are the only deletions besides sign-out.
 */
@Singleton
class RedactionCacheCleaner @Inject constructor(
        // Lazy: ActiveSessionHolder builds ConfigureAndStartSessionUseCase, which injects this.
        private val activeSessionHolder: Lazy<ActiveSessionHolder>,
        private val settings: RedactionPreservationSettings,
        private val mediaStore: PreservedMediaStore,
        private val repository: RedactedContentRepository,
) {

    /** The app's own Clear cache, applied to the preserved event data of every room that allows it. */
    suspend fun onAppCacheCleared() {
        val session = activeSessionHolder.get().getSafeActiveSession() ?: return
        val service = session.redactedContentService()
        val kept = service.roomsWithPreservedContent().filterNot { settings.clearsWithAppCache(it) }
        service.clearExcept(kept)
        // The in-memory copy is authoritative at bind time, so leaving it would keep rendering
        // content that has just been deleted for the rest of the process.
        repository.clearCaches()
    }

    /** Account-wide "Clear redacted media cache": the files only, every room. */
    suspend fun clearMediaCache() = mediaStore.clear()

    /** Per-room "Clear room redacted media cache". */
    suspend fun clearRoomMediaCache(roomId: String) = mediaStore.clearRoom(roomId)
}
