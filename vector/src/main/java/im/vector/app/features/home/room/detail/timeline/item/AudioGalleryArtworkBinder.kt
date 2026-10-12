/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.ImageViewCompat
import im.vector.app.R
import im.vector.app.core.extensions.singletonEntryPoint
import im.vector.app.features.attachments.preview.AudioDetails
import org.matrix.android.sdk.api.session.room.model.message.AudioMetadata
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal object AudioGalleryArtworkBinder {
    private val worker = Executors.newSingleThreadExecutor()
    private val cache = LruCache<String, Artwork>(32)
    private data class Artwork(val backdrop: Bitmap, val note: Bitmap)

    fun clear(image: ImageView) {
        (image.getTag(R.id.audio_gallery_artwork_binding) as? AtomicBoolean)?.set(true)
        image.setTag(R.id.audio_gallery_artwork_binding, null)
        image.clearColorFilter()
    }

    fun bind(image: ImageView, icon: ImageView, metadata: AudioMetadata?, allowFetch: Boolean) {
        metadata ?: return
        val cancelled = AtomicBoolean(false)
        image.setTag(R.id.audio_gallery_artwork_binding, cancelled)
        val context = image.context.applicationContext
        val supplied = metadata.coverArtBlurhash?.takeIf { it.isNotBlank() }
        val cached = metadata.coverArtUrl?.let { AudioDetails.cachedCoverArtHash(it) }
        if (supplied == null && cached == null && (!allowFetch || metadata.coverArtUrl == null)) return
        val mask = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        AppCompatResources.getDrawable(image.context, R.drawable.ic_music_note)?.apply {
            setBounds(0, 0, mask.width, mask.height)
            draw(Canvas(mask))
        }
        worker.execute {
            try {
                if (cancelled.get()) return@execute
                fun artwork(hash: String): Artwork? = cache.get(hash) ?: AudioDetails.coverArtBackdrop(hash)?.let { backdrop ->
                    Artwork(backdrop, makeNote(backdrop, mask)).also { cache.put(hash, it) }
                }
                val art = (supplied ?: cached)?.let(::artwork) ?: if (allowFetch && !cancelled.get()) {
                    context.singletonEntryPoint().activeSessionHolder().getSafeActiveSession()?.let { session ->
                        AudioDetails.fetchCoverArtHash(context, session, metadata)?.hash?.let(::artwork)
                    }
                } else null
                if (art != null && !cancelled.get()) image.post {
                    if (image.getTag(R.id.audio_gallery_artwork_binding) !== cancelled || cancelled.get()) return@post
                    image.setImageDrawable(BitmapDrawable(image.resources, art.backdrop).apply {
                        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                                0.55f, 0f, 0f, 0f, 0f,
                                0f, 0.55f, 0f, 0f, 0f,
                                0f, 0f, 0.55f, 0f, 0f,
                                0f, 0f, 0f, 1f, 0f,
                        )))
                    })
                    ImageViewCompat.setImageTintList(icon, null)
                    icon.setImageBitmap(art.note)
                }
            } catch (failure: Exception) {
                Timber.w(failure, "Cannot render audio gallery cover art")
            } finally {
                mask.recycle()
            }
        }
    }

    private fun makeNote(backdrop: Bitmap, mask: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                    0.35f, 0f, 0f, 0f, 165f,
                    0f, 0.35f, 0f, 0f, 165f,
                    0f, 0f, 0.35f, 0f, 165f,
                    0f, 0f, 0f, 1f, 0f,
            )))
        }
        canvas.drawBitmap(backdrop, null, Rect(0, 0, result.width, result.height), paint)
        paint.colorFilter = null
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        canvas.drawBitmap(mask, 0f, 0f, paint)
        return result
    }
}
