/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.attachmentviewer

sealed class AttachmentEvents {
    data class VideoEvent(val isPlaying: Boolean, val progress: Int, val duration: Int) : AttachmentEvents()
}

interface AttachmentEventListener {
    fun onEvent(event: AttachmentEvents)
}

sealed class AttachmentCommands {
    object PauseVideo : AttachmentCommands()
    object StartVideo : AttachmentCommands()
    data class SeekTo(val percentProgress: Int) : AttachmentCommands()

    /** @param changePitch whether the audio pitch rides along with the speed, as tape does. */
    data class SetPlaybackSpeed(val speed: Float, val changePitch: Boolean) : AttachmentCommands()

    /** @param gain 1 is the clip's own loudness; above it the sound is boosted rather than scaled. */
    data class SetVolume(val gain: Float, val muted: Boolean) : AttachmentCommands()
}
