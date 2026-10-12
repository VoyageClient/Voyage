/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

@file:Suppress("DEPRECATION")

package im.vector.app.features.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.startForegroundCompat
import im.vector.app.core.platform.PendingIntentCompat
import im.vector.app.core.services.VectorAndroidService
import im.vector.app.features.attachments.editor.video.PlaybackSpeed
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AudioPlaybackService : VectorAndroidService() {
    @Inject lateinit var controller: AudioPlaybackController
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var hasFocus = false
    private var resumeOnGain = false
    private var lastNotificationState: AudioPlaybackController.State? = null
    private var lastSessionState: AudioPlaybackController.State? = null
    private var lastSessionUpdate = 0L
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                if (resumeOnGain) controller.resume()
                resumeOnGain = false
            }
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                resumeOnGain = change != AudioManager.AUDIOFOCUS_LOSS && controller.state.value?.playing == true
                hasFocus = false
                controller.pause()
            }
        }
    }
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) { resumeOnGain = false; controller.pause() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                    NotificationChannel(CHANNEL, getString(CommonStrings.audio_player_channel), NotificationManager.IMPORTANCE_LOW)
                            .apply { setSound(null, null) }
            )
        }
        mediaSession = MediaSessionCompat(this, "VoyageAudio", ComponentName(this, MediaButtonReceiver::class.java), null).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = this@AudioPlaybackService.controller.resume()
                override fun onPause() { resumeOnGain = false; this@AudioPlaybackService.controller.pause() }
                override fun onStop() = this@AudioPlaybackService.controller.stop()
                override fun onSkipToNext() = this@AudioPlaybackService.controller.skip(true)
                override fun onSkipToPrevious() = this@AudioPlaybackService.controller.skip(false)
                override fun onSeekTo(pos: Long) = this@AudioPlaybackService.controller.seek(pos.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
                override fun onSetPlaybackSpeed(speed: Float) = this@AudioPlaybackService.controller.setPlaybackSpeed(PlaybackSpeed(speed, this@AudioPlaybackService.controller.state.value?.changePitch ?: false))
            })
            setSessionActivity(contentIntent())
            isActive = true
        }
        if (!startForegroundCompat(NOTIFICATION_ID, notification(controller.state.value))) {
            controller.stop()
            myStopSelf()
            return
        }
        controller.requestAudioFocus = {
            if (!hasFocus) hasFocus = audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            hasFocus
        }
        ContextCompat.registerReceiver(this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        controller.onAudioServiceReady()
        scope.launch {
            controller.state.collect { state ->
                if (state == null) {
                    scope.launch {
                        do {
                            delay(250)
                        } while (controller.state.value == null && controller.hasPendingSelection)
                        if (controller.state.value == null) {
                            stopForegroundCompat()
                            myStopSelf()
                        }
                    }
                } else {
                    val visual = state.copy(position = 0)
                    if (visual != lastNotificationState) {
                        updateMetadata(state)
                        startForegroundCompat(NOTIFICATION_ID, notification(state))
                        lastNotificationState = visual
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (visual != lastSessionState || now - lastSessionUpdate >= 1000) {
                        updatePlaybackState(state)
                        lastSessionUpdate = now
                        lastSessionState = visual
                    }
                    if (!state.playing && !state.loading && hasFocus && !resumeOnGain) {
                        audioManager.abandonAudioFocus(focusListener)
                        hasFocus = false
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PLAY -> controller.resume()
            PAUSE -> { resumeOnGain = false; controller.pause() }
            NEXT -> controller.skip(true)
            PREVIOUS -> controller.skip(false)
            STOP -> controller.stop()
            Intent.ACTION_MEDIA_BUTTON -> MediaButtonReceiver.handleIntent(mediaSession, intent)
        }
        return START_NOT_STICKY
    }

    private fun updateMetadata(state: AudioPlaybackController.State) {
        mediaSession.setMetadata(MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, state.track.id)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, state.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, state.album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.duration.toLong())
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, state.art)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, state.art)
                .build())
    }

    private fun updatePlaybackState(state: AudioPlaybackController.State) {
        var actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_SEEK_TO
        if (state.hasNext) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        if (state.hasPrevious) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        if (state.adjustable) actions = actions or PlaybackStateCompat.ACTION_SET_PLAYBACK_SPEED
        val playback = when {
            state.error -> PlaybackStateCompat.STATE_ERROR
            state.loading -> PlaybackStateCompat.STATE_BUFFERING
            state.playing -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        mediaSession.setPlaybackState(PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(playback, state.position.toLong(), if (state.playing) state.speed else 0f)
                .apply { if (state.error) setErrorMessage(state.errorMessage(this@AudioPlaybackService)) }
                .build())
    }

    private fun notification(state: AudioPlaybackController.State?): Notification {
        val playing = state?.playing == true
        return NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(state?.title ?: getString(CommonStrings.audio_player_channel))
                .setContentText(if (state?.error == true) state.errorMessage(this) else state?.artist)
                .setLargeIcon(state?.art)
                .setContentIntent(contentIntent())
                .setDeleteIntent(action(STOP))
                .setOnlyAlertOnce(true)
                .setOngoing(playing)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_previous, getString(CommonStrings.audio_player_previous), action(PREVIOUS))
                .addAction(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                        getString(if (playing) CommonStrings.action_pause else CommonStrings.action_play), action(if (playing) PAUSE else PLAY))
                .addAction(android.R.drawable.ic_media_next, getString(CommonStrings.audio_player_next), action(NEXT))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(CommonStrings.audio_player_stop), action(STOP))
                .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(mediaSession.sessionToken)
                        .setShowActionsInCompactView(0, 1, 2).setShowCancelButton(true).setCancelButtonIntent(action(STOP)))
                .build()
    }

    private fun action(action: String): PendingIntent = PendingIntent.getService(this, 0,
            Intent(this, AudioPlaybackService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntentCompat.FLAG_IMMUTABLE)

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(this, 810,
            Intent().setClassName(packageName, "im.vector.application.features.Alias").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntentCompat.FLAG_IMMUTABLE)

    override fun onDestroy() {
        controller.requestAudioFocus = null
        controller.stop()
        scope.cancel()
        runCatching { unregisterReceiver(noisyReceiver) }
        audioManager.abandonAudioFocus(focusListener)
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "audio_playback"
        const val NOTIFICATION_ID = 810
        const val PLAY = "audio.play"
        const val PAUSE = "audio.pause"
        const val NEXT = "audio.next"
        const val PREVIOUS = "audio.previous"
        const val STOP = "audio.stop"
    }
}
