/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import im.vector.lib.mediatranscode.VideoEditExporter
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.util.MimeTypes
import org.matrix.android.sdk.api.util.MimeTypes.isMimeTypeImage
import org.matrix.android.sdk.api.util.MimeTypes.isMimeTypeVideo

/**
 * All images are editable, expect Gif.
 */
fun ContentAttachmentData.isImageEditable(): Boolean {
    return type == ContentAttachmentData.Type.IMAGE &&
            getSafeMimeType()?.isMimeTypeImage() == true &&
            getSafeMimeType() != MimeTypes.Gif
}

/** Video editing needs MediaMuxer, so it is unavailable below API 18 — see [VideoEditExporter]. */
fun ContentAttachmentData.isVideoEditable(): Boolean {
    return type == ContentAttachmentData.Type.VIDEO &&
            getSafeMimeType()?.isMimeTypeVideo() == true &&
            VideoEditExporter.isSupported()
}

fun ContentAttachmentData.isEditable(): Boolean = isImageEditable() || isVideoEditable()
