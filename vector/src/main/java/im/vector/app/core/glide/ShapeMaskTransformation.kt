/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.util.Util
import im.vector.app.features.home.avatar.AvatarShapes
import im.vector.app.features.settings.AvatarShape
import java.security.MessageDigest

private const val ID = "im.vector.app.core.glide.ShapeMaskTransformation"
private val ID_BYTES = ID.toByteArray(Charsets.UTF_8)

/**
 * Cuts a bitmap to an [AvatarShape]'s outline. Glide's own transforms only cover circles and rounded
 * corners, so this is what the polygonal shapes go through.
 */
class ShapeMaskTransformation(private val shape: AvatarShape) : BitmapTransformation() {

    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        val width = toTransform.width
        val height = toTransform.height
        val result = pool.get(width, height, Bitmap.Config.ARGB_8888)
        result.setHasAlpha(true)
        val canvas = Canvas(result)
        canvas.drawBitmap(toTransform, 0f, 0f, null)
        // Erasing everything outside the shape rather than filling inside it: a PorterDuff mode only
        // blends where the source geometry lands, so the region to touch is the one being removed.
        val mask = AvatarShapes.path(shape, RectF(0f, 0f, width.toFloat(), height.toFloat()))
        mask.fillType = android.graphics.Path.FillType.INVERSE_WINDING
        canvas.drawPath(mask, MASK_PAINT)
        return result
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(ID_BYTES)
        messageDigest.update(shape.storageKey.toByteArray(Charsets.UTF_8))
    }

    override fun equals(other: Any?) = other is ShapeMaskTransformation && other.shape == shape

    override fun hashCode() = Util.hashCode(ID.hashCode(), shape.ordinal)

    private companion object {
        val MASK_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
    }
}
