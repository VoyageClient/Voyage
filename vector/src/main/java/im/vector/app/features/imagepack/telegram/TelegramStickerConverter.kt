/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.telegram

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.LottieFeatureFlag
import im.vector.lib.animatedimage.AnimatedFrame
import im.vector.lib.animatedimage.AnimatedWebpEncoder
import im.vector.lib.animatedimage.WebmAlphaDecoder
import im.vector.lib.animatedimage.WebmDemuxer
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** A converted sticker; [animated] ones are final and should be uploaded without recompression. */
class ConvertedSticker(val file: File, val mimeType: String, val animated: Boolean, val size: Pair<Int, Int>? = null)

/**
 * Turns downloaded Telegram sticker files into images Matrix clients can show: static WebP passes
 * through, Lottie (.tgs) and VP8/VP9 WebM (.webm) become animated WebP.
 */
@RequiresApi(Build.VERSION_CODES.KITKAT)
class TelegramStickerConverter @Inject constructor() {

    suspend fun convert(input: File, output: File): ConvertedSticker = when (input.extension.lowercase()) {
        "tgs" -> renderLottie(input, output)
        "webm" -> renderWebm(input, output)
        "png" -> ConvertedSticker(input, "image/png", animated = false)
        else -> ConvertedSticker(input, MIME_WEBP, animated = false)
    }

    private suspend fun renderLottie(input: File, output: File): ConvertedSticker {
        val result = GZIPInputStream(input.inputStream().buffered()).use { LottieCompositionFactory.fromJsonInputStreamSync(it, null) }
        val composition = result.value ?: throw IOException("Unreadable Lottie sticker", result.exception)
        val bounds = composition.bounds
        val (width, height) = fit(bounds.width(), bounds.height())
        val drawable = LottieDrawable().apply {
            enableFeatureFlag(LottieFeatureFlag.MergePathsApi19, true)
            setComposition(composition)
            setBounds(0, 0, width, height)
        }
        val sourceFps = composition.frameRate.takeIf { it > 0f } ?: DEFAULT_FPS
        val outputFps = minOf(sourceFps, MAX_FPS)
        val durationMs = composition.duration.takeIf { it > 0f } ?: (1000f / outputFps)
        val frameCount = (durationMs * outputFps / 1000f).roundToInt().coerceAtLeast(1)
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        try {
            AnimatedWebpEncoder.StreamWriter(output, width, height, QUALITY).use { writer ->
                var previousEndMs = 0
                for (index in 0 until frameCount) {
                    coroutineContext.ensureActive()
                    // Rounded cumulative end times, so per-frame rounding doesn't drift the loop length.
                    val endMs = ((index + 1) * 1000f / outputFps).roundToInt()
                    drawable.progress = index.toFloat() / frameCount
                    bitmap.eraseColor(0)
                    drawable.draw(canvas)
                    if (!writer.append(listOf(AnimatedFrame(bitmap, endMs - previousEndMs)))) throw IOException("WebP encode failed")
                    previousEndMs = endMs
                }
                if (!writer.finish()) throw IOException("WebP encode failed")
            }
        } finally {
            bitmap.recycle()
        }
        return ConvertedSticker(output, MIME_WEBP, animated = true, size = width to height)
    }

    private suspend fun renderWebm(input: File, output: File): ConvertedSticker {
        val video = WebmDemuxer.parse(input.readBytes()) ?: throw IOException("Unreadable WebM sticker")
        coroutineContext.ensureActive()
        val (width, height) = fit(video.width, video.height)
        val scaled = if (width != video.width || height != video.height) createBitmap(width, height) else null
        val scaleCanvas = scaled?.let { Canvas(it) }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val target = Rect(0, 0, width, height)
        try {
            AnimatedWebpEncoder.StreamWriter(output, width, height, QUALITY).use { writer ->
                var failure: IOException? = null
                val context = coroutineContext
                // Above MAX_FPS a frame is skipped and its time carried into the next one. The 10% slack
                // keeps 30fps clips whole: WebM's 1ms timecodes make their frames 33ms, not 33.3ms.
                val minFrameUs = (1_000_000 / (MAX_FPS * 1.1f)).toLong()
                var carryUs = 0L
                var timelineUs = 0L
                var writtenMs = 0L
                WebmAlphaDecoder(video).decode { frame, durationUs ->
                    context.ensureActive()
                    if (failure != null) return@decode
                    carryUs += durationUs
                    timelineUs += durationUs
                    if (carryUs < minFrameUs) return@decode
                    carryUs = 0
                    val endMs = (timelineUs + 500) / 1000
                    val durationMs = (endMs - writtenMs).toInt()
                    writtenMs = endMs
                    val source = if (scaled != null) {
                        scaled.eraseColor(0)
                        scaleCanvas!!.drawBitmap(frame, null, target, paint)
                        scaled
                    } else {
                        frame
                    }
                    if (!writer.append(listOf(AnimatedFrame(source, durationMs)))) failure = IOException("WebP encode failed")
                }
                failure?.let { throw it }
                if (!writer.finish()) throw IOException("WebP encode failed")
            }
        } finally {
            scaled?.recycle()
        }
        return ConvertedSticker(output, MIME_WEBP, animated = true, size = width to height)
    }

    private fun fit(width: Int, height: Int): Pair<Int, Int> {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val scale = minOf(1f, MAX_DIMENSION.toFloat() / maxOf(safeWidth, safeHeight))
        return (safeWidth * scale).roundToInt().coerceAtLeast(1) to (safeHeight * scale).roundToInt().coerceAtLeast(1)
    }

    private companion object {
        const val MIME_WEBP = "image/webp"
        const val MAX_DIMENSION = 512
        const val MAX_FPS = 30f
        const val DEFAULT_FPS = 30f
        const val QUALITY = 80
    }
}
