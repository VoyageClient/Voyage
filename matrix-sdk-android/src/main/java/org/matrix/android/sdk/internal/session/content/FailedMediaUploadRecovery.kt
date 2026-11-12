/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import androidx.core.net.toUri
import dagger.Lazy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.di.SessionFilesDirectory
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.room.send.LocalEchoEventFactory
import org.matrix.android.sdk.internal.session.room.send.LocalEchoRepository
import org.matrix.android.sdk.internal.session.room.send.queue.EventSenderProcessor
import org.matrix.android.sdk.internal.util.toMatrixErrorStr
import timber.log.Timber
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Turns a message whose MSC2246 media the server refused back into a failed message the sender can resend.
 * The event is already out (or on its way) by then, pointing at media that will never exist, so its remote
 * copy is redacted and a failed echo pointing at local copies of the refused bytes takes its place.
 */
@SessionScope
internal class FailedMediaUploadRecovery @Inject constructor(
        @SessionFilesDirectory sessionFilesDirectory: File,
        private val localEchoRepository: LocalEchoRepository,
        private val localEchoEventFactory: Lazy<LocalEchoEventFactory>,
        private val eventSenderProcessor: Lazy<EventSenderProcessor>,
) {

    private class Failed(val roomId: String) {
        /** Refused content URI to the local copy of its bytes. */
        val localUrls = mutableMapOf<String, String>()
        var message: String? = null
        var replacementId: String? = null
    }

    private val directory = File(sessionFilesDirectory, "failed_media_uploads")

    // By the original local echo id, kept so a gallery's later refusals update the same failed copy.
    private val failed = ConcurrentHashMap<String, Failed>()
    private val mutex = Mutex()

    /** False when the bytes could not be kept, leaving the caller to fail the upload the old way. */
    suspend fun onBytesRefused(params: UploadMediaBytesWorkerParams, clearFile: File, failure: Throwable): Boolean {
        val kept = preserve(clearFile, params.filename) ?: return false
        mutex.withLock {
            params.localEchoIds.forEach { ids ->
                val entry = failed.getOrPut(ids.eventId) { Failed(ids.roomId) }
                entry.localUrls[params.contentUri] = kept.toUri().toString()
                entry.message = failure.toMatrixErrorStr()
                settle(ids.eventId, entry)
            }
        }
        return true
    }

    /** The send of [localEchoId] went through; if its media was refused meanwhile, take it back. */
    suspend fun onEventSent(localEchoId: String, remoteEventId: String) {
        if (!failed.containsKey(localEchoId)) return
        mutex.withLock {
            val entry = failed[localEchoId] ?: return
            if (entry.replacementId == null) replace(localEchoId, remoteEventId, entry)
        }
    }

    fun isPreserved(url: String?): Boolean = url?.let(::preservedFile)?.exists() == true

    fun discardPreserved(url: String?) {
        url?.let(::preservedFile)?.let { tryOrNull { it.delete() } }
    }

    fun discardPreservedIn(content: Content?) {
        collectStrings(content).forEach(::discardPreserved)
    }

    private suspend fun settle(localEchoId: String, entry: Failed) {
        val replacementId = entry.replacementId
        if (replacementId != null) {
            // A later item of a gallery that was already taken back.
            rewriteEcho(replacementId, entry)
            return
        }
        val remoteId = localEchoRepository.getRemoteEchoId(localEchoId)
        if (remoteId != null) {
            replace(localEchoId, remoteId, entry)
        } else {
            // Not out yet, so this echo becomes the failed message; a send already in flight is taken back by onEventSent.
            rewriteEcho(localEchoId, entry)
            localEchoRepository.updateSendState(localEchoId, entry.roomId, SendState.UNDELIVERED, entry.message)
        }
    }

    private suspend fun replace(localEchoId: String, remoteEventId: String, entry: Failed) {
        val original = localEchoRepository.getUpToDateEcho(localEchoId)
                ?: localEchoRepository.getEvent(entry.roomId, remoteEventId)
        if (original == null) {
            Timber.e("## Refused media: no copy of $localEchoId left to take back")
            return
        }
        val factory = localEchoEventFactory.get()
        val replacement = factory.createEvent(
                roomId = entry.roomId,
                type = original.getClearType(),
                content = withLocalUrls(original.getClearContent(), entry.localUrls),
                verbatim = true,
        )
        factory.createLocalEcho(replacement)
        val replacementId = replacement.eventId ?: return
        localEchoRepository.updateSendState(replacementId, entry.roomId, SendState.UNDELIVERED, entry.message)
        entry.replacementId = replacementId
        Timber.w("## Refused media: redacting $remoteEventId, failed copy is $replacementId")
        val redaction = factory.createRedactEvent(entry.roomId, remoteEventId, reason = null).also { factory.createLocalEcho(it) }
        eventSenderProcessor.get().postRedaction(redaction, reason = null)
    }

    private suspend fun rewriteEcho(eventId: String, entry: Failed) {
        localEchoRepository.updateEcho(eventId) { event ->
            event.content = ContentMapper.map(withLocalUrls(event.asDomain(castJsonNumbers = true).content, entry.localUrls))
        }
    }

    private fun preserve(file: File, filename: String?): File? {
        if (!file.exists()) return null
        return tryOrNull("## Failed to keep refused media bytes") {
            directory.mkdirs()
            val target = File(directory, "${UUID.randomUUID()}-${filename?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "media"}")
            if (!file.renameTo(target)) {
                file.copyTo(target)
                file.delete()
            }
            target
        }
    }

    private fun preservedFile(url: String): File? {
        val path = url.toUri().takeIf { it.scheme == "file" }?.path ?: return null
        return File(path).takeIf { it.parentFile == directory }
    }

    private fun collectStrings(value: Any?): List<String> = when (value) {
        is Map<*, *> -> value.values.flatMap(::collectStrings)
        is List<*> -> value.flatMap(::collectStrings)
        is String -> listOf(value)
        else -> emptyList()
    }

    companion object {
        // Encrypted media keeps its URL inside the file info, which means nothing for a local copy.
        private val ENCRYPTED_KEYS = mapOf("file" to "url", "thumbnail_file" to "thumbnail_url")

        internal fun withLocalUrls(content: Content?, localUrls: Map<String, String>): Content? {
            @Suppress("UNCHECKED_CAST")
            return content?.let { rewrite(it, localUrls) as Content }
        }

        private fun rewrite(value: Any?, localUrls: Map<String, String>): Any? = when (value) {
            is Map<*, *> -> {
                val map = value.entries.associate { (key, entry) -> key.toString() to entry }.toMutableMap()
                ENCRYPTED_KEYS.forEach { (fileKey, urlKey) ->
                    val refused = (map[fileKey] as? Map<*, *>)?.get("url") as? String
                    refused?.let { localUrls[it] }?.let { local ->
                        map.remove(fileKey)
                        map[urlKey] = local
                    }
                }
                map.mapValues { (_, entry) -> rewrite(entry, localUrls) }
            }
            is List<*> -> value.map { rewrite(it, localUrls) }
            is String -> localUrls[value] ?: value
            else -> value
        }
    }
}
