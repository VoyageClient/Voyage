/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.audio

import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent

data class AudioGalleryData(
        val roomId: String,
        val eventId: String,
        val content: MessageAudioContent,
        val stableId: String = eventId,
        val galleryIndex: Int? = null,
)
