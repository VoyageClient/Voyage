/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * The read grant on a picked/shared content URI dies with the process, so an upload worker
 * retrying after a restart gets a SecurityException. Copy foreign content into app-private
 * storage while the grant is still valid and send a file URI we own instead.
 */
class SendMediaMaterializer @Inject constructor(
        @ApplicationContext private val context: Context,
) {
    suspend fun materialize(attachments: List<ContentAttachmentData>): List<ContentAttachmentData> = withContext(Dispatchers.IO) {
        attachments.map { materialize(it) }
    }

    private fun materialize(attachment: ContentAttachmentData): ContentAttachmentData {
        val uri = Uri.parse(attachment.queryUri)
        if (uri.scheme != "content") return attachment
        // Our own providers (camera captures, multipicker copies) don't need a grant
        if (uri.authority?.startsWith(context.packageName) == true) return attachment
        return try {
            val dir = File(context.filesDir, "send_cache").apply { mkdirs() }
            sweepOldFiles(dir)
            val destFile = File(dir, UUID.randomUUID().toString())
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { input.copyTo(it) }
            } ?: return attachment
            attachment.copy(queryUri = Uri.fromFile(destFile).toString())
        } catch (failure: Throwable) {
            Timber.w(failure, "Failed to materialize attachment, sending with the original uri")
            attachment
        }
    }

    // Nothing deletes the copy once the upload chain is done with it (the worker only cleans its
    // own temp files), so bound the leak by age — well past any plausible retry horizon
    private fun sweepOldFiles(dir: File) {
        val cutoff = System.currentTimeMillis() - SWEEP_AGE_MS
        dir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }

    companion object {
        private const val SWEEP_AGE_MS = 7 * 24 * 3600 * 1000L
    }
}
