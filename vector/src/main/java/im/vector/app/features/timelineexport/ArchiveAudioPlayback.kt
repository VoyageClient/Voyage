/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import im.vector.app.features.home.room.detail.timeline.helper.AudioMessagePlaybackTracker
import timber.log.Timber
import java.io.File

@Suppress("DEPRECATION")
internal class ArchiveAudioPlayback(context: Context) {
    private val focus = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focusChanged = AudioManager.OnAudioFocusChangeListener { if (it < 0) pause() }
    private var wantsPlayback = false
    val tracker = AudioMessagePlaybackTracker()
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var id: String? = null
    private var ready = false
    private val update = object : Runnable {
        override fun run() {
            val current = player ?: return
            val key = id ?: return
            if (!ready || !current.isPlaying) return
            tracker.updatePlayingAtPlaybackTime(key, current.currentPosition, current.currentPosition.toFloat() / current.duration.coerceAtLeast(1))
            handler.postDelayed(this, 100)
        }
    }
    fun toggle(key: String, file: File) {
        if (id == key) {
            if (!ready) { wantsPlayback = !wantsPlayback; return }
            if (player?.isPlaying == true) pause() else if (requestFocus()) { wantsPlayback = true; player?.start(); tracker.startPlayback(key); handler.post(update) }
            return
        }
        release()
        if (!requestFocus()) return
        wantsPlayback = true
        id = key
        val next = MediaPlayer()
        player = next
        try {
            next.setAudioStreamType(AudioManager.STREAM_MUSIC)
            next.setDataSource(file.path)
            next.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                ready = true; if (wantsPlayback) { it.start(); tracker.startPlayback(key); handler.post(update) }
            }
            next.setOnCompletionListener { tracker.stopPlaybackOrRecorder(key); handler.removeCallbacks(update); it.seekTo(0); wantsPlayback = false; focus.abandonAudioFocus(focusChanged) }
            next.setOnErrorListener { _, what, extra ->
                tracker.onError(key, IllegalStateException("Archive audio error $what/$extra"))
                Timber.w("ExportTrace archive audio failed what=%d extra=%d", what, extra)
                release(); true
            }
            next.prepareAsync()
        } catch (failure: Exception) { tracker.onError(key, failure); release(); Timber.e(failure, "ExportTrace archive audio prepare failed") }
    }
    fun seek(key: String, percentage: Float) {
        if (id != key || !ready) return
        val current = player ?: return
        current.seekTo((current.duration * percentage.coerceIn(0f, 1f)).toInt())
        if (!current.isPlaying) tracker.updatePausedAtPlaybackTime(key, current.currentPosition, percentage)
    }
    private fun requestFocus() = focus.requestAudioFocus(focusChanged, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    fun pause() {
        wantsPlayback = false
        if (ready && player?.isPlaying == true) { player?.pause(); id?.let(tracker::pausePlayback) }
        handler.removeCallbacks(update)
    }
    fun release() {
        handler.removeCallbacks(update)
        player?.release(); player = null; ready = false; wantsPlayback = false
        focus.abandonAudioFocus(focusChanged)
        id?.let(tracker::stopPlaybackOrRecorder); id = null
    }
}
