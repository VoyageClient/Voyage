/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

// The 2.x line is deprecated wholesale in favour of media3, whose floor is above the one this fork
// keeps to — same reason the attachment viewer is on it.
@file:Suppress("DEPRECATION")

package im.vector.app.features.attachments.editor.video

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import androidx.annotation.RequiresApi
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SeekParameters
import timber.log.Timber

/**
 * The video editor's preview, which has to end where the export will.
 *
 * Positions are always the source's own, in microseconds, whatever the implementation does underneath.
 */
interface EditorPreviewPlayer {

    val isPlaying: Boolean

    /** Where playback is in the source, in microseconds. */
    val positionUs: Long

    val audioSessionId: Int

    /**
     * Whether the player itself stops at the range's end. Where it does not, the caller has to watch
     * the clock and stop playback by hand — which it can only do late, and never for the sound that
     * has already left for the speaker.
     */
    val enforcesRange: Boolean

    fun open(context: Context, uri: Uri, surface: Surface, startPositionUs: Long, playWhenReady: Boolean, listener: Listener)

    /** Bounds playback to exactly the clip the export will produce. */
    fun setPlaybackRange(startUs: Long, endUs: Long)

    /** Lifts the bounds, so any frame of the source can be shown — scrubbing, per-frame stepping. */
    fun clearPlaybackRange()

    fun play()

    fun pause()

    /**
     * @param precise land on exactly this frame, which costs a decode from the previous keyframe —
     * hundreds of milliseconds on a long clip. A drag in progress wants the keyframe instead: it keeps
     * up with the finger, and the exact frame follows when the finger lifts.
     */
    fun seekTo(us: Long, precise: Boolean = true)

    /**
     * Whether seeks queue against each other rather than piling up: one runs, the newest target waits.
     * Where they do not, the caller has to space them out itself.
     */
    val coalescesSeeks: Boolean

    fun setSpeed(speed: Float, changePitch: Boolean)

    fun setVolume(scalar: Float)

    fun release()

    interface Listener {
        fun onReady()

        /** Playback reached the end of the range. */
        fun onEnded()

        fun onError(message: String)
    }

    companion object {
        fun create(): EditorPreviewPlayer =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) ExoEditorPreviewPlayer() else LegacyEditorPreviewPlayer()
    }
}

/** A seek that renders nothing — the same frame, a stall — must not wedge the queue behind it. */
private const val SEEK_STALL_MS = 400L

/**
 * The trim is a property of the media source here, so playback simply ends at the cut: the picture and
 * the sound stop together on the last kept frame, with nothing for the caller to time.
 */
@RequiresApi(Build.VERSION_CODES.JELLY_BEAN)
private class ExoEditorPreviewPlayer : EditorPreviewPlayer {

    private var player: ExoPlayer? = null
    private var context: Context? = null
    private var uri: Uri? = null
    private var listener: EditorPreviewPlayer.Listener? = null

    /** Where the current media item starts in the source: positions are relative to it. */
    private var rangeStartUs = 0L
    private var rangeEndUs = 0L
    private var ranged = false
    private var reportedReady = false

    /** When the seek being decoded was issued, or zero when none is; the newest target waits behind it. */
    private var seekInFlightAt = 0L
    private var pendingSeekUs: Long? = null
    private var pendingPrecise = true
    private var preciseSeeks = true

    // Whether playback is *meant* to be running, not whether frames are moving this instant: a press is
    // followed by buffering, and reading the live state there left the play/pause button showing "play"
    // on a clip that had just been restarted.
    override val isPlaying: Boolean
        get() = player?.let { it.playWhenReady && it.playbackState != Player.STATE_ENDED && it.playbackState != Player.STATE_IDLE } == true

    override val positionUs: Long get() = rangeStartUs + (player?.currentPosition ?: 0L) * 1000L

    override val audioSessionId: Int get() = player?.audioSessionId ?: 0

    override val enforcesRange = true

    override val coalescesSeeks = true

