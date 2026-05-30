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
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
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

    private var isSelected = false
    private var mVideoPath: String? = null
    private var countUpTimer: CountUpTimer? = null
    private var progress: Int = 0
    private var wasPaused = false

    private var mediaPlayer: MediaPlayer? = null
    private var surface: Surface? = null
    private var isPrepared = false
    private var videoWidth = 0
    private var videoHeight = 0

    var eventListener: WeakReference<AttachmentEventListener>? = null

    val views = ItemVideoAttachmentBinding.bind(itemView)

    internal val target = DefaultVideoLoaderTarget(this, views.videoThumbnailImage)

    init {
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

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
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
            isQuickScaleEnabled = true
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
        if (player.isPlaying) {
            progress = player.currentPosition
            stopTimer()
            player.pause()
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
        } else if (mVideoPath != null) {
            startPlaying()
        }
        isSelected = true
    }

    private fun startPlaying() {
        views.videoThumbnailImage.isVisible = false
        views.videoLoaderProgress.isVisible = false
        views.videoView.isVisible = true

        // Don't try to set up the MediaPlayer until we have a surface to render into; the
        // SurfaceTextureListener picks it up once the surface becomes available, gated on
        // `isSelected && mVideoPath != null`.
        val activeSurface = surface ?: return

        releasePlayer()
        try {
            val player = MediaPlayer().apply {
                setSurface(activeSurface)
                setDataSource(mVideoPath ?: return)
                setOnVideoSizeChangedListener { _, width, height ->
                    this@VideoViewHolder.videoWidth = width
                    this@VideoViewHolder.videoHeight = height
                    applyAspectMatrix()
                }
                setOnPreparedListener { mp ->
                    isPrepared = true
                    applyAspectMatrix()
                    ensureTickTimer()
                    if (progress > 0) {
                        mp.seekTo(progress)
                    }
                    if (!wasPaused) {
                        mp.start()
                    }
                }
                setOnCompletionListener {
                    stopTimer()
                }
                prepareAsync()
            }
            mediaPlayer = player
        } catch (failure: Throwable) {
            Log.v(VideoViewHolder::class.java.name, "Failed to start video", failure)
            releasePlayer()
        }
    }

    private fun releasePlayer() {
        // Stop the tick timer FIRST so its captured MediaPlayer reference isn't accessed
        // after release.
        stopTimer()
        isPrepared = false
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
                    val pos = active.currentPosition
                    val isPlaying = active.isPlaying
                    eventListener?.get()?.onEvent(AttachmentEvents.VideoEvent(isPlaying, pos, duration))
                } catch (_: IllegalStateException) {
                    stopTimer()
                }
            }
            it.start()
        }
    }

    override fun handleCommand(commands: AttachmentCommands) {
        if (!isSelected) return
        val player = mediaPlayer ?: return
        when (commands) {
            AttachmentCommands.StartVideo -> {
                wasPaused = false
                if (isPrepared) {
                    player.start()
                    ensureTickTimer()
                }
            }
            AttachmentCommands.PauseVideo -> {
                wasPaused = true
                if (isPrepared && player.isPlaying) player.pause()
            }
            is AttachmentCommands.SeekTo -> {
                if (!isPrepared) return
                val duration = player.duration
                if (duration > 0) {
                    val seekDuration = duration * (commands.percentProgress / 100f)
                    player.seekTo(seekDuration.toInt())
                }
            }
        }
    }

    override fun bind(attachmentInfo: AttachmentInfo) {
        super.bind(attachmentInfo)
        progress = 0
        wasPaused = false
        resetZoom()
    }
}
