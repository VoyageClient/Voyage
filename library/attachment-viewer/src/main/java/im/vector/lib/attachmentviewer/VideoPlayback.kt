/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.attachmentviewer

import android.content.Context
import android.os.Build
import android.view.Surface

/**
 * The player behind the video pages, so the viewer can be served by ExoPlayer where it exists and
 * by MediaPlayer on the platforms below it.
 */
internal interface VideoPlayback {

    interface Listener {
        fun onReady()
        fun onVideoSizeChanged(width: Int, height: Int)
        fun onCompletion()
        fun onError()
    }

    val isPlaying: Boolean
    val positionMs: Int
    val durationMs: Int

    /** Zero until there is a player to ask; a boost can only attach to a real session. */
    val audioSessionId: Int

    fun open(context: Context, source: String, surface: Surface, looping: Boolean, listener: Listener)
    fun setSurface(surface: Surface?)
    fun setLooping(looping: Boolean)
    fun play()
    fun pause()
    fun seekTo(positionMs: Int, exact: Boolean)

    /** False when the player refused the speed outright and only a fresh one can carry it. */
    fun setSpeed(speed: Float, pitchFollowsSpeed: Boolean): Boolean

    /** 0 to 1; anything louder is the caller's business, since no player scales past its samples. */
    fun setVolume(volume: Float)

    fun release()

    companion object {
        fun create(): VideoPlayback =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    ExoVideoPlayback()
                } else {
                    LegacyVideoPlayback()
                }
    }
}
