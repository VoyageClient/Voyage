/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.files

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import timber.log.Timber
import java.io.File

private const val MULTIPICKER_FILE_PROVIDER_SUFFIX = ".multipicker.fileprovider"

/**
 * A uri another app can be handed. Ours are plain file:// paths in private storage, which the
 * platform refuses to let out of the process (FileUriExposedException), so they go out through our
 * file provider instead. Anything else is passed through untouched.
 */
fun Uri.asExternallyShareable(context: Context): Uri {
    if (scheme != ContentResolver.SCHEME_FILE) return this
    val path = path ?: return this
    return runCatching {
        FileProvider.getUriForFile(context, context.packageName + MULTIPICKER_FILE_PROVIDER_SUFFIX, File(path))
    }.getOrElse {
        Timber.w(it, "Cannot publish $this through the file provider")
        this
    }
}

/**
 * Whether this url points at bytes on the device — a local echo's own source — rather than at
 * homeserver content. Both schemes occur: what the picker hands us, and the private copy
 * [im.vector.app.features.attachments.SendMediaMaterializer] takes of it.
 */
fun String?.isLocalMediaUri(): Boolean {
    this ?: return false
    return startsWith("content://") || startsWith("file://")
}
