/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.internal.session.SessionScope
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Content URIs reserved through MSC2246 whose bytes are still being uploaded, mapped to the local copy
 * of those bytes.
 *
 * Downloading such a URI would make the homeserver stall until the upload lands (and then time out), so
 * the sender's own timeline reads from here instead. In-memory on purpose: if the process dies mid-upload
 * the entry is simply gone and rendering falls back to the network, which by then is likely to work.
 */
@SessionScope
internal class PendingMediaUploadRegistry @Inject constructor() {

    private data class Pending(val localFile: File, val ownedFiles: List<File>, val eventIds: Set<String>)

    private val pending = ConcurrentHashMap<String, Pending>()

    /** [ownedFiles] are dropped along with [localFile] if the send is cancelled. */
    fun markPending(contentUri: String, localFile: File, ownedFiles: List<File>, eventIds: Set<String>) {
        pending[contentUri] = Pending(localFile, ownedFiles, eventIds)
    }

    fun clear(contentUri: String) {
        pending.remove(contentUri)
    }

    fun getLocalFile(contentUri: String): File? {
        return pending[contentUri]?.localFile?.takeIf { it.exists() }
    }

    /**
     * Cancelling a send tears down the queued byte upload before it can clean up after itself, so the
     * bytes it was holding have to be dropped here instead.
     */
    fun discardForEvent(eventId: String) {
        pending.entries
                .filter { eventId in it.value.eventIds }
                .forEach { (contentUri, entry) ->
                    pending.remove(contentUri)
                    (entry.ownedFiles + entry.localFile).forEach { file -> tryOrNull { file.delete() } }
                }
    }
}
