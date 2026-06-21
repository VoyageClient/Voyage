/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.utils

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build

object MediaPlayerCompat {

    // CONTENT_TYPE_MUSIC / USAGE_MEDIA (not SPEECH / VOICE_COMMUNICATION) so playback stays loud.
    @Suppress("DEPRECATION")
    fun setMediaAudioAttributes(player: MediaPlayer) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            player.setAudioAttributes(
                    AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
            )
        } else {
            player.setAudioStreamType(AudioManager.STREAM_MUSIC)
        }
    }
}
