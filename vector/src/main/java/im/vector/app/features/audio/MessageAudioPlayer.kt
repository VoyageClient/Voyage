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
import timber.log.Timber
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
        // ExoPlayer has no MIDI extractor, but the platform's Sonivox synth plays MIDI on every API level.
        fun open(context: Context, file: File, midi: Boolean, listener: Listener): MessageAudioPlayer =
                if (Build.VERSION.SDK_INT >= 16 && !midi) ExoMessageAudioPlayer(context, file, listener)
                else LegacyMessageAudioPlayer(context, file, listener)

        // Otherwise speed and pitch need the file preprocessed, which only works for WAV.
        fun adjustsLive(midi: Boolean): Boolean = Build.VERSION.SDK_INT >= if (midi) 23 else 16

        fun isMidi(file: File): Boolean = runCatching {
            val head = ByteArray(12)
            val count = file.inputStream().use { it.read(head) }
            isMidiHeader(head, count)
        }.getOrDefault(false)

        internal fun isMidiHeader(head: ByteArray, count: Int): Boolean {
            fun at(offset: Int, tag: String) = count >= offset + tag.length &&
                    tag.indices.all { head[offset + it] == tag[it].code.toByte() }
            return at(0, "MThd") || at(0, "XMF_") || (at(0, "RIFF") && at(8, "RMID"))
        }
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
    private var speed = 1f
    private var pitch = 1f
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
    override fun play() {
        if (!prepared) return
        if (Build.VERSION.SDK_INT >= 23) applyParameters() else player.start()
    }
    override fun pause() { if (prepared) player.pause() }
    override fun seek(position: Int) { if (prepared) player.seekTo(position) }

    // API 14/15 instead play a PCM file processed with these parameters before opening the player.
    override fun parameters(speed: Float, pitch: Float) {
        this.speed = speed
        this.pitch = pitch
        // A non-zero speed starts a paused player, so a paused one only takes them on play().
        if (Build.VERSION.SDK_INT >= 23 && prepared && player.isPlaying) applyParameters()
    }

    @RequiresApi(23)
    private fun applyParameters() {
        runCatching { player.playbackParams = player.playbackParams.setSpeed(speed).setPitch(pitch) }
                .onFailure { Timber.w(it, "Cannot set playback speed $speed / pitch $pitch") }
        if (!player.isPlaying) player.start()
    }
    override fun release() = player.release()
}
