/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.graphics.Bitmap
import com.bumptech.glide.load.Key
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.load.resource.bitmap.TransformationUtils
import java.security.MessageDigest
import kotlin.math.min
import kotlin.math.roundToInt

/** Cap bitmap rounding so small thumbnails retain visible straight edges. */
class CappedRoundedCorners(private val radiusPx: Int) : BitmapTransformation() {

    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        // Compensate for display scaling because the corner radius is baked into decoded pixels.
        val displayScale = when {
            outWidth <= 0 || outHeight <= 0 || toTransform.width <= 0 || toTransform.height <= 0 -> 1f
            else -> min(outWidth.toFloat() / toTransform.width, outHeight.toFloat() / toTransform.height)
        }
        val inBitmapSpace = if (displayScale > 0f) radiusPx / displayScale else radiusPx.toFloat()
        // Still capped, or a picture shorter than the radius comes out a pill.
        val capped = cappedMediaCornerRadius(inBitmapSpace, toTransform.width.toFloat(), toTransform.height.toFloat())
                .roundToInt()
        if (capped <= 0) return toTransform
        return TransformationUtils.roundedCorners(pool, toTransform, capped)
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(ID.toByteArray(Key.CHARSET))
        messageDigest.update(radiusPx.toString().toByteArray(Key.CHARSET))
    }

    override fun equals(other: Any?) = other is CappedRoundedCorners && other.radiusPx == radiusPx

    override fun hashCode() = ID.hashCode() * 31 + radiusPx

    private companion object {
        // Bumped when the maths changes: the key is what a cached transformed bitmap is stored
        // under, so without it every already-rounded image keeps its old corners.
        private const val ID = "im.vector.app.features.media.CappedRoundedCorners.v2"
    }
}
