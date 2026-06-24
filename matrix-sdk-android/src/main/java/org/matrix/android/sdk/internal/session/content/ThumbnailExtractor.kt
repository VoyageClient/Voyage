/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.content

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.vanniktech.blurhash.BlurHash
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.util.MimeTypes
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

internal class ThumbnailExtractor @Inject constructor(
        private val context: Context
) {

    class ThumbnailData(
            val width: Int,
            val height: Int,
            val size: Long,
            val bytes: ByteArray,
            val mimeType: String,
            val blurHash: String?,
    )

    /**
     * @param withBlurHash when false the (expensive) blurhash is not computed. The local-echo path
     * only needs the thumbnail dimensions, so skipping it keeps the message from being held back for
     * seconds while the frame is encoded — the blurhash is recomputed by the upload worker anyway.
     */
    fun extractThumbnail(attachment: ContentAttachmentData, withBlurHash: Boolean = true): ThumbnailData? {
        return if (attachment.type == ContentAttachmentData.Type.VIDEO) {
            extractVideoThumbnail(withBlurHash) { setDataSource(context, attachment.queryUri) }
        } else {
            null
        }
    }

    fun extractVideoThumbnailFromFile(file: File): ThumbnailData? =
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

internal fun blurHashComponents(width: Int, height: Int): Pair<Int, Int> = when {
    width > height -> 4 to 3
    height > width -> 3 to 4
    else -> 4 to 4
}
