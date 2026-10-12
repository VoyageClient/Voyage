/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

@file:Suppress("DEPRECATION")

package im.vector.app.features.audio

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import im.vector.app.core.utils.MediaPlayerCompat
import java.io.File

internal interface MessageAudioPlayer {
    val position: Int
    val duration: Int
    fun play()
    fun pause()
    fun seek(position: Int)
    fun parameters(speed: Float, pitch: Float)
    fun release()

    interface Listener {
        fun ready()
        fun ended()
        fun failed(error: Throwable)
    }

    companion object {
        fun open(context: Context, file: File, listener: Listener): MessageAudioPlayer =
                if (Build.VERSION.SDK_INT >= 16) ExoMessageAudioPlayer(context, file, listener)
                else LegacyMessageAudioPlayer(context, file, listener)
    }
}

@RequiresApi(16)
private class ExoMessageAudioPlayer(context: Context, file: File, listener: MessageAudioPlayer.Listener) : MessageAudioPlayer {
    private val player = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(com.google.android.exoplayer2.audio.AudioAttributes.DEFAULT, false)
        setWakeMode(C.WAKE_MODE_LOCAL)
        addListener(object : Player.Listener {
            private var reportedReady = false
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && !reportedReady) {
                    reportedReady = true
                    listener.ready()
                } else if (playbackState == Player.STATE_ENDED) {
                    listener.ended()
                }
            }
            override fun onPlayerError(error: PlaybackException) = listener.failed(error)
        })
        setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        prepare()
    }
    override val position get() = player.currentPosition.toInt()
    override val duration get() = player.duration.takeIf { it != C.TIME_UNSET }?.toInt() ?: 0
    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seek(position: Int) = player.seekTo(position.toLong())
    override fun parameters(speed: Float, pitch: Float) { player.playbackParameters = PlaybackParameters(speed, pitch) }
    override fun release() = player.release()
}

private class LegacyMessageAudioPlayer(context: Context, file: File, listener: MessageAudioPlayer.Listener) : MessageAudioPlayer {
    private var prepared = false
    private val player = MediaPlayer()

    init {
        try {
            MediaPlayerCompat.setMediaAudioAttributes(player)
            player.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            player.setDataSource(file.path)
            player.setOnPreparedListener { prepared = true; listener.ready() }
            player.setOnCompletionListener { listener.ended() }
            player.setOnErrorListener { _, what, extra -> listener.failed(IllegalStateException("MediaPlayer $what/$extra")); true }
            player.prepareAsync()
        } catch (failure: Throwable) {
            player.release()
            throw failure
        }
    }
    override val position get() = if (prepared) player.currentPosition else 0
    override val duration get() = if (prepared) player.duration else 0
    override fun play() { if (prepared) player.start() }
    override fun pause() { if (prepared) player.pause() }
    override fun seek(position: Int) { if (prepared) player.seekTo(position) }

    // API 14 plays a PCM file processed with these parameters before opening the player.
    override fun parameters(speed: Float, pitch: Float) = Unit
    override fun release() = player.release()
}
