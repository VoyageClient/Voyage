/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.send

import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.room.model.message.AudioMetadata

// Platform seam for reading a track's tags and cover art when building the local echo (MSC4549).
internal fun interface AudioMetadataExtractor {
    /** Tags and a BlurHash of the embedded cover, or null when the file carries neither. */
    fun extractAudioMetadata(attachment: ContentAttachmentData): AudioMetadata?
}
