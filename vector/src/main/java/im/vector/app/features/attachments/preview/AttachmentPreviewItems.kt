/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.Animatable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import androidx.annotation.LayoutRes
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModel
import com.airbnb.epoxy.EpoxyModelClass
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.github.penfeizhou.animation.FrameAnimationDrawable
import im.vector.app.R
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.platform.CheckableImageView
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.attachmentviewer.VideoForwardDrawable
import im.vector.lib.attachmentviewer.VideoLastFrame
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.content.queryUriAndroid
import timber.log.Timber

abstract class AttachmentPreviewItem<H : AttachmentPreviewItem.Holder>(@LayoutRes layoutId: Int) : VectorEpoxyModel<H>(layoutId) {

    abstract val attachment: ContentAttachmentData

    /**
     * Restored on every bind because holders are recycled and the generic-file branch changes it.
     * Null where the view manages its own scale type, as ZoomableImageView does.
     */
    protected open val defaultScaleType: ImageView.ScaleType? = null

    override fun bind(holder: H) {
        super.bind(holder)
        defaultScaleType?.let { holder.imageView.scaleType = it }
        when (attachment.type) {
            ContentAttachmentData.Type.VIDEO -> {
                // .frame(0) only does anything for video sources; .asBitmap() is required to
                // hand Glide that hint. The low-res thumbnail request paints first — a scaled
                // frame extract is much faster than the full-size one, which can take a second
                // of black screen on a large clip.
                Glide.with(holder.view.context)
                        .asBitmap()
                        .load(attachment.queryUriAndroid)
                        .apply(RequestOptions().frame(0))
                        .thumbnail(
                                Glide.with(holder.view.context)
                                        .asBitmap()
                                        .load(attachment.queryUriAndroid)
                                        .apply(RequestOptions().frame(0).override(240))
                        )
                        .into(holder.imageView)
            }
            ContentAttachmentData.Type.IMAGE -> {
                // Plain URI load — our registered Uri -> ByteBuffer loader (see MyAppGlideModule)
                // feeds penfeizhou's animation decoder on Glide's background executor, so APNGs
                // and animated WebPs preview correctly without us reading the file on the main
                // thread.
                Glide.with(holder.view.context)
                        .load(attachment.queryUriAndroid)
                        .into(holder.imageView)
            }
            else -> {
                holder.imageView.setImageResource(R.drawable.filetype_attachment)
                holder.imageView.scaleType = ImageView.ScaleType.FIT_CENTER
            }
        }
    }

    abstract class Holder : VectorEpoxyHolder() {
        abstract val imageView: ImageView
    }
}

@EpoxyModelClass
abstract class AttachmentMiniaturePreviewItem : AttachmentPreviewItem<AttachmentMiniaturePreviewItem.Holder>(R.layout.item_attachment_miniature_preview) {

    override val defaultScaleType = ImageView.ScaleType.CENTER_CROP

    @EpoxyAttribute override lateinit var attachment: ContentAttachmentData

    @EpoxyAttribute
    var clickListener: View.OnClickListener? = null

    @EpoxyAttribute
    var checked: Boolean = false

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.imageView.isChecked = checked
        // The activity theme has no accent variant (?colorAccent is the default green) and a themed-attr
        // ColorStateList doesn't pick up the configured accent either, so resolve it from the app theme and
        // paint the selection border ourselves. The image view's padding turns this fill into a border.
        val border = if (checked) ThemeUtils.getColor(holder.view.context, com.google.android.material.R.attr.colorAccent) else Color.TRANSPARENT
        val pad = holder.imageView.paddingLeft
        holder.imageView.setBackgroundColor(border)
        holder.imageView.setPadding(pad, pad, pad, pad)
        holder.miniatureVideoIndicator.isVisible = attachment.type == ContentAttachmentData.Type.VIDEO
        holder.view.setOnClickListener(clickListener)
    }

    class Holder : AttachmentPreviewItem.Holder() {
        override val imageView: CheckableImageView
            get() = miniatureImageView
        private val miniatureImageView by bind<CheckableImageView>(R.id.attachmentMiniatureImageView)
        val miniatureVideoIndicator by bind<ImageView>(R.id.attachmentMiniatureVideoIndicator)
    }
}

