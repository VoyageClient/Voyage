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
import android.graphics.Picture
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.resource.SimpleResource
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import java.io.BufferedInputStream
import java.io.InputStream

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
        source.mark(SNIFF_BYTES)
        return try {
            val buf = ByteArray(SNIFF_BYTES)
            val read = source.read(buf)
            if (read <= 0) return false
            val head = String(buf, 0, read, Charsets.US_ASCII).trimStart()
            head.startsWith("<?xml") && head.contains("<svg", ignoreCase = true) ||
                    head.startsWith("<svg", ignoreCase = true) ||
                    head.startsWith("<!DOCTYPE svg", ignoreCase = true)
        } finally {
            source.reset()
        }
    }

    override fun decode(source: InputStream, width: Int, height: Int, options: Options): Resource<Drawable>? {
        val svg = try {
            SVG.getFromInputStream(BufferedInputStream(source))
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
        return SimpleResource(ScaledPictureDrawable(picture))
    }

    private fun intrinsicSize(svg: SVG): Pair<Int, Int> {
        val docW = svg.documentWidth.takeIf { it > 0 }
        val docH = svg.documentHeight.takeIf { it > 0 }
        val viewBox = svg.documentViewBox
        val w = (docW ?: viewBox?.width()?.takeIf { it > 0 } ?: DEFAULT_SIZE.toFloat()).toInt().coerceAtLeast(1)
        val h = (docH ?: viewBox?.height()?.takeIf { it > 0 } ?: DEFAULT_SIZE.toFloat()).toInt().coerceAtLeast(1)
        return w to h
    }

    companion object {
        private const val SNIFF_BYTES = 256
        private const val DEFAULT_SIZE = 512
    }
}

/**
 * Unlike [android.graphics.drawable.PictureDrawable] — which only translates/clips to its bounds,
 * so consumers that size via setBounds (emote spans, previews) got the top-left slice of the
 * document — this scales the recorded picture into the bounds on every draw.
 */
internal class ScaledPictureDrawable(private val picture: Picture) : Drawable() {

    // Pictures can't replay onto a hardware canvas before M; rasterise once per size instead.
    private var raster: Bitmap? = null

    override fun getIntrinsicWidth(): Int = picture.width
    override fun getIntrinsicHeight(): Int = picture.height

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M && canvas.isHardwareAccelerated) {
            val bitmap = raster?.takeIf { it.width == b.width() && it.height == b.height() }
                    ?: Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.ARGB_8888).also {
                        Canvas(it).drawPicture(picture, Rect(0, 0, b.width(), b.height()))
                        raster = it
                    }
            canvas.drawBitmap(bitmap, null, b, null)
        } else {
            canvas.drawPicture(picture, b)
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
