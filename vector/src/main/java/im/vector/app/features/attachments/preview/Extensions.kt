/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import android.content.Context
import im.vector.lib.animatedimage.AnimatedImageFormat
import im.vector.lib.mediatranscode.AudioEditExporter
import im.vector.lib.mediatranscode.VideoEditExporter
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.content.queryUriAndroid
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

/** Audio editing writes an mp4 with MediaMuxer, so it is unavailable below API 18. */
fun ContentAttachmentData.isAudioEditable(): Boolean {
    return type == ContentAttachmentData.Type.AUDIO && AudioEditExporter.isSupported()
}

/**
 * Animated images go through the video editor rather than the still one, which would flatten them
 * to a single frame. Which form a `image/webp` or `image/png` really is only shows in the bytes, so
 * this reads the header rather than trusting the mime type.
 */
fun ContentAttachmentData.animatedImageFormat(context: Context): AnimatedImageFormat? {
    if (type != ContentAttachmentData.Type.IMAGE) return null
    return runCatching {
        context.contentResolver.openInputStream(queryUriAndroid)?.use { AnimatedImageFormat.detect(it) }
    }.getOrNull()
}

fun ContentAttachmentData.isEditable(animated: Boolean): Boolean =
        animated || isImageEditable() || isVideoEditable() || isAudioEditable()

/** Only what the SDK's compressors act on: an explicit quality or size would be ignored elsewhere. */
fun ContentAttachmentData.isCompressible(): Boolean =
        type == ContentAttachmentData.Type.IMAGE || type == ContentAttachmentData.Type.VIDEO
