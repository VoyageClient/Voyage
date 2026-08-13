/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.core.utils.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log

/**
 * A looping track of silence, held while the app is in the foreground on a Bluetooth output.
 *
 * An output with no active track is suspended, and waking it again over Bluetooth costs the best
 * part of a second while the A2DP link is re-established — heard as a stall on the first frames of
 * a video and an audible click. Players stop their own track whenever playback ends, so that price
 * is otherwise paid on every open and every loop around.
 *
 * Silence is only worth streaming to a Bluetooth sink: wired and speaker outputs wake instantly.
 */
object AudioRouteKeepAlive {

    private const val SAMPLE_RATE = 8000

    private var track: AudioTrack? = null
    private var holders = 0
    private var routeWatcher: Any? = null

    @Synchronized
    fun acquire(context: Context) {
        holders++
        watchRoute(context)
        syncToRoute(context)
    }

    @Synchronized
    fun release(context: Context) {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) unwatchRoute(context)
        syncToRoute(context)
    }

    @Synchronized
    private fun syncToRoute(context: Context) {
        val wanted = holders > 0 && isBluetoothOutput(context)
        if (wanted == (track != null)) return
        if (wanted) {
            track = runCatching { createSilentTrack() }
                    .onFailure { Log.w(AudioRouteKeepAlive::class.java.name, "Cannot hold the audio route open", it) }
                    .getOrNull()
        } else {
            track?.let { active ->
                runCatching { active.stop() }
                active.release()
            }
            track = null
        }
    }

    private fun isBluetoothOutput(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothA2dpOn
        }
    }

    /** Below API 23 there is no device-change callback, so the route is only read as holders come and go. */
    private fun watchRoute(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || routeWatcher != null) return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val appContext = context.applicationContext
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = syncToRoute(appContext)
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = syncToRoute(appContext)
        }
        routeWatcher = callback
        audioManager.registerAudioDeviceCallback(callback, null)
    }

    private fun unwatchRoute(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val callback = routeWatcher as? AudioDeviceCallback ?: return
        routeWatcher = null
        (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.unregisterAudioDeviceCallback(callback)
    }

    private fun createSilentTrack(): AudioTrack {
        val frames = SAMPLE_RATE // one second
        @Suppress("DEPRECATION")
        return AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                frames * 2,
                AudioTrack.MODE_STATIC
        ).apply {
            write(ShortArray(frames), 0, frames)
            setLoopPoints(0, frames, -1)
            play()
        }
    }
}
