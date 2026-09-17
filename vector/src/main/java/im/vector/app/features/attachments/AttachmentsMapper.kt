/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments

import android.content.Context
import android.content.res.Resources
import im.vector.app.core.ui.model.Size
import im.vector.app.features.media.MIN_MEDIA_SIDE_DP
import im.vector.app.features.media.atLeastMinimumMediaSize
import im.vector.lib.multipicker.entity.MultiPickerAudioType
import im.vector.lib.multipicker.entity.MultiPickerBaseMediaType
import im.vector.lib.multipicker.entity.MultiPickerBaseType
import im.vector.lib.multipicker.entity.MultiPickerContactType
import im.vector.lib.multipicker.entity.MultiPickerFileType
import im.vector.lib.multipicker.entity.MultiPickerImageType
import im.vector.lib.multipicker.entity.MultiPickerVideoType
import im.vector.lib.multipicker.utils.ImageUtils
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.content.queryUriAndroid
import org.matrix.android.sdk.api.util.MimeTypes
import org.matrix.android.sdk.api.util.MimeTypes.isMimeTypeAudio
import org.matrix.android.sdk.api.util.MimeTypes.isMimeTypeImage
import org.matrix.android.sdk.api.util.MimeTypes.isMimeTypeVideo
import timber.log.Timber
import kotlin.math.roundToInt

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
            queryUri = contentUri.toString()
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
            queryUri = contentUri.toString(),
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
    val sent = sentSize()
    return ContentAttachmentData(
            mimeType = mimeType,
            type = mapType(),
            name = displayName,
            size = size,
            height = sent.height.toLong(),
            width = sent.width.toLong(),
            exifOrientation = orientation,
            queryUri = contentUri.toString()
    )
}

/** Publish SVG dimensions at the same minimum size used for display. */
private fun MultiPickerImageType.sentSize(): Size = svgSentSize(mimeType, width, height)

private fun svgSentSize(mimeType: String?, width: Int, height: Int): Size {
    val declared = Size(width, height)
    if (mimeType != MimeTypes.Svg) return declared
    val density = Resources.getSystem().displayMetrics.density
    val floorPx = (MIN_MEDIA_SIDE_DP * density).roundToInt()
    return declared.atLeastMinimumMediaSize(floorPx, Int.MAX_VALUE, Int.MAX_VALUE)
}

/** File pickers may omit SVG dimensions; measure them before publishing the attachment. */
fun ContentAttachmentData.withMeasuredSvgSize(context: Context): ContentAttachmentData {
    if (getSafeMimeType() != MimeTypes.Svg) return this
    if ((width ?: 0) > 0 && (height ?: 0) > 0) return this
    val measured = ImageUtils.getImageSize(context, queryUriAndroid) ?: return this
    val sent = svgSentSize(MimeTypes.Svg, measured.width, measured.height)
    return copy(width = sent.width.toLong(), height = sent.height.toLong())
}

fun MultiPickerVideoType.toContentAttachmentData(): ContentAttachmentData {
    if (mimeType == null) Timber.w("No mimeType")
    // orientation is the mp4 rotation hint (degrees) and width/height the unrotated track dims;
    // a sideways video must swap them so consumers of the attachment (e.g. the compressed-send
    // path's fallback attributes) see display dims.
    val sideways = orientation == 90 || orientation == 270
    return ContentAttachmentData(
            mimeType = mimeType,
            type = ContentAttachmentData.Type.VIDEO,
            size = size,
            height = (if (sideways) width else height).toLong(),
            width = (if (sideways) height else width).toLong(),
            duration = duration,
            name = displayName,
            queryUri = contentUri.toString()
    )
}
