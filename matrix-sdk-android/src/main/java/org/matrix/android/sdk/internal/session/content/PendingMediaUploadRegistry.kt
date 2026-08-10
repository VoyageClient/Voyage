/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.matrix.android.sdk.internal.di.SessionDownloadsDirectory
import org.matrix.android.sdk.internal.session.SessionScope
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Content URIs reserved through MSC2246 whose bytes are still being uploaded, mapped to the local copy
 * of those bytes.
 *
 * Downloading such a URI would make the homeserver stall until the upload lands (and then time out), so
 * the sender's own timeline reads from here instead. Persisted to disk: the byte upload can outlive the
 * process (WorkManager retries across restarts), and losing the redirect meanwhile would leave the
 * sender's own media unloadable everywhere until the upload finally lands.
 */
@SessionScope
internal class PendingMediaUploadRegistry @Inject constructor(
        @SessionDownloadsDirectory sessionCacheDirectory: File,
) {

    @JsonClass(generateAdapter = true)
    internal data class PersistedEntry(
            @Json(name = "content_uri") val contentUri: String,
            @Json(name = "local_file") val localFilePath: String,
            @Json(name = "owned_files") val ownedFilePaths: List<String>,
            @Json(name = "event_ids") val eventIds: List<String>,
            @Json(name = "created_ts") val createdTs: Long = 0,
    )

    @JsonClass(generateAdapter = true)
    internal data class PersistedState(
            @Json(name = "entries") val entries: List<PersistedEntry>,
    )

    private data class Pending(val localFile: File, val ownedFiles: List<File>, val eventIds: Set<String>, val createdTs: Long)

    private val backingFile = File(sessionCacheDirectory, "pending_media_uploads.json")
    private val adapter = MoshiProvider.providesMoshi().adapter(PersistedState::class.java)
    private val pending = ConcurrentHashMap<String, Pending>()

    // Serializes persist() calls off the caller thread: discardForEvent runs from main-thread
    // redaction handlers, and a whole-map JSON rewrite is disk IO.
    private val persistExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    init {
        tryOrNull("Failed to load pending media uploads") {
            if (backingFile.exists()) {
                val now = System.currentTimeMillis()
                adapter.fromJson(backingFile.readText())?.entries?.forEach { entry ->
                    val localFile = File(entry.localFilePath)
                    // An entry the byte-upload worker never cleans up (e.g. its clear() persist
                    // failed) must not redirect this media to the local copy forever.
                    if (localFile.exists() && now - entry.createdTs < MAX_ENTRY_AGE_MS) {
                        pending[entry.contentUri] = Pending(localFile, entry.ownedFilePaths.map(::File), entry.eventIds.toSet(), entry.createdTs)
                    }
                }
            }
        }
    }

    private fun persist() {
        persistExecutor.execute {
            tryOrNull("Failed to persist pending media uploads") {
                val state = PersistedState(
                        pending.map { (contentUri, entry) ->
                            PersistedEntry(contentUri, entry.localFile.path, entry.ownedFiles.map { it.path }, entry.eventIds.toList(), entry.createdTs)
                        }
                )
                backingFile.parentFile?.mkdirs()
                backingFile.writeText(adapter.toJson(state))
            }
        }
    }

    /** [ownedFiles] are dropped along with [localFile] if the send is cancelled. */
    fun markPending(contentUri: String, localFile: File, ownedFiles: List<File>, eventIds: Set<String>) {
        pending[contentUri] = Pending(localFile, ownedFiles, eventIds, System.currentTimeMillis())
        persist()
    }

    fun clear(contentUri: String) {
        if (pending.remove(contentUri) != null) {
            persist()
        }
    }

    fun getLocalFile(contentUri: String): File? {
        return pending[contentUri]?.localFile?.takeIf { it.exists() }
    }

    fun isPending(contentUri: String): Boolean = pending.containsKey(contentUri)

    /**
     * Cancelling a send tears down the queued byte upload before it can clean up after itself, so the
     * bytes it was holding have to be dropped here instead.
     */
    fun discardForEvent(eventId: String) {
        val discarded = pending.entries.filter { eventId in it.value.eventIds }
        discarded.forEach { (contentUri, entry) ->
            pending.remove(contentUri)
            (entry.ownedFiles + entry.localFile).forEach { file -> tryOrNull { file.delete() } }
        }
        if (discarded.isNotEmpty()) {
            persist()
        }
    }

    companion object {
        private const val MAX_ENTRY_AGE_MS = 48 * 3600_000L
    }
}
