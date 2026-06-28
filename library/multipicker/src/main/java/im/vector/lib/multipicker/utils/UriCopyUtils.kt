/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.multipicker.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.core.database.getStringOrNull
import im.vector.lib.core.utils.compat.use
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * Pre-21, the temporary read grant on an ACTION_GET_CONTENT result URI is tied to the receiving
 * activity and is gone by the time the background upload worker reads it, so the upload fails with a
 * SecurityException. Copy the content into our own FileProvider directory while the grant is still
 * valid and return a URI we own. Returns the original [uri] if the copy fails.
 */
internal fun Uri.copyToMultiPickerCache(context: Context): Uri {
    return try {
        val displayName = queryDisplayName(context) ?: "file_${UUID.randomUUID()}"
        val storageDir = File(context.filesDir, "media").also { it.mkdirs() }
        val destDir = File(storageDir, UUID.randomUUID().toString()).also { it.mkdirs() }
        val destFile = File(destDir, displayName)
        context.contentResolver.openInputStream(this)?.use { input ->
            destFile.outputStream().use { input.copyTo(it) }
        } ?: return this
        val authority = context.packageName + ".multipicker.fileprovider"
        FileProvider.getUriForFile(context, authority, destFile)
    } catch (failure: Throwable) {
        Timber.w(failure, "Failed to copy picked uri to cache, using original uri")
        this
    }
}

private fun Uri.queryDisplayName(context: Context): String? {
    return context.contentResolver.query(this, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getStringOrNull(index) else null
    }
}
