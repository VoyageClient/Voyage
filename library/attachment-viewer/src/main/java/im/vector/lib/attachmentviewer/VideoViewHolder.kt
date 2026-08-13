/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.attachmentviewer

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.animation.doOnEnd
import androidx.core.view.isVisible
import im.vector.lib.attachmentviewer.databinding.ItemVideoAttachmentBinding
import im.vector.lib.core.utils.timer.CountUpTimer
import java.io.File
import java.lang.ref.WeakReference

/**
 * Video playback uses a TextureView backed by a MediaPlayer rather than the SurfaceView-based
 * VideoView. Pinch / pan / double-tap gestures apply view-tree transforms (scaleX/Y +
 * translationX/Y), which TextureView honours pixel-for-pixel, whereas SurfaceView's surface is
 * composed at its anchored position and ignores those transforms.
 */
class VideoViewHolder constructor(itemView: View) :
        BaseViewHolder(itemView) {

    private companion object {
        const val END_SEEK_WINDOW_MS = 250
    }

    private var isSelected = false
    private var mVideoPath: String? = null
    private var countUpTimer: CountUpTimer? = null
    private var progress: Int = 0
    private var wasPaused = false

    /** True when the pause came from playback reaching the end, not from the user. */
    private var endedNaturally = false

    private var lastReportedPositionMs = 0

    /** Timestamp of the final frame, probed in the background once the player is prepared. */
    @Volatile private var endFrameMs = -1

    private var mediaPlayer: MediaPlayer? = null
    private var surface: Surface? = null
    private var isPrepared = false
    private var waitingForFirstFrame = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var playbackSpeed = 1f
    private var pitchFollowsSpeed = true

    var eventListener: WeakReference<AttachmentEventListener>? = null

    /** With looping the player wraps around by itself and completion never fires. */
    var loopEnabled = false

    val views = ItemVideoAttachmentBinding.bind(itemView)

    internal val target = DefaultVideoLoaderTarget(this, views.videoThumbnailImage)

    private val seekRippleDrawable = VideoForwardDrawable(itemView.context)

    init {
        views.videoSeekRipple.background = seekRippleDrawable
        attachZoomGestures()
        views.videoView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                surface = Surface(texture)
                mediaPlayer?.setSurface(surface)
                if (mediaPlayer == null && mVideoPath != null && isSelected) {
                    startPlaying()
                }
                applyAspectMatrix()
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                applyAspectMatrix()
            }

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                releasePlayer()
                surface?.release()
                surface = null
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                if (waitingForFirstFrame) {
                    waitingForFirstFrame = false
                    views.videoView.alpha = 1f
                    views.videoThumbnailImage.isVisible = false
                }
            }
        }
    }

    /** Aspect-fit transform of the raw video frame to view bounds. Recomputed when either changes. */
    private val baseMatrix = Matrix()

    /** User-applied pinch + pan, composed on top of [baseMatrix]. */
    private val userMatrix = Matrix()

    /** The temporary matrix we push into TextureView.setTransform every frame. */
    private val drawMatrix = Matrix()

    private val matrixValues = FloatArray(9)

    /** True while a pinch is in progress — used to let rubber-band scale below 1 then spring back. */
    private var pinchActive = false

    private var springBackAnimator: ValueAnimator? = null

    private fun applyDrawMatrix() {
        drawMatrix.set(baseMatrix)
        drawMatrix.postConcat(userMatrix)
        views.videoView.setTransform(drawMatrix)
        views.videoView.invalidate()
    }

    private fun userScale(): Float {
        userMatrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    /** Push the current pan back inside the legal range for the current scale. */
    private fun clampUserTranslation() {
        userMatrix.getValues(matrixValues)
        val scale = matrixValues[Matrix.MSCALE_X]
        val tx = matrixValues[Matrix.MTRANS_X]
        val ty = matrixValues[Matrix.MTRANS_Y]
        val vw = views.videoView.width.toFloat()
        val vh = views.videoView.height.toFloat()
        if (scale <= 1f) {
            // Below 1x (during rubber-band) we want the view centered.
            matrixValues[Matrix.MTRANS_X] = vw * (1f - scale) / 2f
            matrixValues[Matrix.MTRANS_Y] = vh * (1f - scale) / 2f
            userMatrix.setValues(matrixValues)
            return
        }
        val maxTx = 0f
        val minTx = vw * (1f - scale)
        val maxTy = 0f
        val minTy = vh * (1f - scale)
        matrixValues[Matrix.MTRANS_X] = tx.coerceIn(minTx, maxTx)
        matrixValues[Matrix.MTRANS_Y] = ty.coerceIn(minTy, maxTy)
        userMatrix.setValues(matrixValues)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachZoomGestures() {
        val view = views.videoView

        val scaleDetector = ScaleGestureDetector(view.context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinchActive = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                val nextScale = userScale() * factor
                // While pinching let the user pull below 1x for rubber-band feel; clamp the
                // upper bound only. The release handler springs scale<1 back to 1.
                val clamped = nextScale.coerceAtMost(6f)
                val applied = clamped / userScale()
                userMatrix.postScale(applied, applied, detector.focusX, detector.focusY)
                clampUserTranslation()
                applyDrawMatrix()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                pinchActive = false
                if (userScale() < 1f) {
                    animateSpringBack()
                }
            }
        }).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                isQuickScaleEnabled = true
            }
        }

        var lastX = 0f
        var lastY = 0f
        var panActive = false

        view.setOnTouchListener { v, event ->
            val zoomed = userScale() > 1.0008f
            // Only block the parent ViewPager from intercepting when we are actually using a
            // gesture (active pinch, or a pan while zoomed). Otherwise leave intercept open so
            // horizontal swipes between media still work.
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    panActive = zoomed
                    if (panActive) v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    panActive = false
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                    panActive = zoomed
                }
                MotionEvent.ACTION_MOVE -> {
                    if (panActive && event.pointerCount == 1 && zoomed) {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        userMatrix.postTranslate(event.x - lastX, event.y - lastY)
                        clampUserTranslation()
                        applyDrawMatrix()
                        lastX = event.x
                        lastY = event.y
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    panActive = false
                    if (!zoomed && !pinchActive) v.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            scaleDetector.onTouchEvent(event)
            // Consume only when we're handling the gesture; otherwise pass through so the pager
            // sees swipes.
            zoomed || pinchActive || event.pointerCount > 1 || event.actionMasked == MotionEvent.ACTION_DOWN
        }
    }

    /**
     * Letterbox the video inside the TextureView so it preserves aspect ratio. TextureView
     * stretches its surface to fill the view bounds by default; we apply a transform matrix
     * that scales the surface to centerInside-fit the video frame.
     */
    private fun applyAspectMatrix() {
        val view = views.videoView
        val vw = view.width.toFloat()
        val vh = view.height.toFloat()
        if (vw <= 0f || vh <= 0f || videoWidth <= 0 || videoHeight <= 0) return
        val scale = minOf(vw / videoWidth, vh / videoHeight)
        val drawnW = videoWidth * scale
        val drawnH = videoHeight * scale
        seekRippleDrawable.contentRect = Rect(
                ((vw - drawnW) / 2f).toInt(),
                ((vh - drawnH) / 2f).toInt(),
                ((vw + drawnW) / 2f).toInt(),
                ((vh + drawnH) / 2f).toInt()
        )
        baseMatrix.reset()
        baseMatrix.setScale(drawnW / vw, drawnH / vh)
        baseMatrix.postTranslate((vw - drawnW) / 2f, (vh - drawnH) / 2f)
        applyDrawMatrix()
    }

    private fun resetZoom() {
        springBackAnimator?.cancel()
        springBackAnimator = null
        userMatrix.reset()
        applyDrawMatrix()
    }

    /**
     * Smoothly interpolate the current user-matrix entries back to identity. Cheaper and
     * smoother than a hard `userMatrix.reset()` snap on pinch release, which is what made
     * the rubber-band feel rigid.
     */
    private fun animateSpringBack() {
        val from = FloatArray(9)
        userMatrix.getValues(from)
        val to = FloatArray(9).also { Matrix().getValues(it) }
        springBackAnimator?.cancel()
        springBackAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            val frame = FloatArray(9)
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                for (i in 0 until 9) {
                    frame[i] = from[i] + (to[i] - from[i]) * t
                }
                userMatrix.setValues(frame)
                applyDrawMatrix()
            }
            doOnEnd { springBackAnimator = null }
            start()
        }
    }

    internal fun isScaled(): Boolean = userScale() > 1.0008f || pinchActive

    override fun onRecycled() {
        super.onRecycled()
        springBackAnimator?.cancel()
        springBackAnimator = null
        stopTimer()
        releasePlayer()
        mVideoPath = null
    }

    fun videoReady(file: File) {
        mVideoPath = file.path
        if (isSelected) {
            startPlaying()
        }
    }

    fun videoReady(path: String) {
        mVideoPath = path
        if (isSelected) {
            startPlaying()
        }
    }

    fun videoFileLoadError() {
    }

    override fun entersBackground() {
        val player = mediaPlayer ?: return
        // Saved even when paused: the surface teardown that follows releases the player, and a
        // position remembered only for playing videos would restart a paused one from the top.
        runCatching {
            progress = player.currentPosition
            if (player.isPlaying) {
                stopTimer()
                player.pause()
            }
        }
    }

    override fun entersForeground() {
        onSelected(isSelected)
    }

    override fun onSelected(selected: Boolean) {
        if (!selected) {
            progress = mediaPlayer?.takeIf { it.isPlaying }?.currentPosition ?: 0
            releasePlayer()
            resetZoom()
            // A speed belongs to the video it was chosen for, so swiping away puts it back.
            playbackSpeed = 1f
            pitchFollowsSpeed = true
        } else if (mVideoPath != null) {
            startPlaying()
        }
        isSelected = true
    }

    private fun startPlaying() {
        // Page selection and every entersForeground() both land here — twice for one open transition —
        // and rebuilding the player would replay the opening second of the video.
        mediaPlayer?.let { player ->
            if (isPrepared && !wasPaused && !player.isPlaying) {
                player.start()
                ensureTickTimer()
            }
            return
        }

        views.videoLoaderProgress.isVisible = false
        views.videoView.alpha = 0f
        views.videoView.isVisible = true
        // Coming back from the background rebuilds the player; without the poster the transparent
        // TextureView reads as a black flash until the first frame lands.
        views.videoThumbnailImage.isVisible = true
        waitingForFirstFrame = true

        // Don't try to set up the MediaPlayer until we have a surface to render into; the
        // SurfaceTextureListener picks it up once the surface becomes available, gated on
        // `isSelected && mVideoPath != null`.
        val activeSurface = surface ?: return

        releasePlayer()
        try {
            val player = MediaPlayer().apply {
                setSurface(activeSurface)
                val path = mVideoPath ?: return
                if (path.startsWith("content://")) {
                    setDataSource(itemView.context, android.net.Uri.parse(path))
                } else {
                    setDataSource(path)
                }
                isLooping = loopEnabled
                setOnVideoSizeChangedListener { _, width, height ->
                    this@VideoViewHolder.videoWidth = width
                    this@VideoViewHolder.videoHeight = height
                    applyAspectMatrix()
                }
                setOnPreparedListener { mp ->
                    isPrepared = true
                    applyAspectMatrix()
                    ensureTickTimer()
                    if (endFrameMs < 0) {
                        mVideoPath?.let { source ->
                            Thread({ endFrameMs = VideoLastFrame.probeMs(itemView.context, source) }, "video-end-probe").start()
                        }
                    }
                    // The player is new — a chosen speed only lives in the holder.
                    if (playbackSpeed != 1f || !pitchFollowsSpeed) applyPlaybackSpeed()
                    if (progress > 0) {
                        seekTo(mp, progress)
                    }
                    if (!wasPaused) {
                        mp.start()
                    }
                }
                setOnCompletionListener { mp ->
                    stopTimer()
                    wasPaused = true
                    endedNaturally = true
                    progress = 0
                    // The timer is what tells the overlay whether we're playing, so without a last
                    // report of our own its button stays a pause and only ever sends PauseVideo.
                    val duration = runCatching { mp.duration }.getOrDefault(0)
                    eventListener?.get()?.onEvent(AttachmentEvents.VideoEvent(false, duration, duration))
                }
                prepareAsync()
            }
            mediaPlayer = player
        } catch (failure: Throwable) {
            Log.v(VideoViewHolder::class.java.name, "Failed to start video", failure)
            releasePlayer()
        }
    }

    private fun applyPlaybackSpeed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val player = mediaPlayer?.takeIf { isPrepared } ?: return
        runCatching {
            val playing = player.isPlaying
            player.playbackParams = player.playbackParams
                    .setSpeed(playbackSpeed)
                    // Tape behaviour is pitch riding along with the speed; the alternative holds it.
                    .setPitch(if (pitchFollowsSpeed) playbackSpeed else 1f)
            // Setting the parameters starts a paused player, which would run off the frame on show.
            if (!playing) player.pause()
        }.onFailure {
            Log.v(VideoViewHolder::class.java.name, "Cannot play at speed $playbackSpeed", it)
        }
    }

    private fun releasePlayer() {
        // Stop the tick timer FIRST so its captured MediaPlayer reference isn't accessed
        // after release.
        stopTimer()
        isPrepared = false
        lastReportedPositionMs = 0
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (_: IllegalStateException) {
                // already stopped/released
            }
            it.release()
        }
        mediaPlayer = null
    }

    private fun stopTimer() {
        countUpTimer?.stop()
        countUpTimer = null
    }

    private fun ensureTickTimer() {
        if (countUpTimer != null) return
        countUpTimer = CountUpTimer(intervalInMs = 100).also {
            it.tickListener = CountUpTimer.TickListener {
                val active = mediaPlayer ?: return@TickListener
                try {
                    val duration = active.duration
                    val raw = active.currentPosition
                    val isPlaying = active.isPlaying
                    // An audio sink spinning up (Bluetooth especially) briefly walks the reported
                    // position backwards; steady playback never does, so hold through small
                    // regressions rather than letting the scrubber jitter.
                    val pos = if (isPlaying && raw < lastReportedPositionMs && lastReportedPositionMs - raw < 1500) {
                        lastReportedPositionMs
                    } else {
                        raw
                    }
                    lastReportedPositionMs = pos
                    eventListener?.get()?.onEvent(AttachmentEvents.VideoEvent(isPlaying, pos, duration))
                } catch (_: IllegalStateException) {
                    stopTimer()
                }
            }
            it.start()
        }
    }

    private fun seekTo(player: MediaPlayer, positionMs: Int) {
        var target = positionMs
        val duration = runCatching { player.duration }.getOrDefault(0)
        if (duration > 0 && positionMs > duration - END_SEEK_WINDOW_MS) {
            // The duration usually sits past the last frame, so a precise seek to it finds
            // nothing to render and the picture hangs. Aim at the final frame's own probed
            // timestamp instead, and take the completed-playback state while there.
            target = endFrameMs.takeIf { it in 1..duration } ?: (duration - END_SEEK_WINDOW_MS).coerceAtLeast(0)
            if (!loopEnabled) {
                runCatching { if (player.isPlaying) player.pause() }
                wasPaused = true
                endedNaturally = true
            }
        }
        // A real jump, so the anti-jitter hold must not pin reports to the old position.
        lastReportedPositionMs = target
        // SEEK_CLOSEST is frame-accurate; the int overload defaults to
        // SEEK_PREVIOUS_SYNC which snaps to the previous keyframe (often 5–10s apart).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            player.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST)
        } else {
            player.seekTo(target)
        }
    }

    override fun handlesDoubleTapAt(xFraction: Float): Boolean = xFraction < 1f / 3 || xFraction >= 2f / 3

    override fun onDoubleTapped(xFraction: Float): Boolean {
        val forward = xFraction >= 2f / 3
        if (!forward && xFraction >= 1f / 3) return false
        val player = mediaPlayer?.takeIf { isPrepared } ?: return false
        return runCatching {
            val duration = player.duration
            val position = player.currentPosition
            // Only when there is somewhere to seek to.
            if (duration <= 0) return@runCatching false
            if (forward && duration - position <= 1_000) return@runCatching false
            if (!forward && position <= 1_000) return@runCatching false
            // Decided before the seek, which itself flags an ended state when it lands at the end.
            // An end-of-video pause isn't one the user chose, so jumping back resumes playback.
            // That also restarts the tick timer, which the completion handler stopped — without
            // it the overlay seekbar would sit still despite the picture having moved.
            val resumeFromEnd = endedNaturally && !forward
            seekTo(player, (position + if (forward) 10_000 else -10_000).coerceIn(0, duration))
            if (resumeFromEnd) {
                endedNaturally = false
                wasPaused = false
                player.start()
                ensureTickTimer()
            }
            seekRippleDrawable.startAnimation(leftSide = !forward)
            seekRippleDrawable.addTime(10_000)
            true
        }.getOrDefault(false)
    }

    override fun handleCommand(commands: AttachmentCommands) {
        if (!isSelected) return
        when (commands) {
            is AttachmentCommands.SetPlaybackSpeed -> {
                // Kept even with no player yet: whichever one comes next picks it up when prepared.
                playbackSpeed = commands.speed
                pitchFollowsSpeed = commands.changePitch
                applyPlaybackSpeed()
            }
            AttachmentCommands.StartVideo -> {
                val player = mediaPlayer ?: return
                wasPaused = false
                if (isPrepared) {
                    // Play from the end means play again: without the rewind it runs for a
                    // frame, completes and pauses right back.
                    val duration = runCatching { player.duration }.getOrDefault(0)
                    val position = runCatching { player.currentPosition }.getOrDefault(0)
                    if (endedNaturally || (duration > 0 && position >= duration - END_SEEK_WINDOW_MS)) {
                        seekTo(player, 0)
                    }
                    endedNaturally = false
                    player.start()
                    ensureTickTimer()
                } else {
                    endedNaturally = false
                }
            }
            AttachmentCommands.PauseVideo -> {
                val player = mediaPlayer ?: return
                wasPaused = true
                if (isPrepared && player.isPlaying) player.pause()
            }
            is AttachmentCommands.SeekTo -> {
                val player = mediaPlayer ?: return
                if (!isPrepared) return
                val duration = player.duration
                if (duration > 0) {
                    seekTo(player, (duration * (commands.percentProgress / 100f)).toInt())
                }
            }
        }
    }

    override fun bind(attachmentInfo: AttachmentInfo) {
        super.bind(attachmentInfo)
        views.videoView.isVisible = false
        views.videoView.alpha = 1f
        views.videoThumbnailImage.isVisible = true
        waitingForFirstFrame = false
        progress = 0
        wasPaused = false
        endedNaturally = false
        endFrameMs = -1
        playbackSpeed = 1f
        pitchFollowsSpeed = true
        resetZoom()
    }
}
