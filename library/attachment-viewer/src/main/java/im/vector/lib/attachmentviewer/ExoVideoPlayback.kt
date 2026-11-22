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
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.RenderersFactory
import com.google.android.exoplayer2.SeekParameters
import com.google.android.exoplayer2.analytics.AnalyticsListener
import com.google.android.exoplayer2.audio.AudioSink
import com.google.android.exoplayer2.audio.ForwardingAudioSink
import com.google.android.exoplayer2.audio.MediaCodecAudioRenderer
import com.google.android.exoplayer2.decoder.DecoderReuseEvaluation
import com.google.android.exoplayer2.video.VideoSize
import java.io.File
import java.nio.ByteBuffer

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
    private var playbackListener: Player.Listener? = null
    private var analyticsListener: AnalyticsListener? = null
    private var currentSource: String? = null
    @Volatile private var openStartedAtMs = 0L
    @Volatile private var sourceChangePending = false
    private val decoderCapabilities = VideoDecoderCapabilities { name, mime ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            1
        } else {
            val codec = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { it.name == name }
            val instances = codec?.getCapabilitiesForType(mime)?.maxSupportedInstances ?: 1
            AttachmentViewerDebug.i { "stage=video-capability decoder=$name mime=$mime maxInstances=$instances" }
            instances
        }
    }
    override val canRetainPreparedSource: Boolean get() = decoderCapabilities.canRetainSource

    override val isPlaying: Boolean get() = player?.isPlaying == true

    override val positionMs: Int get() = player?.currentPosition?.toInt() ?: 0

    override val durationMs: Int
        get() = player?.duration?.takeIf { it != C.TIME_UNSET }?.toInt() ?: 0

    override val audioSessionId: Int get() = player?.audioSessionId ?: 0

    override fun open(context: Context, source: String, surface: Surface?, looping: Boolean, startPositionMs: Int, listener: VideoPlayback.Listener) {
        val started = SystemClock.elapsedRealtime()
        openStartedAtMs = started
        AttachmentViewerDebug.i { "stage=exo-create" }
        reportedReady = false
        val reused = player != null
        val exo = player ?: createPlayer(context.applicationContext)
        clearListeners(exo)
        AttachmentViewerDebug.i { "stage=exo-created reused=$reused elapsedMs=${SystemClock.elapsedRealtime() - started}" }
        player = exo
        exo.playbackParameters = PlaybackParameters.DEFAULT
        exo.volume = 1f
        exo.setSeekParameters(SeekParameters.DEFAULT)
        exo.setVideoSurface(surface)
        exo.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        analyticsListener = object : AnalyticsListener {
            override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
                val reportedDelayMs = System.currentTimeMillis() - playoutStartSystemTimeMs
                AttachmentViewerDebug.i { "stage=audio-advancing reportedDelayMs=$reportedDelayMs elapsedMs=${SystemClock.elapsedRealtime() - started}" }
            }

            override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
                AttachmentViewerDebug.i { "stage=audio-session session=$audioSessionId elapsedMs=${SystemClock.elapsedRealtime() - started}" }
            }

            override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                AttachmentViewerDebug.i { "stage=audio-underrun bufferMs=$bufferSizeMs lastFeedMs=$elapsedSinceLastFeedMs elapsedMs=${SystemClock.elapsedRealtime() - started}" }
            }

            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                AttachmentViewerDebug.i { "stage=audio-decoder durationMs=$initializationDurationMs elapsedMs=${SystemClock.elapsedRealtime() - started}" }
            }

            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                decoderCapabilities.onDecoderInitialized(decoderName)
                AttachmentViewerDebug.i { "stage=video-decoder durationMs=$initializationDurationMs retainSource=$canRetainPreparedSource elapsedMs=${SystemClock.elapsedRealtime() - started}" }
            }

            override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                decoderCapabilities.onFormatChanged(format.sampleMimeType)
                AttachmentViewerDebug.i { "stage=video-format mime=${format.sampleMimeType} retainSource=$canRetainPreparedSource elapsedMs=${SystemClock.elapsedRealtime() - started}" }
            }
        }.also { exo.addAnalyticsListener(it) }
        playbackListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                AttachmentViewerDebug.i { "stage=exo-state state=$playbackState elapsedMs=${SystemClock.elapsedRealtime() - started}" }
                when (playbackState) {
                    Player.STATE_READY -> {
                        sourceChangePending = false
                        if (!reportedReady) {
                            reportedReady = true
                            listener.onReady()
                        }
                    }
                    Player.STATE_ENDED -> listener.onCompletion()
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                listener.onVideoSizeChanged(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio, videoSize.unappliedRotationDegrees)
            }

            override fun onRenderedFirstFrame() {
                AttachmentViewerDebug.i { "stage=exo-first-frame elapsedMs=${SystemClock.elapsedRealtime() - started}" }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                AttachmentViewerDebug.i { "stage=exo-playing playing=$isPlaying elapsedMs=${SystemClock.elapsedRealtime() - started}" }
            }

            override fun onPlayerError(error: PlaybackException) {
                sourceChangePending = false
                listener.onError()
            }
        }.also { exo.addListener(it) }
        if (currentSource == source && exo.playbackState == Player.STATE_READY &&
                kotlin.math.abs(exo.currentPosition - startPositionMs) < 20L) {
            val size = exo.videoSize
            listener.onVideoSizeChanged(size.width, size.height, size.pixelWidthHeightRatio, size.unappliedRotationDegrees)
            reportedReady = true
            listener.onReady()
            AttachmentViewerDebug.i { "stage=exo-resume-prepared elapsedMs=${SystemClock.elapsedRealtime() - started}" }
            return
        }
        currentSource = source
        decoderCapabilities.clear()
        sourceChangePending = true
        exo.setMediaItem(MediaItem.fromUri(source.toPlayableUri()), startPositionMs.coerceAtLeast(0).toLong())
        exo.playWhenReady = false
        exo.prepare()
    }

    private fun createPlayer(context: Context): ExoPlayer {
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean, enableOffload: Boolean): AudioSink? {
                val sink = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams, enableOffload) ?: return null
                return object : ForwardingAudioSink(sink) {
                    private var firstBuffer = true

                    private inline fun logOutput(event: () -> String) {
                        AttachmentViewerDebug.i { "stage=audio-output event=${event()} elapsedMs=${SystemClock.elapsedRealtime() - openStartedAtMs}" }
                    }

                    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
                        logOutput { "configure rate=${inputFormat.sampleRate} channels=${inputFormat.channelCount} bufferBytes=$specifiedBufferSize" }
                        firstBuffer = true
                        super.configure(inputFormat, specifiedBufferSize, outputChannels)
                    }

                    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
                        val started = if (firstBuffer && AttachmentViewerDebug.enabled) SystemClock.elapsedRealtime() else 0L
                        val accepted = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
                        if (firstBuffer && accepted) {
                            firstBuffer = false
                            logOutput { "first-buffer positionMs=${presentationTimeUs / 1000} writeMs=${SystemClock.elapsedRealtime() - started}" }
                        }
                        return accepted
                    }

                    override fun play() {
                        logOutput { "play" }
                        super.play()
                    }

                    override fun pause() {
                        logOutput { "pause" }
                        super.pause()
                    }

                    override fun flush() {
                        val caller = if (AttachmentViewerDebug.enabled) {
                            Throwable().stackTrace.firstOrNull { it.className.endsWith("MediaCodecAudioRenderer") }?.methodName
                        } else null
                        firstBuffer = true
                        // Source replacement disables the renderer before its seek-retention option applies.
                        if (sourceChangePending && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                            logOutput { "flush-retain-track caller=$caller sourceChange=true" }
                            super.experimentalFlushWithoutAudioTrackRelease()
                        } else {
                            logOutput { "flush caller=$caller" }
                            super.flush()
                        }
                    }

                    override fun experimentalFlushWithoutAudioTrackRelease() {
                        logOutput { "flush-retain-track" }
                        firstBuffer = true
                        super.experimentalFlushWithoutAudioTrackRelease()
                    }

                    override fun reset() {
                        logOutput { "reset" }
                        firstBuffer = true
                        super.reset()
                    }
                }
            }
        }
        val factory = RenderersFactory { handler, video, audio, text, metadata ->
            renderers.createRenderers(handler, video, audio, text, metadata).also { created ->
                created.filterIsInstance<MediaCodecAudioRenderer>().forEach {
                    // AudioTrack.flush() is unreliable before API 25; those devices need a new track.
                    it.experimentalSetEnableKeepAudioTrackOnSeek(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1)
                }
            }
        }
        return ExoPlayer.Builder(context, factory).build()
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
        sourceChangePending = false
        player?.let { clearListeners(it) }
        player?.release()
        player = null
        currentSource = null
        decoderCapabilities.clear()
    }

    override fun prepareForReuse(): Boolean {
        val exo = player ?: return false
        clearListeners(exo)
        exo.pause()
        exo.clearVideoSurface()
        reportedReady = false
        return true
    }

    private fun clearListeners(exo: ExoPlayer) {
        playbackListener?.let { exo.removeListener(it) }
        analyticsListener?.let { exo.removeAnalyticsListener(it) }
        playbackListener = null
        analyticsListener = null
    }
}

internal class VideoDecoderCapabilities(private val queryMaxInstances: (String, String) -> Int) {
    private var decoderName: String? = null
    private var mimeType: String? = null
    var canRetainSource = false
        private set

    fun onDecoderInitialized(name: String) {
        decoderName = name
        update()
    }

    fun onFormatChanged(mime: String?) {
        mimeType = mime
        update()
    }

    fun clear() {
        decoderName = null
        mimeType = null
        canRetainSource = false
    }

    private fun update() {
        canRetainSource = false
        val name = decoderName ?: return
        val mime = mimeType ?: return
        canRetainSource = runCatching { queryMaxInstances(name, mime) >= 2 }
                .onFailure { AttachmentViewerDebug.w(it) { "stage=video-capability-failed" } }
                .getOrDefault(false)
    }
}
