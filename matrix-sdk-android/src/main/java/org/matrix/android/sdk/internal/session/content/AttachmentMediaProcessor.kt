/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import org.matrix.android.sdk.api.listeners.ProgressListener
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import java.io.File
import java.io.InputStream

internal data class CompressedImage(val file: File, val mimeType: String?)

/**
 * Platform seam for everything the upload pipeline does to attachment bytes before they go out:
 * reading the source, transcoding, metadata scrubbing and the measurements that end up in the event.
 * Android answers with its media stack; a JVM answers with ImageIO and passes video through.
 */
internal interface AttachmentMediaProcessor {
    fun openSource(attachment: ContentAttachmentData): InputStream

    /** The send is over (either way): give back any access the source was granted for it. */
    fun releaseSource(attachment: ContentAttachmentData)

    /** Voice messages are recorded into a scratch location the SDK owns once sent. */
    fun deleteSource(attachment: ContentAttachmentData)

    suspend fun compressImage(file: File, width: Int, height: Int, quality: Int, exactSize: Boolean): CompressedImage

    suspend fun reEncodeImageStrippingMetadata(file: File): CompressedImage

    suspend fun compressVideo(
            attachment: ContentAttachmentData,
            targetWidth: Int?,
            targetHeight: Int?,
            targetBitrate: Int?,
            progressListener: ProgressListener?,
    ): VideoCompressionResult

    /** Re-muxes straight from the source without scrubbing; null when the platform can't. */
    suspend fun stripVideoMetadata(attachment: ContentAttachmentData, progressListener: ProgressListener?): File?

    suspend fun stripVideoMetadata(file: File): File?

    suspend fun stripAudioMetadataInPlace(file: File)

    fun readImageSize(file: File): Pair<Int, Int>?

    /** null when the file isn't JPEG XL or the platform can't tell. */
    fun isAnimatedJxl(file: File): Boolean?

    /** Orientation-corrected (width, height) of a transcoded video; nulls when unreadable. */
    fun readVideoSize(file: File): Pair<Int?, Int?>

    fun extractWaveform(file: File): List<Int>

    fun encodeBlurHash(file: File): String?
}
