/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import com.vanniktech.blurhash.BlurHash
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.listeners.ProgressListener
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.content.queryUriAndroid
import org.matrix.android.sdk.api.util.JxlSupport
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject

internal class AndroidAttachmentMediaProcessor @Inject constructor(
        private val appContext: Context,
        private val imageCompressor: ImageCompressor,
        private val videoCompressor: VideoCompressor,
        private val videoMetadataStripper: VideoMetadataStripper,
) : AttachmentMediaProcessor {

    override fun openSource(attachment: ContentAttachmentData): InputStream =
            appContext.contentResolver.openInputStream(attachment.queryUriAndroid)
                    ?: throw IOException("Cannot openInputStream for file: ${attachment.queryUri}")

    override fun releaseSource(attachment: ContentAttachmentData) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.revokeUriPermission(appContext.packageName, attachment.queryUriAndroid, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            appContext.revokeUriPermission(attachment.queryUriAndroid, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    override fun deleteSource(attachment: ContentAttachmentData) {
        // Picked audio uses a MediaStore URI we don't own — let the delete fail quietly.
        tryOrNull("Failed to delete voice message source") {
            appContext.contentResolver.delete(attachment.queryUriAndroid, null, null)
        }
    }

    override suspend fun compressImage(file: File, width: Int, height: Int, quality: Int, exactSize: Boolean): CompressedImage =
            imageCompressor.compress(file, width, height, quality, exactSize)

    override suspend fun reEncodeImageStrippingMetadata(file: File): CompressedImage =
            imageCompressor.reEncodeStrippingMetadata(file)

    override suspend fun compressVideo(
            attachment: ContentAttachmentData,
            targetWidth: Int?,
            targetHeight: Int?,
            targetBitrate: Int?,
            progressListener: ProgressListener?,
    ): VideoCompressionResult = videoCompressor.compress(
            attachment.queryUriAndroid,
            attachment.size,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            targetBitrate = targetBitrate,
            progressListener = progressListener,
    )

    override suspend fun stripVideoMetadata(attachment: ContentAttachmentData, progressListener: ProgressListener?): File? =
            videoMetadataStripper.strip(attachment.queryUriAndroid, progressListener)

    override suspend fun stripVideoMetadata(file: File): File? = videoMetadataStripper.strip(file)

    override suspend fun stripAudioMetadataInPlace(file: File) {
        videoMetadataStripper.stripInPlace(file)
    }

    override fun readImageSize(file: File): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
        // BitmapFactory reports 0x0 for anything it can't decode.
        if (options.outWidth > 0 && options.outHeight > 0) return options.outWidth to options.outHeight
        return if (JxlSupport.isAvailable && sniffImageFormat(file) == ImageSourceFormat.JXL) JxlImageReader.readSize(file) else null
    }

    override fun isAnimatedJxl(file: File): Boolean? {
        if (!JxlSupport.isAvailable || sniffImageFormat(file) != ImageSourceFormat.JXL) return null
        return JxlImageReader.frameCount(file)?.let { it > 1 }
    }

    override fun readVideoSize(file: File): Pair<Int?, Int?> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt()
            val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            // METADATA_KEY_VIDEO_WIDTH/HEIGHT return raw track dims; swap when rotation is
            // sideways so layout uses display orientation.
            val swap = rotation == 90 || rotation == 270
            (if (swap) rawH else rawW) to (if (swap) rawW else rawH)
        } catch (e: Exception) {
            Timber.w(e, "Failed to read compressed video dimensions")
            null to null
        } finally {
            retriever.release()
        }
    }

    override fun extractWaveform(file: File): List<Int> = AudioWaveformExtractor.extract(file)

    override fun encodeBlurHash(file: File): String? {
        return try {
            val bitmap = decodeForBlurHash(file) ?: return null
            try {
                val (xc, yc) = blurHashComponents(bitmap.width, bitmap.height)
                BlurHash.encode(bitmap, xc, yc)
            } finally {
                bitmap.recycle()
            }
        } catch (t: Throwable) {
            Timber.w(t, "Failed to encode blurhash")
            null
        }
    }

    private fun decodeForBlurHash(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            val sample = generateSequence(1) { it * 2 }
                    .first { it * BLURHASH_DECODE_MAX >= maxOf(bounds.outWidth, bounds.outHeight) }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            return file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
        }
        return if (JxlSupport.isAvailable && sniffImageFormat(file) == ImageSourceFormat.JXL) {
            JxlImageReader.decode(file, BLURHASH_DECODE_MAX)
        } else {
            null
        }
    }

    companion object {
        private const val BLURHASH_DECODE_MAX = 128
    }
}