    override fun open(
            context: Context,
            uri: Uri,
            surface: Surface,
            startPositionUs: Long,
            playWhenReady: Boolean,
            listener: EditorPreviewPlayer.Listener,
    ) {
        release()
        this.context = context
        this.uri = uri
        this.listener = listener
        reportedReady = false
        val exo = ExoPlayer.Builder(context).build()
        player = exo
        exo.setVideoSurface(surface)
        // Frame-exact, for a per-frame view whose whole point is landing on one.
        exo.setSeekParameters(SeekParameters.EXACT)
        preciseSeeks = true
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> if (!reportedReady) {
                        reportedReady = true
                        listener.onReady()
                    }
                    Player.STATE_ENDED -> listener.onEnded()
                    else -> Unit
                }
            }

            override fun onRenderedFirstFrame() {
                // A seek renders a frame, which is the only honest signal that it finished.
                seekInFlightAt = 0
                pendingSeekUs?.let { issueSeek(it) }
            }

            override fun onPlayerError(error: PlaybackException) {
                Timber.w(error, "VideoEditor: preview failed")
                listener.onError(error.errorCodeName)
            }
        })
        setItem(startUs = 0, endUs = 0, ranged = false, seekToUs = startPositionUs, playWhenReady = playWhenReady)
    }

    override fun setPlaybackRange(startUs: Long, endUs: Long) {
        if (ranged && startUs == rangeStartUs && endUs == rangeEndUs) return
        // Held where it is: re-clipping is a new media item, and the frame on screen should not move.
        setItem(startUs, endUs, ranged = true, seekToUs = positionUs.coerceIn(startUs, endUs), playWhenReady = isPlaying)
    }

    override fun clearPlaybackRange() {
        if (!ranged) return
        setItem(startUs = 0, endUs = 0, ranged = false, seekToUs = positionUs, playWhenReady = isPlaying)
    }

    private fun setItem(startUs: Long, endUs: Long, ranged: Boolean, seekToUs: Long, playWhenReady: Boolean) {
        val exo = player ?: return
        val source = uri ?: return
        val item = MediaItem.Builder().setUri(source).let { builder ->
            if (!ranged || endUs <= startUs) {
                builder
            } else {
                builder.setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(startUs / 1000)
                                .setEndPositionMs(endUs / 1000)
                                .build()
                )
            }
        }.build()
        seekInFlightAt = 0
        pendingSeekUs = null
        rangeStartUs = if (ranged) startUs else 0L
        rangeEndUs = if (ranged) endUs else 0L
        this.ranged = ranged
        exo.setMediaItem(item, ((seekToUs - rangeStartUs) / 1000).coerceAtLeast(0))
        exo.playWhenReady = playWhenReady
        exo.prepare()
    }

    override fun play() {
        player?.play()
    }

    override fun pause() {
        player?.pause()
    }

    /**
     * Exact seeks decode from the last sync frame, and a new one abandons whatever the last was still
     * decoding — so a dragged trim handle, which asks for a seek every few milliseconds, rendered almost
     * nothing until the finger stopped. One seek runs at a time and the newest target waits its turn,
     * which shows every frame the decoder can manage and still lands on the one the finger ended on.
     */
    override fun seekTo(us: Long, precise: Boolean) {
        if (player == null) return
        pendingPrecise = precise
        if (seekInFlightAt != 0L && SystemClock.uptimeMillis() - seekInFlightAt < SEEK_STALL_MS) {
            pendingSeekUs = us
            return
        }
        issueSeek(us)
    }

    private fun issueSeek(us: Long) {
        val exo = player ?: return
        pendingSeekUs = null
        // A seek onto the frame already shown renders nothing, so nothing would report it finished.
        if (exo.currentPosition == msIn(us)) return
        if (preciseSeeks != pendingPrecise) {
            preciseSeeks = pendingPrecise
            exo.setSeekParameters(if (preciseSeeks) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        }
        seekInFlightAt = SystemClock.uptimeMillis()
        exo.seekTo(msIn(us))
    }

    private fun msIn(us: Long) = ((us - rangeStartUs) / 1000).coerceAtLeast(0)

    override fun setSpeed(speed: Float, changePitch: Boolean) {
        // Tape behaviour is pitch riding along with the speed; the alternative holds it.
        player?.playbackParameters = PlaybackParameters(speed, if (changePitch) speed else 1f)
    }

    override fun setVolume(scalar: Float) {
        player?.volume = scalar
    }

    override fun release() {
        player?.release()
        player = null
        listener = null
        ranged = false
        rangeStartUs = 0
        rangeEndUs = 0
        seekInFlightAt = 0
        pendingSeekUs = null
    }
}

/**
 * For the versions ExoPlayer does not reach. The range is advisory here — the caller watches the clock
 * — which is why the preview on these devices can still run a little past the cut.
 */
private class LegacyEditorPreviewPlayer : EditorPreviewPlayer {

    private var player: MediaPlayer? = null

    override val isPlaying: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    override val positionUs: Long get() = runCatching { (player?.currentPosition ?: 0) * 1000L }.getOrDefault(0L)

    override val audioSessionId: Int get() = runCatching { player?.audioSessionId ?: 0 }.getOrDefault(0)

    override val enforcesRange = false

    override val coalescesSeeks = false

    override fun open(
            context: Context,
            uri: Uri,
            surface: Surface,
            startPositionUs: Long,
            playWhenReady: Boolean,
            listener: EditorPreviewPlayer.Listener,
    ) {
        release()
        player = MediaPlayer().apply {
            setSurface(surface)
            setOnPreparedListener {
                seekTo(startPositionUs)
                listener.onReady()
                if (playWhenReady) runCatching { start() }
            }
            setOnCompletionListener { listener.onEnded() }
            setOnErrorListener { _, what, extra ->
                listener.onError("$what/$extra")
                true
            }
            runCatching {
                setDataSource(context, uri)
                prepareAsync()
            }.onFailure { listener.onError(it.message.orEmpty()) }
        }
    }

    override fun setPlaybackRange(startUs: Long, endUs: Long) = Unit

    override fun clearPlaybackRange() = Unit

    override fun play() {
        runCatching { player?.start() }
    }

    override fun pause() {
        runCatching { player?.takeIf { it.isPlaying }?.pause() }
    }

    override fun seekTo(us: Long, precise: Boolean) {
        runCatching {
            // Plain seekTo() lands on the previous sync frame, which on a sparsely keyframed video can
            // be seconds earlier — that reads as the playhead jumping backwards.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                player?.seekTo(us / 1000, if (precise) MediaPlayer.SEEK_CLOSEST else MediaPlayer.SEEK_CLOSEST_SYNC)
            } else {
                player?.seekTo((us / 1000).toInt())
            }
        }
    }

    override fun setSpeed(speed: Float, changePitch: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val player = player ?: return
        runCatching {
            player.playbackParams = player.playbackParams.setSpeed(speed).setPitch(if (changePitch) speed else 1f)
        }.onFailure { Timber.w(it, "VideoEditor: cannot preview speed $speed") }
    }

    override fun setVolume(scalar: Float) {
        runCatching { player?.setVolume(scalar, scalar) }
    }

    override fun release() {
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
    }
}
