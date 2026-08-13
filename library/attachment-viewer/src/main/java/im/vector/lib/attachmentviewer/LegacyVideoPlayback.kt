/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.attachmentviewer

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Surface

/** The platform player, for the versions ExoPlayer does not reach. */
internal class LegacyVideoPlayback : VideoPlayback {

    private var player: MediaPlayer? = null
    private var prepared = false

    override val isPlaying: Boolean
        get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    override val positionMs: Int
        get() = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)

    override val durationMs: Int
        get() = runCatching { player?.duration ?: 0 }.getOrDefault(0)

    override fun open(context: Context, source: String, surface: Surface, looping: Boolean, listener: VideoPlayback.Listener) {
        prepared = false
        try {
            player = MediaPlayer().apply {
                setSurface(surface)
                if (source.startsWith("content://")) {
                    setDataSource(context, Uri.parse(source))
                } else {
                    setDataSource(source)
                }
                isLooping = looping
                setOnVideoSizeChangedListener { _, width, height -> listener.onVideoSizeChanged(width, height) }
                setOnPreparedListener {
                    prepared = true
                    listener.onReady()
                }
                setOnCompletionListener { listener.onCompletion() }
                // Without one of these the platform reports an error as a completion, and a player
                // whose media server has gone answers every later call with another error — the two
                // feed each other into a storm that locks the UI up. Take the player down instead.
                setOnErrorListener { _, what, extra ->
                    Log.w(LegacyVideoPlayback::class.java.name, "Video error what=$what extra=$extra")
                    listener.onError()
                    true
                }
                prepareAsync()
            }
        } catch (failure: Throwable) {
            Log.w(LegacyVideoPlayback::class.java.name, "Failed to start video", failure)
            release()
            listener.onError()
        }
    }

    override fun setSurface(surface: Surface?) {
        runCatching { player?.setSurface(surface) }
    }

    override fun setLooping(looping: Boolean) {
        runCatching { player?.isLooping = looping }
    }

    override fun play() {
        runCatching { player?.start() }
    }

    override fun pause() {
        runCatching { if (player?.isPlaying == true) player?.pause() }
    }

    override fun seekTo(positionMs: Int, exact: Boolean) {
        val active = player ?: return
        runCatching {
            // SEEK_CLOSEST is frame-accurate; the int overload defaults to SEEK_PREVIOUS_SYNC
            // which snaps to the previous keyframe (often 5–10s apart).
            if (exact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                active.seekTo(positionMs.toLong(), MediaPlayer.SEEK_CLOSEST)
            } else {
                active.seekTo(positionMs)
            }
        }
    }

    override fun setSpeed(speed: Float, pitchFollowsSpeed: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val active = player?.takeIf { prepared } ?: return true
        val playing = isPlaying
        // Pitch is served by resampling, which the sink can only take so far, so tape behaviour is
        // what gets dropped past its ceiling rather than the speed the user asked for.
        val pitches = if (pitchFollowsSpeed) listOf(speed, 1f) else listOf(1f)
        val applied = pitches.any { pitch ->
            runCatching { active.playbackParams = active.playbackParams.setSpeed(speed).setPitch(pitch) }.isSuccess
        }
        // Setting the parameters starts a paused player, which would run off the frame on show.
        if (applied && !playing) runCatching { active.pause() }
        return applied
    }

    override fun release() {
        prepared = false
        player?.let {
            runCatching { if (it.isPlaying) it.stop() }
            it.release()
        }
        player = null
    }
}
