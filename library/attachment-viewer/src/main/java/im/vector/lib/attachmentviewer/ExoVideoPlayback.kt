/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

// The 2.x line is deprecated wholesale in favour of media3, whose floor is above the one this
// fork keeps to.
@file:Suppress("DEPRECATION")

package im.vector.lib.attachmentviewer

import android.annotation.TargetApi
import android.content.Context
import android.net.Uri
import android.os.Build
import android.view.Surface
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SeekParameters
import com.google.android.exoplayer2.video.VideoSize
import java.io.File

/**
 * Looping here is the player's own repeat, which reads straight on through the seam without ever
 * stopping the audio track — where MediaPlayer ends its track at every end of stream and pays for
 * a new one, loudly on Bluetooth. Speed is a software time stretch rather than a resample, so it
 * is not held to the sink's buffer either.
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
internal class ExoVideoPlayback : VideoPlayback {

    private var player: ExoPlayer? = null
    private var reportedReady = false

    override val isPlaying: Boolean get() = player?.isPlaying == true

    override val positionMs: Int get() = player?.currentPosition?.toInt() ?: 0

    override val durationMs: Int
        get() = player?.duration?.takeIf { it != C.TIME_UNSET }?.toInt() ?: 0

    override val audioSessionId: Int get() = player?.audioSessionId ?: 0

    override fun open(context: Context, source: String, surface: Surface, looping: Boolean, listener: VideoPlayback.Listener) {
        reportedReady = false
        val exo = ExoPlayer.Builder(context).build()
        player = exo
        exo.setVideoSurface(surface)
        exo.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> if (!reportedReady) {
                        reportedReady = true
                        listener.onReady()
                    }
                    Player.STATE_ENDED -> listener.onCompletion()
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                listener.onVideoSizeChanged(videoSize.width, videoSize.height)
            }

            override fun onPlayerError(error: PlaybackException) {
                listener.onError()
            }
        })
        exo.setMediaItem(MediaItem.fromUri(source.toPlayableUri()))
        exo.playWhenReady = false
        exo.prepare()
    }

    private fun String.toPlayableUri(): Uri =
            if (startsWith("content://")) Uri.parse(this) else Uri.fromFile(File(this))

    override fun setSurface(surface: Surface?) {
        player?.setVideoSurface(surface)
    }

    override fun setLooping(looping: Boolean) {
        player?.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    override fun play() {
        player?.play()
    }

    override fun pause() {
        player?.pause()
    }

    override fun seekTo(positionMs: Int, exact: Boolean) {
        val exo = player ?: return
        exo.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.PREVIOUS_SYNC)
        exo.seekTo(positionMs.toLong())
    }

    override fun setSpeed(speed: Float, pitchFollowsSpeed: Boolean): Boolean {
        player?.playbackParameters = PlaybackParameters(speed, if (pitchFollowsSpeed) speed else 1f)
        return true
    }

    override fun setVolume(volume: Float) {
        player?.volume = volume
    }

    override fun release() {
        reportedReady = false
        player?.release()
        player = null
    }
}
