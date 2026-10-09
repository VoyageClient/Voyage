/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Rect
import android.net.Uri
import androidx.core.content.FileProvider
import im.vector.app.core.glide.JxlBitmaps
import im.vector.app.core.glide.MediaCache
import im.vector.app.core.glide.SvgDecoder
import im.vector.app.core.glide.SvgPictureCache
import im.vector.app.features.attachments.editor.FrameTransform
import im.vector.lib.multipicker.utils.ImageUtils
import org.matrix.android.sdk.api.util.JxlSupport
import org.matrix.android.sdk.api.util.MimeTypes
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Applies the edits collected by [ImageEditorView] to the full-resolution source and writes the
 * result to disk.
 */
object ImageEditorExporter {

    data class Result(val uri: Uri, val width: Int, val height: Int, val size: Long, val mimeType: String)

    private const val JPEG_QUALITY = 90
    internal const val FILE_PROVIDER_SUFFIX = ".multipicker.fileprovider"
    private const val BYTES_PER_PIXEL = 4
    private const val HARD_PIXEL_CAP = 16_000_000
    private const val DISPLAY_PIXEL_CAP = 2_073_600
    private const val DISPLAY_MAX_DIMENSION = 2048

    /**
     * Rotating and cropping each allocate a second bitmap, so a single decode may only claim a
     * fraction of the heap. On the low-memory devices this fork supports a full-resolution decode
     * is otherwise a guaranteed OOM.
     */
    private fun pixelBudget(): Int {
        val budgetBytes = Runtime.getRuntime().maxMemory() / 6
        return (budgetBytes / BYTES_PER_PIXEL).coerceAtMost(HARD_PIXEL_CAP.toLong()).toInt()
    }

    /** Decodes at display resolution with the source EXIF rotation already applied. */
    fun loadForDisplay(context: Context, source: Uri, targetSize: Pair<Int, Int>? = null): Bitmap? {
        val budget = min(pixelBudget(), DISPLAY_PIXEL_CAP)
        val picture = readSvgPicture(context, source)
        if (picture != null) {
            val aspect = (targetSize?.first ?: picture.width).toDouble() / (targetSize?.second ?: picture.height)
            val width = minOf(sqrt(budget * aspect), DISPLAY_MAX_DIMENSION.toDouble(), DISPLAY_MAX_DIMENSION * aspect)
                    .toInt().coerceAtLeast(1)
            val height = minOf(width / aspect, budget.toDouble() / width, DISPLAY_MAX_DIMENSION.toDouble()).toInt().coerceAtLeast(1)
            return renderSvg(picture, ImageEditorEdits(), width to height)
        }
        return loadOriented(context, source, budget)
    }

    internal fun readSvgPicture(context: Context, source: Uri): Picture? {
        return context.contentResolver.openInputStream(source)?.buffered()?.use { input ->
            input.mark(256)
            val header = ByteArray(256)
            val count = input.read(header)
            if (!SvgDecoder.looksLikeSvg(header, count)) return@use null
            input.reset()
            SvgPictureCache.decode(ByteBuffer.wrap(input.readBytes()))
        }
    }

    fun loadOriented(context: Context, source: Uri, maxPixels: Int): Bitmap? {
        val bitmap = decodeSampled(context, source, maxPixels) ?: return null
        return applyRotation(bitmap, ImageUtils.getOrientation(context, source))
    }

    fun export(context: Context, source: Uri, edits: ImageEditorEdits, displayName: String?, sourceMimeType: String?, targetSize: Pair<Int, Int>? = null): Result? {
        val picture = readSvgPicture(context, source)
        if (picture != null) {
            val bitmap = renderSvg(picture, edits, targetSize ?: (picture.width to picture.height))
            return exportBitmap(context, bitmap, ImageEditorEdits(), displayName, usePng = true)
        }
        val decoded = decodeSampled(context, source, pixelBudget()) ?: return null
        // The editor works in a space where EXIF rotation has already been applied, so replay it
        // here before the user's own rotation or the normalised rectangles will not line up.
        val oriented = applyRotation(decoded, ImageUtils.getOrientation(context, source))
        return exportBitmap(context, oriented, edits, displayName, usePng = sourceMimeType?.lowercase() == MimeTypes.Png)
    }

