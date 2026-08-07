/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.redaction.preservation

import dagger.Lazy
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.network.WifiDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageFileContent
import org.matrix.android.sdk.api.session.room.model.message.MessageImageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVideoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies a message's media into [PreservedMediaStore] so a later redaction can't take it away.
 *
 * "Only already-downloaded" is the default because it costs nothing: the blob is already in the
 * media cache and this just copies it somewhere the cache-clear doesn't reach. Downloading is opt-in
 * and additionally gated on a size cap and (by default) Wi-Fi, since a room where every redaction
 * pulls an attachment over mobile data is the pathological case.
 */
@Singleton
class PreservedMediaPreserver @Inject constructor(
        // Lazy: ActiveSessionHolder builds ConfigureAndStartSessionUseCase, which injects this.
        private val activeSessionHolder: Lazy<ActiveSessionHolder>,
        private val wifiDetector: WifiDetector,
        private val settings: RedactionPreservationSettings,
        private val store: PreservedMediaStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun preserve(roomId: String, eventId: String, content: MessageContent) {
        val attachment = content as? MessageWithAttachmentContent ?: return
        if (store.has(roomId, eventId)) return
        val session = activeSessionHolder.get().getSafeActiveSession() ?: return
        val fileService = session.fileService()

        if (!settings.preserveMediaFor(roomId)) return
        // Already in the cache: copying it out costs nothing, so the size cap and Wi-Fi gate only
        // apply to media that would have to be fetched.
        if (!fileService.isFileInCache(attachment) && !mayDownload(roomId, attachment)) return

        val source = try {
            fileService.downloadFile(attachment)
        } catch (failure: Throwable) {
            Timber.w(failure, "Could not preserve media for $eventId")
            return
        }
        withContext(Dispatchers.IO) {
            store.prepareRoomDir(roomId)
            runCatching { source.copyTo(store.fileFor(roomId, eventId), overwrite = true) }
                    .onFailure { Timber.w(it, "Could not copy preserved media for $eventId") }
        }
    }

    /** Fire-and-forget: the caller is inside a resolve that must not block on the download. */
    fun preserveAsync(roomId: String, eventId: String, content: MessageContent) {
        scope.launch { preserve(roomId, eventId, content) }
    }

    suspend fun clearForUser(userId: String) = store.clearForUser(userId)

    suspend fun discard(roomId: String, eventId: String) {
        withContext(Dispatchers.IO) { store.fileFor(roomId, eventId).delete() }
    }

    private fun mayDownload(roomId: String, attachment: MessageWithAttachmentContent): Boolean {
        val cap = settings.maxMediaSizeFor(roomId)
        val size = attachment.attachmentSize()
        if (cap > 0 && size > cap) return false
        return !settings.wifiOnlyFor(roomId) || wifiDetector.isConnectedToWifi()
    }

    // The size lives on each type's own info block, not on the shared interface.
    private fun MessageWithAttachmentContent.attachmentSize(): Long = when (this) {
        is MessageImageContent -> info?.size ?: 0L
        is MessageVideoContent -> videoInfo?.size ?: 0L
        is MessageAudioContent -> audioInfo?.size ?: 0L
        is MessageFileContent -> info?.size ?: 0L
        else -> 0L
    }
}
