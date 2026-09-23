/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.model.message

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

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
         * A BlurHash approximating the track's cover art.
         */
        @Json(name = "cover_art") val coverArt: String? = null,
) {
    val isEmpty: Boolean
        get() = title.isNullOrBlank() && artist.isNullOrBlank() && album.isNullOrBlank() && coverArt.isNullOrBlank()

    fun takeIfNotEmpty(): AudioMetadata? = takeIf { !it.isEmpty }
}