@EpoxyModelClass
abstract class AttachmentBigPreviewItem : AttachmentPreviewItem<AttachmentBigPreviewItem.Holder>(R.layout.item_attachment_big_preview) {

    @EpoxyAttribute override lateinit var attachment: ContentAttachmentData

    /** Only the attachment the pager is settled on is allowed to hold a MediaPlayer. */
    @EpoxyAttribute var activePage: Boolean = false

    /** False while the hosting fragment is not resumed. */
    @EpoxyAttribute var playbackAllowed: Boolean = true

    @EpoxyAttribute var loopVideos: Boolean = false

    /** The size the attachment will be sent at, when the sender has chosen one. */
    @EpoxyAttribute var targetSize: Pair<Int, Int>? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var playbackListener: VideoPlaybackListener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        applyPlaybackState(holder)
    }

    override fun bind(holder: Holder, previouslyBoundModel: EpoxyModel<*>) {
        if ((previouslyBoundModel as? AttachmentBigPreviewItem)?.attachment?.queryUri == attachment.queryUri) {
            // Re-running super.bind() would restart the Glide load, flashing the thumbnail back
            // over video that is already playing.
            applyPlaybackState(holder)
        } else {
            bind(holder)
        }
    }

    override fun unbind(holder: Holder) {
        // The fragment's controls must let go of a recycled holder, or they would drive whatever
        // attachment its views are rebound to next.
        holder.releasePlaybackControls(playbackListener)
        holder.release()
        holder.resetZoom()
        super.unbind(holder)
    }

    private fun applyPlaybackState(holder: Holder) {
        val isVideo = attachment.type == ContentAttachmentData.Type.VIDEO
        // Before setVideo: a recycled holder still holding the controls would otherwise report the
        // position it is being reset to into a bar that another page owns by now.
        if (!isVideo) holder.setPlaybackListener(null)
        holder.setTargetSize(targetSize)
        holder.setLooping(loopVideos)
        holder.setVideo(attachment.queryUriAndroid.takeIf { isVideo }, (attachment.duration ?: 0L).toInt())
        // Each surface owns its own zoom, so the still and the video never fight over the gesture.
        holder.setZoomEnabled(!isVideo)
        // Swiping away drops any zoom, so coming back lands at 1x.
        if (!activePage) holder.resetZoom()
        if (!isVideo) {
            if (activePage) playbackListener?.onVideoControlsAvailable(null)
            // Swiping to another attachment must not leave this one burning cycles off-screen.
            holder.setAnimatedImagePlaying(activePage && playbackAllowed)
            return
        }
        // Only whichever attachment is on show drives the controls under the send options, and only
        // it may claim them: a sibling binding afterwards would otherwise take them away again.
        holder.setPlaybackListener(playbackListener.takeIf { activePage })
        if (activePage) playbackListener?.onVideoControlsAvailable(holder)
        when {
            // A different attachment is on show now, so this one starts from the top next time.
            !activePage -> holder.release()
            !playbackAllowed -> holder.suspendPlayback()
            else -> holder.resumePlaybackIfNeeded()
        }
    }

    class Holder : AttachmentPreviewItem.Holder(), VideoPlaybackControls {
        override val imageView: ImageView
            get() = bigImageView
        private val bigImageView by bind<ZoomableImageView>(R.id.attachmentBigImageView)
        private val videoView by bind<ZoomableTextureView>(R.id.attachmentBigVideoView)
        private val seekRipple by bind<View>(R.id.attachmentBigSeekRipple)
        private val seekRippleDrawable by lazy { VideoForwardDrawable(view.context).also { seekRipple.background = it } }
        private val playPauseButton by bind<ImageView>(R.id.attachmentBigPlayPause)

        private var videoUri: Uri? = null
        private var mediaPlayer: MediaPlayer? = null
        private var surface: Surface? = null
        private var isPrepared = false
        private var wantsPlayback = false
        private var waitingForFirstFrame = false
        private var videoWidth = 0
        private var videoHeight = 0
        private var listenerAttached = false

        /** Leaving the app tears the surface down and with it the player; this is where it was. */
        private var resumePositionMs = 0
        private var resumeWasPlaying = false

        /** The attachment's own length, so the bar reads correctly before anything is prepared. */
        private var declaredDurationMs = 0
        private var lastReportedPositionMs = 0
        private var looping = false

        /** Timestamp of the final frame, probed in the background once the player is prepared. */
        @Volatile private var endFrameMs = -1

        private var listener: VideoPlaybackListener? = null
        private val ticker = Runnable { onTick() }

        fun resetZoom() {
            bigImageView.resetZoom()
            videoView.resetZoom()
        }

        /**
         * APNG, GIF and animated WebP all arrive as self-animating [Animatable] drawables, so pausing
         * is a matter of stopping the drawable rather than driving a player.
         */
        fun setAnimatedImagePlaying(playing: Boolean) {
            val drawable = bigImageView.drawable
            // penfeizhou's stop() rewinds to frame 0, so APNG/WebP have to pause instead. Glide's
            // GifDrawable has no pause and already holds its position on stop().
            if (drawable is FrameAnimationDrawable<*>) {
                when {
                    !playing -> if (drawable.isRunning) drawable.pause()
                    !drawable.isRunning -> drawable.start()
                    else -> drawable.resume()
                }
                return
            }
            val animatable = drawable as? Animatable ?: return
            if (playing == animatable.isRunning) return
            if (playing) animatable.start() else animatable.stop()
        }

        fun setTargetSize(size: Pair<Int, Int>?) {
            bigImageView.contentSizeOverride = size
            videoView.contentSizeOverride = size
        }

        fun setZoomEnabled(enabled: Boolean) {
            bigImageView.zoomEnabled = enabled
        }

        fun setLooping(value: Boolean) {
            looping = value
            runCatching { mediaPlayer?.isLooping = value }
        }

        fun setVideo(uri: Uri?, durationMs: Int) {
            if (videoUri != uri) {
                releasePlayer()
                videoUri = uri
                endFrameMs = -1
                resetToThumbnail()
            }
            declaredDurationMs = durationMs
            playPauseButton.isVisible = uri != null
            if (uri != null) {
                attachSurfaceListener()
                videoView.onDoubleTap = ::onDoubleTapSeek
                playPauseButton.setOnClickListener { togglePlayback() }
            } else {
                videoView.onDoubleTap = null
                playPauseButton.setOnClickListener(null)
            }
        }

        private fun onDoubleTapSeek(xFraction: Float): Boolean {
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
                player.seekEndAware((position + if (forward) 10_000 else -10_000).coerceIn(0, duration))
                seekRippleDrawable.contentRect = videoView.fittedContentRect()
                seekRippleDrawable.startAnimation(leftSide = !forward)
                seekRippleDrawable.addTime(10_000)
                reportProgress()
                true
            }.getOrDefault(false)
        }

        /** The controls live in the fragment, below the send options, and drive this holder. */
        fun setPlaybackListener(listener: VideoPlaybackListener?) {
            this.listener = listener
            if (listener != null) reportProgress()
        }

        fun releasePlaybackControls(owner: VideoPlaybackListener?) {
            if (listener != null) owner?.onVideoControlsReleased(this)
            listener = null
        }

        override fun seekTo(positionMs: Int) {
            val player = mediaPlayer?.takeIf { isPrepared }
            // Nothing prepared yet, so remember it as the point playback will pick up from.
            player?.seekEndAware(positionMs) ?: run {
                resumePositionMs = positionMs
                lastReportedPositionMs = positionMs
            }
            reportProgress()
        }

        /**
         * The duration usually sits past the last frame, so a precise seek to it finds nothing
         * to render and the picture hangs. Aim at the final frame's own probed timestamp
         * instead, paused — a paused frame-accurate seek always displays its target.
         */
        private fun MediaPlayer.seekEndAware(positionMs: Int) {
            var target = positionMs
            val durationMs = runCatching { duration }.getOrDefault(0)
            if (durationMs > 0 && positionMs > durationMs - END_SEEK_WINDOW_MS) {
                target = endFrameMs.takeIf { it in 1..durationMs } ?: (durationMs - END_SEEK_WINDOW_MS).coerceAtLeast(0)
                if (!looping) runCatching { if (isPlayingSafe()) pause() }
            }
            lastReportedPositionMs = target
            seekToPrecise(target)
        }

        private fun durationMs() = mediaPlayer?.takeIf { isPrepared }?.let {
            runCatching { it.duration }.getOrDefault(0)
        }?.takeIf { it > 0 } ?: declaredDurationMs

        private fun onTick() {
            reportProgress()
            if (mediaPlayer?.isPlayingSafe() == true) videoView.postDelayed(ticker, PROGRESS_INTERVAL_MS)
        }

        private fun reportProgress() {
            val raw = mediaPlayer?.takeIf { isPrepared }?.let { runCatching { it.currentPosition }.getOrDefault(0) }
                    ?: resumePositionMs
            val playing = mediaPlayer?.isPlayingSafe() == true
            // An audio sink spinning up (Bluetooth especially) briefly walks the reported position
            // backwards; steady playback never does, so hold through small regressions.
            val position = if (playing && raw < lastReportedPositionMs && lastReportedPositionMs - raw < 1500) {
                lastReportedPositionMs
            } else {
                raw
            }
            lastReportedPositionMs = position
            playPauseButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow)
            listener?.onVideoProgress(position, durationMs(), playing)
        }

        override fun togglePlayback() {
            val player = mediaPlayer
            if (player != null) {
                // Still preparing — the prepared listener will start it.
                if (!isPrepared) return
                if (player.isPlayingSafe()) {
                    player.pause()
                } else {
                    // Play from the end means play again: without the rewind it runs for a
                    // frame, completes and rewinds paused.
                    val durationMs = runCatching { player.duration }.getOrDefault(0)
                    val positionMs = runCatching { player.currentPosition }.getOrDefault(0)
                    if (durationMs > 0 && positionMs >= durationMs - END_SEEK_WINDOW_MS) {
                        player.seekToPrecise(0)
                        lastReportedPositionMs = 0
                    }
                    player.start()
                    startTicking()
                }
                return
            }
            wantsPlayback = true
            waitingForFirstFrame = true
            // A gone TextureView never gets a SurfaceTexture, so reveal it (still transparent)
            // before waiting for the surface.
            videoView.alpha = 0f
            videoView.isVisible = true
            if (surface != null) {
                startPlayer()
            }
        }

        /** Done with this attachment altogether: drop the player and go back to the poster. */
        fun release() {
            releasePlayer()
            resetToThumbnail()
        }

        /** Backgrounded: drop the player but keep the place — [release] would revert to the poster. */
        override fun suspendPlayback() {
            mediaPlayer?.let {
                resumePositionMs = runCatching { it.currentPosition }.getOrDefault(0)
                resumeWasPlaying = it.isPlayingSafe()
            }
            releasePlayer()
        }

        fun resumePlaybackIfNeeded() {
            if (mediaPlayer == null && wantsPlayback && surface != null) startPlayer()
        }

        private fun attachSurfaceListener() {
            if (listenerAttached) return
            listenerAttached = true
            videoView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    surface = Surface(texture)
                    mediaPlayer?.setSurface(surface)
                    if (wantsPlayback && mediaPlayer == null) {
                        startPlayer()
                    }
                    videoView.setVideoSize(videoWidth, videoHeight)
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                    videoView.setVideoSize(videoWidth, videoHeight)
                }

                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    // Remembered before the player goes, or coming back would start the clip again.
                    mediaPlayer?.let {
                        resumePositionMs = runCatching { it.currentPosition }.getOrDefault(0)
                        resumeWasPlaying = it.isPlayingSafe()
                    }
                    releasePlayer()
                    surface?.release()
                    surface = null
                    // A dead surface renders black, so the poster covers the gap until the rebuilt
                    // player paints its first frame.
                    if (videoView.isVisible) {
                        waitingForFirstFrame = true
                        videoView.alpha = 0f
                        bigImageView.isVisible = true
                    }
                    return true
                }

                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                    if (waitingForFirstFrame) {
                        waitingForFirstFrame = false
                        videoView.alpha = 1f
                        bigImageView.isVisible = false
                    }
                }
            }
        }

        private fun startPlayer() {
            val uri = videoUri ?: return
            val activeSurface = surface ?: return
            releasePlayer()
            try {
                mediaPlayer = MediaPlayer().apply {
                    setSurface(activeSurface)
                    setDataSource(view.context, uri)
                    isLooping = looping
                    setOnVideoSizeChangedListener { _, width, height ->
                        this@Holder.videoWidth = width
                        this@Holder.videoHeight = height
                        videoView.setVideoSize(width, height)
                    }
                    setOnPreparedListener {
                        isPrepared = true
                        if (endFrameMs < 0) {
                            Thread({ endFrameMs = VideoLastFrame.probeMs(view.context, uri.toString()) }, "video-end-probe").start()
                        }
                        videoView.setVideoSize(this@Holder.videoWidth, this@Holder.videoHeight)
                        val resuming = resumePositionMs > 0
                        // Seeking a prepared player paints the frame, so a video left paused comes
                        // back showing where it was rather than black.
                        if (resuming) it.seekToPrecise(resumePositionMs)
                        if (wantsPlayback && (!resuming || resumeWasPlaying)) {
                            it.start()
                            startTicking()
                        }
                        reportProgress()
                    }
                    setOnCompletionListener {
                        wantsPlayback = false
                        resumePositionMs = 0
                        resumeWasPlaying = false
                        it.seekTo(0)
                        reportProgress()
                    }
                    setOnErrorListener { _, what, extra ->
                        // Reverting to the thumbnail silently leaves no trace of why playback died.
                        Timber.w("Attachment preview video error what=$what extra=$extra")
                        this@Holder.release()
                        true
                    }
                    prepareAsync()
                }
            } catch (failure: Throwable) {
                Timber.w(failure, "Failed to play attachment preview video")
                release()
            }
        }

        private fun startTicking() {
            videoView.removeCallbacks(ticker)
            videoView.post(ticker)
        }

        private fun releasePlayer() {
            videoView.removeCallbacks(ticker)
            isPrepared = false
            mediaPlayer?.let {
                try {
                    if (it.isPlayingSafe()) it.stop()
                } catch (_: IllegalStateException) {
                    // Already stopped or released.
                }
                it.release()
            }
            mediaPlayer = null
        }

        private fun resetToThumbnail() {
            wantsPlayback = false
            waitingForFirstFrame = false
            resumePositionMs = 0
            resumeWasPlaying = false
            lastReportedPositionMs = 0
            videoWidth = 0
            videoHeight = 0
            // The view keeps its own aspect matrix, which would squeeze the next clip into the
            // shape of the one this holder was showing before.
            videoView.setVideoSize(0, 0)
            videoView.resetZoom()
            videoView.isVisible = false
            videoView.alpha = 0f
            bigImageView.isVisible = true
            reportProgress()
        }

        /**
         * Plain seekTo lands on the previous sync frame, which on a typical clip is seconds
         * earlier — coming back from the background would jump to whatever keyframe preceded where
         * you actually were. Frame-accurate seeking only exists from API 26; below it the platform
         * offers nothing better.
         */
        private fun MediaPlayer.seekToPrecise(positionMs: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                seekTo(positionMs.toLong(), MediaPlayer.SEEK_CLOSEST)
            } else {
                seekTo(positionMs)
            }
        }

        private fun MediaPlayer.isPlayingSafe() = try {
            isPlaying
        } catch (_: IllegalStateException) {
            false
        }

        companion object {
            private const val PROGRESS_INTERVAL_MS = 100L
            private const val END_SEEK_WINDOW_MS = 250
        }
    }
}

/** What the fragment's playback controls can ask of whichever attachment is on show. */
interface VideoPlaybackControls {
    fun togglePlayback()
    fun seekTo(positionMs: Int)

    /** Drops the decoder but keeps the position, for handing the clip to another screen. */
    fun suspendPlayback()
}

interface VideoPlaybackListener {
    /** Null when the attachment on show is not a video, and the controls should go away. */
    fun onVideoControlsAvailable(controls: VideoPlaybackControls?)

    /**
     * A holder is letting go of the controls. Identity matters: a recycled holder is unbound *after*
     * the incoming one has bound and claimed them, so an unconditional release would hide controls
     * that the page now on screen is driving.
     */
    fun onVideoControlsReleased(controls: VideoPlaybackControls)

    fun onVideoProgress(positionMs: Int, durationMs: Int, isPlaying: Boolean)
}
