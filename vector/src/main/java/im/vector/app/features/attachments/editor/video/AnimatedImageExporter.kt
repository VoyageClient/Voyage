/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import androidx.core.content.FileProvider
import im.vector.app.core.glide.MediaCache
import im.vector.lib.animatedimage.AnimatedFrame
import im.vector.lib.animatedimage.AnimatedImageFormat
import im.vector.lib.animatedimage.AnimatedImageReader
import im.vector.lib.animatedimage.AnimatedWebpEncoder
import im.vector.lib.mediatranscode.VideoEditProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Applies a [VideoEditorEdits] to an animated image and writes an animated WebP.
 *
 * Always WebP, whatever went in: it is the one animated format the fork can both write and show
 * everywhere, and a GIF re-encoded as GIF would only lose colours.
 */
object AnimatedImageExporter {

    private const val FILE_PROVIDER_SUFFIX = ".multipicker.fileprovider"
    private const val OUTPUT_MIME_TYPE = "image/webp"
    private const val DEFAULT_QUALITY = 80

    suspend fun export(
            context: Context,
            source: File,
            format: AnimatedImageFormat?,
            displayName: String?,
            edits: VideoEditorEdits,
            targetSize: Pair<Int, Int>?,
            progressListener: VideoEditProgressListener?,
            censors: List<RectF> = emptyList(),
    ): VideoEditorExporter.Result = withContext(Dispatchers.Default) {
        progressListener?.onProgress(0)
        if (!edits.reversed) return@withContext exportStreaming(context, source, format, displayName, edits, targetSize, progressListener, censors)
        val decoded = AnimatedImageReader.readFrames(source, format) ?: throw AnimatedImageException()
        val destination = createOutputFile(context, displayName)
        val output = ArrayList<AnimatedFrame>()
        try {
            val kept = trim(decoded, edits).reversed()
            if (kept.isEmpty()) throw AnimatedImageException()
            // Bitmap.compress(WEBP) gained alpha in 4.2.1; below that every transparent pixel comes
            // back black, so an export that would look wrong is refused rather than written.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 && kept.anyTransparent()) {
                throw TransparencyUnsupportedException()
            }
            val geometry = Geometry.of(decoded[0].bitmap, edits, targetSize, censors)
            kept.forEachIndexed { index, frame ->
                coroutineContext.ensureActive()
                output.add(AnimatedFrame(geometry.apply(frame.bitmap), scaleDuration(frame.durationMs, edits)))
                progressListener?.onProgress(index * 100 / kept.size)
            }
            val written = runCatching {
                destination.outputStream().use { AnimatedWebpEncoder.encode(output, DEFAULT_QUALITY, it, parallel = true) }
            }.getOrDefault(false)
            if (!written) throw AnimatedImageException()
            VideoEditorExporter.Result(
                    uri = FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, destination),
                    width = geometry.width,
                    height = geometry.height,
                    size = destination.length(),
                    mimeType = OUTPUT_MIME_TYPE,
                    durationMs = output.sumOf { it.durationMs }.toLong(),
                    audioDropped = false
            )
        } catch (throwable: Throwable) {
            destination.parentFile?.deleteRecursively()
            throw throwable
        } finally {
            output.forEach { it.bitmap.recycle() }
            decoded.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
            progressListener?.onProgress(100)
        }
    }

    private suspend fun exportStreaming(
            context: Context,
            source: File,
            format: AnimatedImageFormat?,
            displayName: String?,
            edits: VideoEditorEdits,
            targetSize: Pair<Int, Int>?,
            progressListener: VideoEditProgressListener?,
            censors: List<RectF>,
    ): VideoEditorExporter.Result {
        val destination = createOutputFile(context, displayName)
        var writer: AnimatedWebpEncoder.StreamWriter? = null
        var geometry: Geometry? = null
        val pending = ArrayList<AnimatedFrame>(4)
        var sourcePositionUs = 0L
        var durationMs = 0L
        var lastProgress = -1
        val jobContext = coroutineContext
        fun flush() {
            jobContext.ensureActive()
            try {
                if (writer?.append(pending) != true) throw AnimatedImageException()
            } finally {
                pending.forEach { it.bitmap.recycle() }
                pending.clear()
            }
        }
        try {
            val decoded = AnimatedImageReader.visitFrames(source, format) { frame, index, count ->
                jobContext.ensureActive()
                val endUs = sourcePositionUs + frame.durationMs * 1000L
                val keep = edits.durationUs <= 0 || (endUs > edits.startUs && sourcePositionUs < edits.endUs)
                sourcePositionUs = endUs
                if (keep) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 && listOf(frame).anyTransparent()) {
                        throw TransparencyUnsupportedException()
                    }
                    val current = geometry ?: Geometry.of(frame.bitmap, edits, targetSize, censors).also { geometry = it }
                    if (writer == null) writer = AnimatedWebpEncoder.StreamWriter(destination, current.width, current.height, DEFAULT_QUALITY)
                    val delay = scaleDuration(frame.durationMs, edits)
                    pending.add(AnimatedFrame(current.apply(frame.bitmap), delay))
                    durationMs += delay
                    val bytesPerFrame = current.width.toLong() * current.height * 4
                    val batchSize = (Runtime.getRuntime().maxMemory() / 16 / bytesPerFrame.coerceAtLeast(1)).coerceIn(1, 4).toInt()
                    if (pending.size >= batchSize) flush()
                }
                val progress = (index + 1) * 99 / count.coerceAtLeast(1)
                if (progress != lastProgress) {
                    progressListener?.onProgress(progress)
                    lastProgress = progress
                }
            }
            if (!decoded || writer == null) throw AnimatedImageException()
            if (pending.isNotEmpty()) flush()
            jobContext.ensureActive()
            if (writer?.finish() != true) throw AnimatedImageException()
            val finalGeometry = geometry ?: throw AnimatedImageException()
            progressListener?.onProgress(100)
            return VideoEditorExporter.Result(
                    uri = FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, destination),
                    width = finalGeometry.width, height = finalGeometry.height, size = destination.length(),
                    mimeType = OUTPUT_MIME_TYPE, durationMs = durationMs, audioDropped = false
            )
        } catch (error: Throwable) {
            destination.parentFile?.deleteRecursively()
            throw error
        } finally {
            writer?.close()
            pending.forEach { it.bitmap.recycle() }
        }
    }

    /** The trim is in source time, which for an animation is the running total of frame delays. */
    private fun trim(frames: List<AnimatedFrame>, edits: VideoEditorEdits): List<AnimatedFrame> {
        if (edits.durationUs <= 0 || (edits.startUs <= 0 && edits.endUs >= edits.durationUs)) return frames
        val kept = mutableListOf<AnimatedFrame>()
        var startUs = 0L
        frames.forEach { frame ->
            val endUs = startUs + frame.durationMs * 1000L
            if (endUs > edits.startUs && startUs < edits.endUs) kept.add(frame)
            startUs = endUs
        }
        return kept
    }

    private fun scaleDuration(durationMs: Int, edits: VideoEditorEdits): Int =
            (durationMs / edits.speed.speed).toInt().coerceAtLeast(MIN_FRAME_DELAY_MS)

    private const val MIN_FRAME_DELAY_MS = 10

    private class Geometry(
            val width: Int,
            val height: Int,
            private val rotationDegrees: Int,
            private val crop: RectF?,
            private val censors: List<RectF>,
    ) {

        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        fun apply(bitmap: Bitmap): Bitmap {
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val rotated = if (rotationDegrees == 0) bitmap else bitmap.rotated(rotationDegrees)
            val sourceRect = crop?.let {
                Rect(
                        (it.left * rotated.width).toInt(),
                        (it.top * rotated.height).toInt(),
                        (it.right * rotated.width).toInt(),
                        (it.bottom * rotated.height).toInt()
                )
            }
            canvas.drawBitmap(rotated, sourceRect, Rect(0, 0, width, height), paint)
            val region = crop ?: RectF(0f, 0f, 1f, 1f)
            val censorPaint = Paint().apply { color = Color.BLACK }
            censors.forEach {
                canvas.drawRect(
                        (it.left - region.left) / region.width() * width,
                        (it.top - region.top) / region.height() * height,
                        (it.right - region.left) / region.width() * width,
                        (it.bottom - region.top) / region.height() * height,
                        censorPaint
                )
            }
            if (rotated !== bitmap) rotated.recycle()
            return output
        }

        private fun Bitmap.rotated(degrees: Int): Bitmap {
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            return Bitmap.createBitmap(this, 0, 0, this.width, this.height, matrix, true)
        }

        companion object {
            fun of(first: Bitmap, edits: VideoEditorEdits, targetSize: Pair<Int, Int>?, censors: List<RectF>): Geometry {
                val rotation = ((edits.rotationDegrees % 360) + 360) % 360
                val swapped = rotation % 180 == 90
                val displayWidth = if (swapped) first.height else first.width
                val displayHeight = if (swapped) first.width else first.height
                val croppedWidth = ((edits.crop?.width() ?: 1f) * displayWidth).toInt().coerceAtLeast(1)
                val croppedHeight = ((edits.crop?.height() ?: 1f) * displayHeight).toInt().coerceAtLeast(1)
                // No 16-pixel alignment here: nothing is being handed to a video encoder, so the
                // crop can be honoured to the pixel.
                return Geometry(
                        width = targetSize?.first ?: croppedWidth,
                        height = targetSize?.second ?: croppedHeight,
                        rotationDegrees = rotation,
                        crop = edits.crop,
                        censors = censors
                )
            }
        }
    }

    private fun createOutputFile(context: Context, displayName: String?): File {
        val directory = File(MediaCache.editedMediaDirectory(context), UUID.randomUUID().toString()).also { it.mkdirs() }
        val baseName = displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "image"
        return File(directory, "$baseName.webp")
    }

    /** Sampled on a grid: an exact answer would mean reading every pixel of every frame. */
    private fun List<AnimatedFrame>.anyTransparent(): Boolean = any { frame ->
        val bitmap = frame.bitmap
        if (!bitmap.hasAlpha()) return@any false
        val stride = maxOf(1, minOf(bitmap.width, bitmap.height) / TRANSPARENCY_SAMPLES)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                if (bitmap.getPixel(x, y) ushr 24 != 0xFF) return@any true
                x += stride
            }
            y += stride
        }
        false
    }

    private const val TRANSPARENCY_SAMPLES = 32

    class AnimatedImageException : Exception("Could not export the animated image")

    class TransparencyUnsupportedException : Exception("This device cannot write a transparent animated WebP")
}