    /** Applies [edits] to [bitmap], which this takes ownership of and recycles, and writes the result. */
    fun exportBitmap(context: Context, bitmap: Bitmap, edits: ImageEditorEdits, displayName: String?, usePng: Boolean): Result {
        val working = render(bitmap, edits)
        val format = if (usePng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val extension = if (usePng) ".png" else ".jpg"
        var destination: File? = null
        try {
            val outputFile = createOutputFile(context, displayName, extension)
            destination = outputFile
            FileOutputStream(outputFile).use { output ->
                check(working.compress(format, JPEG_QUALITY, output)) { "Unable to encode edited image" }
            }
            return Result(
                    // Local echo and the upload worker stat the file via DocumentFile, which cannot read file:// URIs.
                    uri = FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, outputFile),
                    width = working.width,
                    height = working.height,
                    size = outputFile.length(),
                    mimeType = if (usePng) MimeTypes.Png else MimeTypes.Jpeg
            )
        } catch (error: Throwable) {
            destination?.delete()
            throw error
        } finally {
            working.recycle()
        }
    }

    private fun renderSvg(picture: Picture, edits: ImageEditorEdits, sourceSize: Pair<Int, Int>): Bitmap {
        val (sourceWidth, sourceHeight) = sourceSize
        val sideways = edits.userRotation % 180 != 0
        val frameWidth = if (sideways) sourceHeight else sourceWidth
        val frameHeight = if (sideways) sourceWidth else sourceHeight
        val cropWidth = (edits.crop.width() * frameWidth).coerceAtLeast(1f)
        val cropHeight = (edits.crop.height() * frameHeight).coerceAtLeast(1f)
        val width = cropWidth.roundToInt().coerceAtLeast(1)
        val height = cropHeight.roundToInt().coerceAtLeast(1)
        require(width > 0 && height > 0 && width.toLong() * height <= pixelBudget()) { "SVG rasterization exceeds the image memory budget" }
        val matrix = FrameTransform.sourceToFrame(sourceWidth, sourceHeight, edits.userRotation, edits.tiltDegrees).apply {
            postTranslate(-edits.crop.left * frameWidth, -edits.crop.top * frameHeight)
            postScale(width / cropWidth, height / cropHeight)
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.concat(matrix)
            canvas.drawPicture(picture, Rect(0, 0, sourceWidth, sourceHeight))
            canvas.clipRect(0f, 0f, sourceWidth.toFloat(), sourceHeight.toFloat())
            ImageAnnotationPainter.draw(canvas, sourceWidth.toFloat(), sourceHeight.toFloat(), edits.censors, edits.strokes)
        }
    }

    internal fun createOutputFile(context: Context, displayName: String?, extension: String): File {
        val directory = File(MediaCache.editedMediaDirectory(context), UUID.randomUUID().toString()).also { it.mkdirs() }
        val baseName = displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "image"
        return File(directory, baseName + extension)
    }

    private fun decodeSampled(context: Context, source: Uri, maxPixels: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return decodeUndecodable(context, source, maxPixels)

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxPixels)
        }
        return context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    /** Formats BitmapFactory returns 0x0 for — currently only JPEG XL. */
    private fun decodeUndecodable(context: Context, source: Uri, maxPixels: Int): Bitmap? {
        if (!JxlSupport.isAvailable) return null
        val bytes = context.contentResolver.openInputStream(source)?.use { it.readBytes() } ?: return null
        return JxlBitmaps.decodeBounded(bytes, maxPixels)
    }

    private fun sampleSizeFor(width: Int, height: Int, maxPixels: Int): Int {
        var sample = 1
        while (width.toLong() * height / (sample.toLong() * sample) > maxPixels) {
            sample *= 2
        }
        return sample
    }

    internal fun applyRotation(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    /**
     * Turns, tilts, annotates and crops in a single draw, so only the cropped output is allocated
     * next to the source.
     */
    private fun render(bitmap: Bitmap, edits: ImageEditorEdits): Bitmap {
        val sideways = edits.userRotation % 180 != 0
        val frameWidth = if (sideways) bitmap.height else bitmap.width
        val frameHeight = if (sideways) bitmap.width else bitmap.height
        val crop = edits.crop
        // Unclamped: with a tilt the crop may reach into the corners swung outside the frame.
        val left = (crop.left * frameWidth).roundToInt()
        val top = (crop.top * frameHeight).roundToInt()
        val right = (crop.right * frameWidth).roundToInt().coerceAtLeast(left + 1)
        val bottom = (crop.bottom * frameHeight).roundToInt().coerceAtLeast(top + 1)
        val untouched = edits.userRotation % 360 == 0 && edits.tiltDegrees == 0f && !edits.hasAnnotations &&
                left == 0 && top == 0 && right == frameWidth && bottom == frameHeight
        if (untouched) return bitmap

        val matrix = FrameTransform.sourceToFrame(bitmap.width, bitmap.height, edits.userRotation, edits.tiltDegrees)
        matrix.postTranslate(-left.toFloat(), -top.toFloat())
        val output = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.concat(matrix)
        canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.clipRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        ImageAnnotationPainter.draw(canvas, bitmap.width.toFloat(), bitmap.height.toFloat(), edits.censors, edits.strokes)
        bitmap.recycle()
        return output
    }
}
