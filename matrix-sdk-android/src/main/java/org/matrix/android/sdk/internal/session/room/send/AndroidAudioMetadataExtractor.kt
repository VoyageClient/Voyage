/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.send

import android.content.Context
import android.media.MediaMetadataRetriever
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.content.AudioCoverArt
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.content.queryUriAndroid
import org.matrix.android.sdk.api.session.room.model.message.AudioMetadata
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
                    coverArtBlurhash = retriever.embeddedPicture?.let { AudioCoverArt.encode(it) },
            ).takeIfNotEmpty()
        } catch (error: Exception) {
            Timber.w(error, "Cannot read audio metadata")
            null
        } finally {
            tryOrNull { retriever.release() }
        }
    }

    private fun MediaMetadataRetriever.tag(key: Int) = tryOrNull { extractMetadata(key) }?.takeIf { it.isNotBlank() }
}
