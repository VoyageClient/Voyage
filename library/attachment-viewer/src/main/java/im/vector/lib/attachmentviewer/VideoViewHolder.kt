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
import android.os.Build
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.animation.doOnEnd
import androidx.core.view.isVisible
import im.vector.lib.attachmentviewer.databinding.ItemVideoAttachmentBinding
import im.vector.lib.core.utils.audio.LoudnessBoost
import im.vector.lib.core.utils.timer.CountUpTimer
import java.io.File
import java.lang.ref.WeakReference

/**
 * Video renders into a TextureView rather than the SurfaceView-based VideoView. Pinch / pan /
 * double-tap gestures apply view-tree transforms (scaleX/Y + translationX/Y), which TextureView
 * honours pixel-for-pixel, whereas SurfaceView's surface is composed at its anchored position and
 * ignores those transforms.
 */
class VideoViewHolder constructor(itemView: View) :
        BaseViewHolder(itemView) {

    private companion object {
        const val END_SEEK_WINDOW_MS = 250
        const val REVEAL_FALLBACK_MS = 500L
    }

    private var isSelected = false
    private var mVideoPath: String? = null
    private var countUpTimer: CountUpTimer? = null
    private var progress: Int = 0
    private var wasPaused = false

    /** True when the pause came from playback reaching the end, not from the user. */
    private var endedNaturally = false

    private var lastReportedPositionMs = 0

    /** Last duration a player reported, so the controls can be restored without one to ask. */
    private var lastKnownDurationMs = 0

    /** Timestamp of the final frame, probed in the background once the player is prepared. */
    @Volatile private var endFrameMs = -1

    private var player: VideoPlayback? = null
    private var surface: Surface? = null
    private var isPrepared = false
    private var waitingForFirstFrame = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var playbackSpeed = 1f
    private var pitchFollowsSpeed = true
    private var rebuiltForSpeed = false
    private var volumeGain = 1f
    private var volumeMuted = false
    private var boost: LoudnessBoost? = null

    var eventListener: WeakReference<AttachmentEventListener>? = null

    /** With looping the player wraps around by itself and completion never fires. */
    var loopEnabled = false
        set(value) {
            field = value
            player?.setLooping(value)
        }

    val views = ItemVideoAttachmentBinding.bind(itemView)

    internal val target = DefaultVideoLoaderTarget(this, views.videoThumbnailImage)

    private val seekRippleDrawable = VideoForwardDrawable(itemView.context)

    init {
        views.videoSeekRipple.background = seekRippleDrawable
        attachZoomGestures()
        views.videoView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                surface = Surface(texture)
                player?.setSurface(surface)
                if (player == null && mVideoPath != null && isSelected) {
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
                // Not before the frame size is known: until it is, the transform is identity and
                // the picture is stretched to the view bounds.
                if (waitingForFirstFrame && videoWidth > 0 && videoHeight > 0) {
                    revealVideo()
                }
            }
        }
    }

    private fun revealVideo() {
        waitingForFirstFrame = false
        itemView.removeCallbacks(revealFallback)
        views.videoView.alpha = 1f
        views.videoThumbnailImage.isVisible = false
    }

    /** A video whose size never arrives would otherwise sit behind its poster for good. */
    private val revealFallback = Runnable { if (waitingForFirstFrame) revealVideo() }

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
        // Whatever page comes next is not selected until it is told so; carrying the flag over would
        // have an off-screen page start itself and report over the one on screen.
        isSelected = false
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
        val active = player ?: return
        // Saved even when paused: the surface teardown that follows releases the player, and a
        // position remembered only for playing videos would restart a paused one from the top.
        progress = active.positionMs
        if (active.isPlaying) {
            stopTimer()
            active.pause()
        }
    }

    override fun entersForeground() {
        onSelected(isSelected)
    }

    override fun onSelected(selected: Boolean) {
        if (!selected) {
            progress = player?.takeIf { it.isPlaying }?.positionMs ?: 0
            releasePlayer()
            resetZoom()
            // A speed and a volume belong to the video they were chosen for, so swiping away
            // puts them back.
            playbackSpeed = 1f
            pitchFollowsSpeed = true
            volumeGain = 1f
            volumeMuted = false
        } else if (mVideoPath != null) {
            startPlaying()
        }
        isSelected = selected
    }

    /**
     * The overlay is one view shared by every page and is blanked as each is selected, so a page
     * coming back would read 0:00 until its first tick rather than the position it kept.
     */
    override fun publishState() {
        val active = player?.takeIf { isPrepared }
        val duration = active?.durationMs?.takeIf { it > 0 } ?: lastKnownDurationMs
        if (duration <= 0) return
        val position = active?.positionMs ?: progress
        eventListener?.get()?.onEvent(AttachmentEvents.VideoEvent(active?.isPlaying == true, position, duration))
    }

    private fun startPlaying() {
        // Page selection and every entersForeground() both land here — twice for one open transition —
        // and rebuilding the player would replay the opening second of the video.
        player?.let { active ->
            if (isPrepared && !wasPaused && !active.isPlaying) {
                active.play()
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

        // Don't try to set up the player until we have a surface to render into; the
        // SurfaceTextureListener picks it up once the surface becomes available, gated on
        // `isSelected && mVideoPath != null`.
        val activeSurface = surface ?: return

        releasePlayer()
        val path = mVideoPath ?: return
        val engine = VideoPlayback.create()
        player = engine
        engine.open(itemView.context, path, activeSurface, loopEnabled, object : VideoPlayback.Listener {
            override fun onReady() {
                isPrepared = true
                applyAspectMatrix()
                itemView.postDelayed(revealFallback, REVEAL_FALLBACK_MS)
                ensureTickTimer()
                if (endFrameMs < 0) {
                    Thread({ endFrameMs = VideoLastFrame.probeMs(itemView.context, path) }, "video-end-probe").start()
                }
                // The player is new — a chosen speed and volume only live in the holder.
                if (playbackSpeed != 1f || !pitchFollowsSpeed) applyPlaybackSpeed()
                if (volumeGain != 1f || volumeMuted) applyVolume()
                if (progress > 0) {
                    seekTo(engine, progress)
                }
                if (!wasPaused) {
                    engine.play()
                }
            }

            override fun onVideoSizeChanged(width: Int, height: Int) {
                videoWidth = width
                videoHeight = height
                applyAspectMatrix()
            }

            override fun onCompletion() {
                stopTimer()
                wasPaused = true
                endedNaturally = true
                progress = 0
                // The timer is what tells the overlay whether we're playing, so without a last
                // report of our own its button stays a pause and only ever sends PauseVideo.
                val duration = engine.durationMs
                eventListener?.get()?.onEvent(AttachmentEvents.VideoEvent(false, duration, duration))
            }

            override fun onError() {
                releasePlayer()
                wasPaused = true
                views.videoView.alpha = 0f
                views.videoThumbnailImage.isVisible = true
                eventListener?.get()?.onEvent(AttachmentEvents.VideoEvent(false, 0, 0))
            }
        })
    }

    /** Above its own samples a player has no volume left to give, so the boost takes over there. */
    private fun applyVolume() {
        val active = player ?: return
        val gain = if (volumeMuted) 0f else volumeGain
        active.setVolume(gain.coerceIn(0f, 1f))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return
        val sessionId = active.audioSessionId
        if (boost == null && sessionId != 0) boost = LoudnessBoost.attachTo(sessionId)
        boost?.setGain(gain)
    }

    private fun applyPlaybackSpeed() {
        val active = player?.takeIf { isPrepared } ?: return
        if (!active.setSpeed(playbackSpeed, pitchFollowsSpeed)) rebuildForSpeed(active)
    }

    /**
     * MediaPlayer sizes its audio buffer for the speed in force when it starts and cannot grow it,
     * so past about twice normal the sink refuses the parameters. Start again from the same
     * position instead, since the parameters set while preparing are the ones it is cut to.
     */
    private fun rebuildForSpeed(active: VideoPlayback) {
        if (rebuiltForSpeed) return
        rebuiltForSpeed = true
        progress = active.positionMs
        wasPaused = !active.isPlaying
        releasePlayer()
        startPlaying()
    }

    private fun releasePlayer() {
        // Stop the tick timer FIRST so its captured player reference isn't used after release.
        stopTimer()
        boost?.release()
        boost = null
        isPrepared = false
        lastReportedPositionMs = 0
        player?.release()
        player = null
    }

    private fun stopTimer() {
        countUpTimer?.stop()
        countUpTimer = null
    }

    private fun ensureTickTimer() {
        if (countUpTimer != null) return
        countUpTimer = CountUpTimer(intervalInMs = 100).also {
            it.tickListener = CountUpTimer.TickListener {
                val active = player ?: return@TickListener
                val duration = active.durationMs
                if (duration > 0) lastKnownDurationMs = duration
                val raw = active.positionMs
                val isPlaying = active.isPlaying
                // An audio sink spinning up (Bluetooth especially) briefly walks the reported
                // position backwards; steady playback never does, so hold through small
                // regressions rather than letting the scrubber jitter. A loop around is a real
                // jump back, so it is not one of them.
                val loopedRound = loopEnabled && duration > 0 && lastReportedPositionMs > duration - 1500
                val pos = if (isPlaying && !loopedRound && raw < lastReportedPositionMs && lastReportedPositionMs - raw < 1500) {
                    lastReportedPositionMs
                } else {
                    raw
                }
                lastReportedPositionMs = pos
                eventListener?.get()?.onEvent(AttachmentEvents.VideoEvent(isPlaying, pos, duration))
            }
            it.start()
        }
    }

    private fun seekTo(active: VideoPlayback, positionMs: Int) {
        var target = positionMs
        val duration = active.durationMs
        if (duration > 0 && positionMs > duration - END_SEEK_WINDOW_MS) {
            // The duration usually sits past the last frame, so a precise seek to it finds
            // nothing to render and the picture hangs. Aim at the final frame's own probed
            // timestamp instead, and take the completed-playback state while there.
            target = endFrameMs.takeIf { it in 1..duration } ?: (duration - END_SEEK_WINDOW_MS).coerceAtLeast(0)
            if (!loopEnabled) {
                active.pause()
                wasPaused = true
                endedNaturally = true
            }
        } else {
            // Seeking anywhere else leaves the end behind, and play from here means play from here
            // — not the rewind that playing from the end asks for.
            endedNaturally = false
        }
        // A real jump, so the anti-jitter hold must not pin reports to the old position.
        lastReportedPositionMs = target
        active.seekTo(target, exact = true)
    }

    override fun handlesDoubleTapAt(xFraction: Float): Boolean = xFraction < 1f / 3 || xFraction >= 2f / 3

    override fun onDoubleTapped(xFraction: Float): Boolean {
        val forward = xFraction >= 2f / 3
        if (!forward && xFraction >= 1f / 3) return false
        val active = player?.takeIf { isPrepared } ?: return false
        val duration = active.durationMs
        val position = active.positionMs
        // Only when there is somewhere to seek to.
        if (duration <= 0) return false
        if (forward && duration - position <= 1_000) return false
        if (!forward && position <= 1_000) return false
        // Decided before the seek, which itself flags an ended state when it lands at the end.
        // An end-of-video pause isn't one the user chose, so jumping back resumes playback.
        // That also restarts the tick timer, which the completion handler stopped — without
        // it the overlay seekbar would sit still despite the picture having moved.
        val resumeFromEnd = endedNaturally && !forward
        seekTo(active, (position + if (forward) 10_000 else -10_000).coerceIn(0, duration))
        if (resumeFromEnd) {
            endedNaturally = false
            wasPaused = false
            active.play()
            ensureTickTimer()
        }
        seekRippleDrawable.startAnimation(leftSide = !forward)
        seekRippleDrawable.addTime(10_000)
        return true
    }

    override fun handleCommand(commands: AttachmentCommands) {
        if (!isSelected) return
        when (commands) {
            is AttachmentCommands.SetVolume -> {
                // Kept even with no player yet: whichever one comes next picks it up when prepared.
                volumeGain = commands.gain
                volumeMuted = commands.muted
                applyVolume()
            }
            is AttachmentCommands.SetPlaybackSpeed -> {
                // Kept even with no player yet: whichever one comes next picks it up when prepared.
                playbackSpeed = commands.speed
                pitchFollowsSpeed = commands.changePitch
                rebuiltForSpeed = false
                applyPlaybackSpeed()
            }
            AttachmentCommands.StartVideo -> {
                val active = player ?: return
                wasPaused = false
                if (isPrepared) {
                    // Play from the end means play again: without the rewind it runs for a
                    // frame, completes and pauses right back.
                    val duration = active.durationMs
                    val position = active.positionMs
                    if (endedNaturally || (duration > 0 && position >= duration - END_SEEK_WINDOW_MS)) {
                        seekTo(active, 0)
                    }
                    endedNaturally = false
                    active.play()
                    ensureTickTimer()
                } else {
                    endedNaturally = false
                }
            }
            AttachmentCommands.PauseVideo -> {
                val active = player ?: return
                wasPaused = true
                if (isPrepared) active.pause()
            }
            is AttachmentCommands.SeekTo -> {
                val active = player ?: return
                if (!isPrepared) return
                val duration = active.durationMs
                if (duration > 0) {
                    seekTo(active, (duration * (commands.percentProgress / 100f)).toInt())
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
        itemView.removeCallbacks(revealFallback)
        videoWidth = 0
        videoHeight = 0
        progress = 0
        wasPaused = false
        endedNaturally = false
        endFrameMs = -1
        lastKnownDurationMs = 0
        playbackSpeed = 1f
        pitchFollowsSpeed = true
        rebuiltForSpeed = false
        volumeGain = 1f
        volumeMuted = false
        resetZoom()
    }
}
