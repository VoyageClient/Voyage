/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.send

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.vanniktech.blurhash.BlurHash
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.content.queryUriAndroid
import org.matrix.android.sdk.api.session.room.model.message.AudioMetadata
import org.matrix.android.sdk.internal.session.content.blurHashComponents
import timber.log.Timber
import javax.inject.Inject

internal class AndroidAudioMetadataExtractor @Inject constructor(
        private val context: Context
) : AudioMetadataExtractor {

    override fun extractAudioMetadata(attachment: ContentAttachmentData): AudioMetadata? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, attachment.queryUriAndroid)
            AudioMetadata(
                    title = retriever.tag(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    artist = retriever.tag(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                            ?: retriever.tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                    album = retriever.tag(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    coverArt = retriever.embeddedPicture?.let { encodeCoverArt(it) },
            ).takeIfNotEmpty()
        } catch (error: Exception) {
            Timber.w(error, "Cannot read audio metadata")
            null
        } finally {
            tryOrNull { retriever.release() }
        }
    }

    private fun MediaMetadataRetriever.tag(key: Int) = tryOrNull { extractMetadata(key) }?.takeIf { it.isNotBlank() }

    private fun encodeCoverArt(bytes: ByteArray): String? = tryOrNull {
        val art = decodeScaled(bytes) ?: return null
        try {
            val (xc, yc) = blurHashComponents(art.width, art.height)
            BlurHash.encode(art, xc, yc)
        } finally {
            art.recycle()
        }
    }

    /** BlurHash.encode runs a trig term per pixel, so never hand it a full-size cover. */
    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > COVER_MAX_DIMENSION) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    companion object {
        private const val COVER_MAX_DIMENSION = 128
    }
}
