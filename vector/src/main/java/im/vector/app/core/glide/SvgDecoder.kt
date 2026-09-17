/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.util.ByteBufferUtil
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Renders an SVG source into a picture-backed Drawable so it stays a vector. Glide hands the
 * resulting Drawable to the ImageView untouched, and any scaling (PhotoView's pinch matrix
 * included) re-rasterises the picture at the new canvas scale — no pixelation, no fixed-size
 * thumbnail flash when the view is re-measured, and the drawable's intrinsic dimensions come
 * from the SVG itself so FIT_CENTER preserves the original aspect ratio.
 */
internal class SvgDecoder : ResourceDecoder<InputStream, Drawable> {

    override fun handles(source: InputStream, options: Options): Boolean {
        if (!source.markSupported()) return false
        source.mark(SNIFF_MARK_BYTES)
        return try {
            val buf = ByteArray(SNIFF_BYTES)
            val read = source.read(buf)
            looksLikeSvg(buf, read)
        } finally {
            source.reset()
        }
    }

    override fun decode(source: InputStream, width: Int, height: Int, options: Options): Resource<Drawable>? {
        return decodeSvg(BufferedInputStream(source))
    }

    companion object {
        internal const val SNIFF_BYTES = 256
        internal const val DEFAULT_SIZE = 512

        internal fun looksLikeSvg(buf: ByteArray, read: Int): Boolean {
            if (read <= 0) return false
            val head = String(buf, 0, minOf(read, SNIFF_BYTES), Charsets.US_ASCII).trimStart()
            return head.startsWith("<?xml") && head.contains("<svg", ignoreCase = true) ||
                    head.startsWith("<svg", ignoreCase = true) ||
                    head.startsWith("<!DOCTYPE svg", ignoreCase = true)
        }
    }
}

internal class SvgByteBufferDecoder : ResourceDecoder<ByteBuffer, Drawable> {

    override fun handles(source: ByteBuffer, options: Options): Boolean {
        val peek = source.duplicate()
        val buf = ByteArray(minOf(peek.remaining(), SvgDecoder.SNIFF_BYTES))
        peek.get(buf)
        return SvgDecoder.looksLikeSvg(buf, buf.size)
    }

    override fun decode(source: ByteBuffer, width: Int, height: Int, options: Options): Resource<Drawable>? {
        return decodeSvg(ByteBufferUtil.toStream(source))
    }
}

private fun decodeSvg(source: InputStream): Resource<Drawable>? {
    val svg = try {
        SVG.getFromInputStream(source)
    } catch (e: SVGParseException) {
        return null
    }

    val (intrinsicW, intrinsicH) = intrinsicSize(svg)
    // Record the picture at the SVG's intrinsic dimensions so the drawable reports those
    // as its intrinsic size (FIT_CENTER on PhotoView then fits-with-aspect into the view).
    // The picture itself is resolution-independent — replaying it through the canvas's
    // transform at zoom time produces crisp output at any scale.
    svg.documentWidth = intrinsicW.toFloat()
    svg.documentHeight = intrinsicH.toFloat()
    val picture = svg.renderToPicture(intrinsicW, intrinsicH)
    return PictureDrawableResource(picture)
}

/** Each Glide target needs its own drawable bounds; the recorded Picture can be shared. */
private class PictureDrawableResource(private val picture: Picture) : Resource<Drawable> {

    override fun getResourceClass(): Class<Drawable> = Drawable::class.java

    override fun get(): Drawable = ScaledPictureDrawable(picture)

    // A picture holds recorded drawing ops, not pixels; its footprint is not knowable, so report the
    // area it would rasterise to and let Glide size its cache from that.
    override fun getSize(): Int = (picture.width * picture.height * BYTES_PER_PIXEL).coerceAtLeast(1)

    override fun recycle() = Unit

    private companion object {
        private const val BYTES_PER_PIXEL = 4
    }
}

private fun intrinsicSize(svg: SVG): Pair<Int, Int> {
    val docW = svg.documentWidth.takeIf { it > 0 }
    val docH = svg.documentHeight.takeIf { it > 0 }
    val viewBox = svg.documentViewBox
    val w = (docW ?: viewBox?.width()?.takeIf { it > 0 } ?: SvgDecoder.DEFAULT_SIZE.toFloat()).toInt().coerceAtLeast(1)
    val h = (docH ?: viewBox?.height()?.takeIf { it > 0 } ?: SvgDecoder.DEFAULT_SIZE.toFloat()).toInt().coerceAtLeast(1)
    return w to h
}

/**
 * Unlike [android.graphics.drawable.PictureDrawable] — which only translates/clips to its bounds,
 * so consumers that size via setBounds (emote spans, previews) got the top-left slice of the
 * document — this scales the recorded picture into the bounds on every draw.
 */
internal class ScaledPictureDrawable(private val picture: Picture) : Drawable() {

    // Pictures can't replay onto a hardware canvas before M; rasterise once per size instead.
    private var raster: Bitmap? = null

    // A crossfade dims each layer through setAlpha. Ignoring it drew the picture solid from the first
    // frame, which covered whatever it was fading from and made the transition look like a swap.
    private var alpha = OPAQUE
    private val rasterPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun getIntrinsicWidth(): Int = picture.width
    override fun getIntrinsicHeight(): Int = picture.height

    // The saveLayerAlpha without flags is API 21; this one is the only version ICS has.
    @Suppress("DEPRECATION")
    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty || alpha == 0) return
        // drawPicture takes no paint, so a partly transparent picture is composited through a layer.
        val layer = if (alpha < OPAQUE) canvas.saveLayerAlpha(RectF(b), alpha, Canvas.ALL_SAVE_FLAG) else -1
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M && canvas.isHardwareAccelerated) {
            val bitmap = raster?.takeIf { it.width == b.width() && it.height == b.height() }
                    ?: Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.ARGB_8888).also {
                        Canvas(it).drawPicture(picture, Rect(0, 0, b.width(), b.height()))
                        raster = it
                    }
            canvas.drawBitmap(bitmap, null, b, rasterPaint)
        } else {
            canvas.drawPicture(picture, b)
        }
        if (layer != -1) canvas.restoreToCount(layer)
    }

    override fun setAlpha(alpha: Int) {
        if (this.alpha == alpha) return
        this.alpha = alpha
        invalidateSelf()
    }
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        private const val OPAQUE = 255
    }
}
