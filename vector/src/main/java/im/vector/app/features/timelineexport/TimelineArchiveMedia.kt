/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.app.Activity
import android.content.Context
import android.net.Uri
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.media.VectorAttachmentViewerActivity
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import java.io.File

internal fun archiveAvatarFile(context: Context, url: String?): File? {
    val uri = url?.takeIf { it.startsWith("file://") }?.let(Uri::parse) ?: return null
    return runCatching {
        val file = File(uri.path ?: return null).canonicalFile
        file.takeIf { it.path.startsWith(File(context.filesDir, "timeline_archives").canonicalPath + File.separator) && it.isFile }
    }.getOrNull()
}

internal fun openArchiveAvatar(activity: Activity, url: String?, name: String) {
    val file = archiveAvatarFile(activity, url) ?: return
    val data = ImageContentRenderer.Data(file.name, name, "image/*", null, null, null, 4096, null, 4096, preservedFile = file)
    activity.startActivity(VectorAttachmentViewerActivity.newIntent(activity, data, null, data.eventId, listOf(data), null,
            standalonePreview = true, hideShowInChat = true, hideForward = true))
}

internal fun ExportEvent.withAttachment(path: String?): ExportEvent {
    val item = extraAttachments.firstOrNull { it.path == path && path != null } ?: return this
    return copy(attachment = item.path, attachmentMime = item.mime, attachmentName = item.name)
}

internal fun MessageContent.archivePath(): String? =
        (this as? MessageWithAttachmentContent)?.let { it.encryptedFileInfo?.url ?: it.url }
