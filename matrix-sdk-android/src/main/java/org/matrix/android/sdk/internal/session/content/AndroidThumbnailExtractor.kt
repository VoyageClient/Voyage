/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.vanniktech.blurhash.BlurHash
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.content.queryUriAndroid
import org.matrix.android.sdk.api.util.MimeTypes
import org.matrix.android.sdk.internal.session.content.ThumbnailExtractor.ThumbnailData
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

internal class AndroidThumbnailExtractor @Inject constructor(
        private val context: Context
) : ThumbnailExtractor {

    override fun extractThumbnail(attachment: ContentAttachmentData, withBlurHash: Boolean): ThumbnailData? {
        return if (attachment.type == ContentAttachmentData.Type.VIDEO) {
            extractVideoThumbnail(withBlurHash) { setDataSource(context, attachment.queryUriAndroid) }
        } else {
            null
        }
    }

    override fun extractVideoThumbnailFromFile(file: File): ThumbnailData? =
            extractVideoThumbnail(withBlurHash = true) { setDataSource(file.absolutePath) }

    private fun extractVideoThumbnail(withBlurHash: Boolean, setSource: MediaMetadataRetriever.() -> Unit): ThumbnailData? {
        var thumbnailData: ThumbnailData? = null
        val mediaMetadataRetriever = MediaMetadataRetriever()
        try {
            mediaMetadataRetriever.setSource()
            mediaMetadataRetriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { thumbnail ->
                val outputStream = ByteArrayOutputStream()
                thumbnail.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                val blurHash = if (withBlurHash) encodeBlurHash(thumbnail) else null
                thumbnailData = ThumbnailData(
                        width = thumbnail.width,
                        height = thumbnail.height,
                        size = outputStream.size().toLong(),
                        bytes = outputStream.toByteArray(),
                        mimeType = MimeTypes.Jpeg,
                        blurHash = blurHash,
                )
                thumbnail.recycle()
                outputStream.reset()
            } ?: run {
                Timber.e("Cannot extract video thumbnail")
            }
        } catch (e: Exception) {
            Timber.e(e, "Cannot extract video thumbnail")
        } finally {
            mediaMetadataRetriever.release()
        }
        return thumbnailData
    }

    // BlurHash.encode is O(width * height) with a trig term per pixel, so encoding a full-resolution
    // video frame can take tens of seconds on slow devices. The hash is a 4x3-ish blur, so downscale
    // first — same as the image path (UploadContentWorker.BLURHASH_DECODE_MAX).
    private fun encodeBlurHash(frame: Bitmap): String? = tryOrNull {
        val largest = maxOf(frame.width, frame.height)
        val source = if (largest <= BLURHASH_MAX_DIMENSION) {
            frame
        } else {
            val scale = BLURHASH_MAX_DIMENSION.toFloat() / largest
            val width = (frame.width * scale).toInt().coerceAtLeast(1)
            val height = (frame.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(frame, width, height, true)
        }
        try {
            val (xc, yc) = blurHashComponents(source.width, source.height)
            BlurHash.encode(source, xc, yc)
        } finally {
            if (source !== frame) source.recycle()
        }
    }

    companion object {
        private const val BLURHASH_MAX_DIMENSION = 128
    }
}
