/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.audio

import im.vector.app.features.attachments.editor.AttachmentEdits
import im.vector.app.features.attachments.editor.video.PlaybackSpeed
import im.vector.app.features.attachments.editor.video.PlaybackVolume
import kotlinx.parcelize.Parcelize

/**
 * The edits, kept against the untouched original so re-opening the editor replays them rather than
 * working from an already-encoded file.
 */
@Parcelize
data class AudioEditorEdits(
        val startUs: Long = 0,
        val endUs: Long = 0,
        val durationUs: Long = 0,
        val volume: PlaybackVolume = PlaybackVolume(),
        val speed: PlaybackSpeed = PlaybackSpeed(),
        val reversed: Boolean = false,
) : AttachmentEdits {

    override val hasChanges: Boolean
        get() = !volume.isDefault || !speed.isDefault || reversed ||
                (durationUs > 0 && (startUs > 0 || endUs < durationUs))
}
