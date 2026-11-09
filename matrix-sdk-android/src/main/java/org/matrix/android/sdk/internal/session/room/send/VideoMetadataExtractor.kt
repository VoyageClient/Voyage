/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.send

import org.matrix.android.sdk.api.session.content.ContentAttachmentData

internal fun interface VideoMetadataExtractor {
    /** Orientation-corrected (width, height) of the video, or (0, 0) if unavailable. */
    fun getVideoSize(attachment: ContentAttachmentData): Pair<Int, Int>
}
