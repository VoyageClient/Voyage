/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.files

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import timber.log.Timber
import java.io.File
import java.io.InputStream
import javax.inject.Inject

class LocalFilesHelper @Inject constructor(private val context: Context) {
    /**
     * Whether this is media we hold ourselves — a local echo's source file — rather than something to
     * fetch from the homeserver.
     *
     * Deliberately not DocumentFile.fromSingleUri, which this used to go through: it returns null
     * below API 19, and above it probes with a DocumentsContract projection that providers other than
     * SAF are free to reject. Either way our own not-yet-uploaded media reads as remote, and the
     * timeline renders it as a failed download until the upload lands.
     */
    fun isLocalFile(fileUri: String?): Boolean {
        val uri = fileUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
        return when (uri.scheme) {
            ContentResolver.SCHEME_FILE -> uri.path?.let { File(it).canRead() } == true
            ContentResolver.SCHEME_CONTENT -> canResolve(uri)
            else -> false
        }
    }

    fun openInputStream(fileUri: String?): InputStream? {
        return fileUri
                ?.takeIf { isLocalFile(it) }
                ?.let { Uri.parse(it) }
                ?.let { openStream(it) }
    }

    private fun canResolve(uri: Uri): Boolean {
        if (context.contentResolver.getType(uri) != null) return true
        // A provider may decline to type its content while still serving the bytes.
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.also { it.close() } != null
        } catch (throwable: Throwable) {
            Timber.d(throwable, "Not a readable local uri: $uri")
            false
        }
    }

    private fun openStream(uri: Uri): InputStream? {
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (throwable: Throwable) {
            Timber.w(throwable, "Failed to open local uri: $uri")
            null
        }
    }
}
