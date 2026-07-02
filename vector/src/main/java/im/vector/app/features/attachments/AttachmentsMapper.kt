/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments

import im.vector.lib.multipicker.entity.MultiPickerAudioType
import im.vector.lib.multipicker.entity.MultiPickerBaseMediaType
import im.vector.lib.multipicker.entity.MultiPickerBaseType
import im.vector.lib.multipicker.entity.MultiPickerContactType
import im.vector.lib.multipicker.entity.MultiPickerFileType
import im.vector.lib.multipicker.entity.MultiPickerImageType
import im.vector.lib.multipicker.entity.MultiPickerVideoType
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.util.MimeTypes.isMimeTypeAudio
import org.matrix.android.sdk.api.util.MimeTypes.isMimeTypeImage
import org.matrix.android.sdk.api.util.MimeTypes.isMimeTypeVideo
import timber.log.Timber

/**
 * Replace the file name with a random id, keeping the extension. Media (image/video/audio) never carries a
 * compound extension, so only the final one is kept (foo.mp4 -> uuid.mp4); other files can (e.g. .tar.gz),
 * where dropping to just the last part (.gz) would break extraction, so the .tar.<x> pair is preserved.
 */
fun ContentAttachmentData.withRandomizedFilename(): ContentAttachmentData {
    val originalName = name ?: return this
    return copy(name = java.util.UUID.randomUUID().toString() + extensionSuffixOf(originalName, type))
}

private fun extensionSuffixOf(fileName: String, type: ContentAttachmentData.Type): String {
    val parts = fileName.split('.')
    val last = parts.lastOrNull().orEmpty()
    if (parts.size < 2 || last.isEmpty()) return ""
    val isMedia = type == ContentAttachmentData.Type.IMAGE ||
            type == ContentAttachmentData.Type.VIDEO ||
            type == ContentAttachmentData.Type.AUDIO ||
            type == ContentAttachmentData.Type.VOICE_MESSAGE
    return if (!isMedia && parts.size >= 3 && parts[parts.size - 2].equals("tar", ignoreCase = true)) {
        ".tar.$last"
    } else {
        ".$last"
    }
}

fun MultiPickerContactType.toContactAttachment(): ContactAttachment {
    return ContactAttachment(
            displayName = displayName,
            photoUri = photoUri,
            emails = emailList.toList(),
            phones = phoneNumberList.toList()
    )
}

fun MultiPickerFileType.toContentAttachmentData(): ContentAttachmentData {
    if (mimeType == null) Timber.w("No mimeType")
    return ContentAttachmentData(
            mimeType = mimeType,
            type = mapType(),
            size = size,
            name = displayName,
            queryUri = contentUri
    )
}

fun MultiPickerAudioType.toContentAttachmentData(isVoiceMessage: Boolean): ContentAttachmentData {
    if (mimeType == null) Timber.w("No mimeType")
    return ContentAttachmentData(
            mimeType = mimeType,
            type = if (isVoiceMessage) ContentAttachmentData.Type.VOICE_MESSAGE else mapType(),
            size = size,
            name = displayName,
            duration = duration,
            queryUri = contentUri,
            waveform = waveform
    )
}

private fun MultiPickerBaseType.mapType(): ContentAttachmentData.Type {
    return when {
        mimeType?.isMimeTypeImage() == true -> ContentAttachmentData.Type.IMAGE
        mimeType?.isMimeTypeVideo() == true -> ContentAttachmentData.Type.VIDEO
        mimeType?.isMimeTypeAudio() == true -> ContentAttachmentData.Type.AUDIO
        else -> ContentAttachmentData.Type.FILE
    }
}

fun MultiPickerBaseType.toContentAttachmentData(): ContentAttachmentData {
    return when (this) {
        is MultiPickerImageType -> toContentAttachmentData()
        is MultiPickerVideoType -> toContentAttachmentData()
        is MultiPickerAudioType -> toContentAttachmentData(isVoiceMessage = false)
        is MultiPickerFileType -> toContentAttachmentData()
        else -> throw IllegalStateException("Unknown file type")
    }
}

fun MultiPickerBaseMediaType.toContentAttachmentData(): ContentAttachmentData {
    return when (this) {
        is MultiPickerImageType -> toContentAttachmentData()
        is MultiPickerVideoType -> toContentAttachmentData()
        else -> throw IllegalStateException("Unknown media type")
    }
}

fun MultiPickerImageType.toContentAttachmentData(): ContentAttachmentData {
    if (mimeType == null) Timber.w("No mimeType")
    return ContentAttachmentData(
            mimeType = mimeType,
            type = mapType(),
            name = displayName,
            size = size,
            height = height.toLong(),
            width = width.toLong(),
            exifOrientation = orientation,
            queryUri = contentUri
    )
}

fun MultiPickerVideoType.toContentAttachmentData(): ContentAttachmentData {
    if (mimeType == null) Timber.w("No mimeType")
    return ContentAttachmentData(
            mimeType = mimeType,
            type = ContentAttachmentData.Type.VIDEO,
            size = size,
            height = height.toLong(),
            width = width.toLong(),
            duration = duration,
            name = displayName,
            queryUri = contentUri
    )
}
