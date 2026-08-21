/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.desktop.platform

import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.listeners.ProgressListener
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.util.MimeTypes
import org.matrix.android.sdk.desktop.di.toLocalFile
import org.matrix.android.sdk.internal.session.content.AttachmentMediaProcessor
import org.matrix.android.sdk.internal.session.content.CompressedImage
import org.matrix.android.sdk.internal.session.content.Mp4MetadataScrubber
import org.matrix.android.sdk.internal.session.content.VideoCompressionResult
import org.matrix.android.sdk.internal.session.content.blurHashComponents
import org.matrix.android.sdk.internal.util.TemporaryFileCreator
import timber.log.Timber
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.inject.Inject

/**
 * ImageIO does the image work; there is no demuxer or codec on a plain JVM, so video passes through
 * untouched (MP4 metadata atoms are still scrubbed, which needs no decoding).
 */
internal class DesktopAttachmentMediaProcessor @Inject constructor(
        private val temporaryFileCreator: TemporaryFileCreator,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
) : AttachmentMediaProcessor {

    override fun openSource(attachment: ContentAttachmentData): InputStream = attachment.queryUri.toLocalFile().inputStream()

    override fun releaseSource(attachment: ContentAttachmentData) = Unit

    override fun deleteSource(attachment: ContentAttachmentData) {
        attachment.queryUri.toLocalFile().delete()
    }

    override suspend fun compressImage(file: File, width: Int, height: Int, quality: Int, exactSize: Boolean): CompressedImage =
            withContext(coroutineDispatchers.io) {
                val image = readImage(file) ?: return@withContext CompressedImage(file, mimeType = null)
                val (targetW, targetH) = if (exactSize) {
                    width to height
                } else {
                    val scale = minOf(width.toDouble() / image.width, height.toDouble() / image.height, 1.0)
                    (image.width * scale).toInt().coerceAtLeast(1) to (image.height * scale).toInt().coerceAtLeast(1)
                }
                encode(scale(image, targetW, targetH), quality)
            }

    override suspend fun reEncodeImageStrippingMetadata(file: File): CompressedImage = withContext(coroutineDispatchers.io) {
        val image = readImage(file) ?: return@withContext CompressedImage(file, mimeType = null)
        val scale = minOf(REENCODE_MAX_DIMENSION.toDouble() / maxOf(image.width, image.height), 1.0)
        encode(scale(image, (image.width * scale).toInt().coerceAtLeast(1), (image.height * scale).toInt().coerceAtLeast(1)), 90)
    }

    override suspend fun compressVideo(
            attachment: ContentAttachmentData,
            targetWidth: Int?,
            targetHeight: Int?,
            targetBitrate: Int?,
            progressListener: ProgressListener?,
    ): VideoCompressionResult = VideoCompressionResult.CompressionNotNeeded

    override suspend fun stripVideoMetadata(attachment: ContentAttachmentData, progressListener: ProgressListener?): File? = null

    override suspend fun stripVideoMetadata(file: File): File? = withContext(coroutineDispatchers.io) {
        val copy = temporaryFileCreator.create()
        file.copyTo(copy, overwrite = true)
        if (Mp4MetadataScrubber.scrub(copy) == Mp4MetadataScrubber.Outcome.UNSUPPORTED) {
            copy.delete()
            null
        } else {
            copy
        }
    }

    override suspend fun stripAudioMetadataInPlace(file: File) {
        withContext(coroutineDispatchers.io) { Mp4MetadataScrubber.scrub(file) }
    }

    override fun readImageSize(file: File): Pair<Int, Int>? = runCatching {
        ImageIO.createImageInputStream(file).use { stream ->
            val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: return@use null
            try {
                reader.input = stream
                reader.getWidth(0) to reader.getHeight(0)
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull()

    override fun isAnimatedJxl(file: File): Boolean? = null

    override fun readVideoSize(file: File): Pair<Int?, Int?> = null to null

    override fun extractWaveform(file: File): List<Int> = emptyList()

    override fun encodeBlurHash(file: File): String? {
        return try {
            val image = readImage(file) ?: return null
            val scale = minOf(BLURHASH_DECODE_MAX.toDouble() / maxOf(image.width, image.height), 1.0)
            val small = scale(image, (image.width * scale).toInt().coerceAtLeast(1), (image.height * scale).toInt().coerceAtLeast(1))
            val (xc, yc) = blurHashComponents(small.width, small.height)
            BlurHashEncoder.encode(small, xc, yc)
        } catch (t: Throwable) {
            Timber.w(t, "Failed to encode blurhash")
            null
        }
    }

    private fun readImage(file: File): BufferedImage? = runCatching { ImageIO.read(file) }.getOrNull()

    private fun scale(image: BufferedImage, width: Int, height: Int): BufferedImage {
        if (width == image.width && height == image.height) return image
        val type = if (image.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        return BufferedImage(width, height, type).also { target ->
            target.createGraphics().run {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(image, 0, 0, width, height, null)
                dispose()
            }
        }
    }

    // ImageIO writes no metadata of its own, so the output is clean whatever the input carried.
    private suspend fun encode(image: BufferedImage, quality: Int): CompressedImage {
        val out = temporaryFileCreator.create()
        if (image.colorModel.hasAlpha()) {
            ImageIO.write(image, "png", out)
            return CompressedImage(out, MimeTypes.Png)
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            val params = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality.coerceIn(1, 100) / 100f
            }
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                writer.write(null, IIOImage(image, null, null), params)
            }
        } finally {
            writer.dispose()
        }
        return CompressedImage(out, MimeTypes.Jpeg)
    }

    companion object {
        private const val REENCODE_MAX_DIMENSION = 4096
        private const val BLURHASH_DECODE_MAX = 128
    }
}
