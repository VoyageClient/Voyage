/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.video

import android.graphics.RectF
import im.vector.app.features.attachments.editor.AttachmentEdits
import kotlinx.parcelize.Parcelize

/**
 * The edits, kept against the untouched original so re-opening the editor replays them rather than
 * working from an already-edited file.
 */
@Parcelize
data class VideoEditorEdits(
        val startUs: Long = 0,
        val endUs: Long = 0,
        val durationUs: Long = 0,
        val rotationDegrees: Int = 0,
        val volume: PlaybackVolume = PlaybackVolume(),
        /** Normalised region of the displayed frame to keep, or null for all of it. */
        val crop: RectF? = null,
        val speed: PlaybackSpeed = PlaybackSpeed(),
) : AttachmentEdits {

    override val hasChanges: Boolean
        get() = rotationDegrees != 0 || !volume.isDefault || crop != null || !speed.isDefault ||
                (durationUs > 0 && (startUs > 0 || endUs < durationUs))
}
