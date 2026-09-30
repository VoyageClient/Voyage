/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.model.message

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.api.session.crypto.attachments.ElementToDecrypt
import org.matrix.android.sdk.api.session.crypto.attachments.toElementToDecrypt
import org.matrix.android.sdk.api.session.crypto.model.EncryptedFileInfo

/**
 * What an audio file says about itself, as MSC4549 carries it in an `m.audio` event's `info`.
 */
@JsonClass(generateAdapter = true)
data class AudioMetadata(
        /**
         * The title of the audio track or song.
         */
        @Json(name = "title") val title: String? = null,

        /**
         * The artist or performer of the track.
         */
        @Json(name = "artist") val artist: String? = null,

        /**
         * The album to which the track belongs.
         */
        @Json(name = "album") val album: String? = null,

        /**
         * An mxc:// URI for the unencrypted cover art image.
         */
        @Json(name = "cover_art") val coverArt: String? = null,

        /**
         * The encrypted cover art image, in place of [coverArt].
         */
        @Json(name = "cover_art_file") val coverArtFile: EncryptedFileInfo? = null,

        @Json(name = "cover_art_info") val coverArtInfo: ThumbnailInfo? = null,

        /**
         * A BlurHash approximating the track's cover art.
         */
        @Json(name = "cover_art_blurhash") val coverArtBlurhash: String? = null,
) {
    private fun validCoverArtFile(): EncryptedFileInfo? = coverArtFile?.takeIf { it.isValid() && it.url.isContentUri() }

    /** The cover art image's mxc:// URI, encrypted or not. */
    val coverArtUrl: String?
        get() = validCoverArtFile()?.url ?: coverArt?.takeIf { it.isContentUri() }

    private fun String?.isContentUri() = this != null && CONTENT_URI.matches(this)

    val coverArtElementToDecrypt: ElementToDecrypt?
        get() = validCoverArtFile()?.toElementToDecrypt()

    val isEmpty: Boolean
        get() = title.isNullOrBlank() && artist.isNullOrBlank() && album.isNullOrBlank() &&
                coverArtBlurhash.isNullOrBlank() && coverArtUrl == null

    fun takeIfNotEmpty(): AudioMetadata? = takeIf { !it.isEmpty }
}

/** `mxc://<server-name>/<media-id>`, with the spec's grammar for both parts. */
private val CONTENT_URI = Regex("""mxc://(\[[0-9A-Fa-f:.]+]|[A-Za-z0-9.-]+)(:[0-9]{1,5})?/[A-Za-z0-9_-]+""")
