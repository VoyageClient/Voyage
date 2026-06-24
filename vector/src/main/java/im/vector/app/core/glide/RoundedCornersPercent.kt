/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.graphics.Bitmap
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.load.resource.bitmap.TransformationUtils
import com.bumptech.glide.util.Util
import java.nio.ByteBuffer
import java.security.MessageDigest

private const val ID = "im.vector.app.core.glide.RoundedCornersPercent"
private val ID_BYTES = ID.toByteArray(Charsets.UTF_8)

/**
 * Like Glide's [com.bumptech.glide.load.resource.bitmap.RoundedCorners], but the radius is a fraction
 * of the shorter side rather than a fixed pixel value, so rounded-square avatars keep the same visual
 * rounding whatever size they're displayed at.
 */
class RoundedCornersPercent(private val percent: Float) : BitmapTransformation() {

    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        val radius = minOf(toTransform.width, toTransform.height) * percent
        return TransformationUtils.roundedCorners(pool, toTransform, radius.toInt())
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(ID_BYTES)
        messageDigest.update(ByteBuffer.allocate(4).putFloat(percent).array())
    }

    override fun equals(other: Any?) = other is RoundedCornersPercent && other.percent == percent

    override fun hashCode() = Util.hashCode(ID.hashCode(), Util.hashCode(percent))
}
