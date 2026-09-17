/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.notifications

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.WorkerThread
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.bumptech.glide.signature.ObjectKey
import im.vector.app.features.home.AvatarRenderer
import org.matrix.android.sdk.api.util.MatrixItem
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class NotificationBitmapLoader @Inject constructor(
        private val context: Context,
        private val avatarRenderer: Provider<AvatarRenderer>,
) {

    /**
     * Get icon of a room.
     */
    @WorkerThread
    fun getRoomBitmap(path: String?, matrixItem: MatrixItem?): Bitmap? {
        path?.let { loadRoomBitmap(it) }?.let { return it }
        // Use circular default avatars when loading fails, matching notification sender icons.
        return matrixItem?.let { defaultAvatarBitmap(it, forceCircle = true) }
    }

    @WorkerThread
    private fun loadRoomBitmap(path: String): Bitmap? {
        return try {
            Glide.with(context)
                    .asBitmap()
                    .load(path)
                    .transform(CircleCrop())
                    .format(DecodeFormat.PREFER_ARGB_8888)
                    .signature(ObjectKey("room-icon-notification"))
                    .submit()
                    .get()
        } catch (e: Exception) {
            Timber.e(e, "decodeFile failed")
            null
        }
    }

    /**
     * Get icon of a user.
     * Before Android P, this does nothing because the icon won't be used
     */
    @WorkerThread
    fun getUserIcon(path: String?, matrixItem: MatrixItem?): IconCompat? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null
        }
        path?.let { loadUserIcon(it) }?.let { return it }
        return matrixItem
                ?.let { defaultAvatarBitmap(it, forceCircle = true) }
                ?.let { IconCompat.createWithBitmap(it) }
    }

    /** The default avatar the app draws elsewhere: the item's glyph over its own profile color. */
    @WorkerThread
    private fun defaultAvatarBitmap(matrixItem: MatrixItem, forceCircle: Boolean): Bitmap? {
        return try {
            val size = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_height)
            avatarRenderer.get().getPlaceholderDrawable(matrixItem, forceCircle = forceCircle).toBitmap(size, size)
        } catch (e: Exception) {
            Timber.e(e, "default avatar failed for ${matrixItem.id}")
            null
        }
    }

    @WorkerThread
    private fun loadUserIcon(path: String): IconCompat? {
        return try {
            val bitmap = Glide.with(context)
                    .asBitmap()
                    .load(path)
                    .transform(CircleCrop())
                    .format(DecodeFormat.PREFER_ARGB_8888)
                    .signature(ObjectKey("user-icon-notification"))
                    .submit()
                    .get()
            IconCompat.createWithBitmap(bitmap)
        } catch (e: Exception) {
            Timber.e(e, "decodeFile failed")
            null
        }
    }
}
