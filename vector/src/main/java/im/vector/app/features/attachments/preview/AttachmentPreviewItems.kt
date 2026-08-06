/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import android.graphics.Color
import android.graphics.SurfaceTexture
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
import im.vector.app.R
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.platform.CheckableImageView
import im.vector.app.features.themes.ThemeUtils
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.content.queryUriAndroid
import timber.log.Timber

abstract class AttachmentPreviewItem<H : AttachmentPreviewItem.Holder>(@LayoutRes layoutId: Int) : VectorEpoxyModel<H>(layoutId) {

    abstract val attachment: ContentAttachmentData

    override fun bind(holder: H) {
        super.bind(holder)
        when (attachment.type) {
            ContentAttachmentData.Type.VIDEO -> {
                // .frame(0) only does anything for video sources; .asBitmap() is required to
                // hand Glide that hint.
                Glide.with(holder.view.context)
                        .asBitmap()
                        .load(attachment.queryUriAndroid)
                        .apply(RequestOptions().frame(0))
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
        holder.setTargetSize(targetSize)
        holder.setVideo(attachment.queryUriAndroid.takeIf { isVideo }, (attachment.duration ?: 0L).toInt())
        // Each surface owns its own zoom, so the still and the video never fight over the gesture.
        holder.setZoomEnabled(!isVideo)
        // Swiping away drops any zoom, so coming back lands at 1x.
        if (!activePage) holder.resetZoom()
        if (!isVideo) {
            holder.view.setOnClickListener(null)
            holder.view.isClickable = false
            return
        }
        holder.view.setOnClickListener { holder.togglePlayback() }
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
        private var listener: VideoPlaybackListener? = null
        private val ticker = Runnable { onTick() }

        fun resetZoom() {
            bigImageView.resetZoom()
            videoView.resetZoom()
        }

        fun setTargetSize(size: Pair<Int, Int>?) {
            bigImageView.contentSizeOverride = size
            videoView.contentSizeOverride = size
        }

        fun setZoomEnabled(enabled: Boolean) {
            bigImageView.zoomEnabled = enabled
        }

        fun setVideo(uri: Uri?, durationMs: Int) {
            if (videoUri != uri) {
                releasePlayer()
                videoUri = uri
                resetToThumbnail()
            }
            declaredDurationMs = durationMs
            if (uri != null) {
                attachSurfaceListener()
                // Once the video is showing it owns the touches, so the root's click never fires.
                videoView.setOnClickListener { togglePlayback() }
            }
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
            // Nothing prepared yet, so remember it as the point playback will pick up from.
            mediaPlayer?.takeIf { isPrepared }?.seekToPrecise(positionMs) ?: run { resumePositionMs = positionMs }
            reportProgress()
        }

        private fun durationMs() = mediaPlayer?.takeIf { isPrepared }?.let {
            runCatching { it.duration }.getOrDefault(0)
        }?.takeIf { it > 0 } ?: declaredDurationMs

        private fun onTick() {
            reportProgress()
            if (mediaPlayer?.isPlayingSafe() == true) videoView.postDelayed(ticker, PROGRESS_INTERVAL_MS)
        }

        private fun reportProgress() {
            val position = mediaPlayer?.takeIf { isPrepared }?.let { runCatching { it.currentPosition }.getOrDefault(0) }
                    ?: resumePositionMs
            listener?.onVideoProgress(position, durationMs(), mediaPlayer?.isPlayingSafe() == true)
        }

        override fun togglePlayback() {
            val player = mediaPlayer
            if (player != null) {
                // Still preparing — the prepared listener will start it.
                if (!isPrepared) return
                if (player.isPlayingSafe()) {
                    player.pause()
                } else {
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
        fun suspendPlayback() {
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
                    setOnVideoSizeChangedListener { _, width, height ->
                        this@Holder.videoWidth = width
                        this@Holder.videoHeight = height
                        videoView.setVideoSize(width, height)
                    }
                    setOnPreparedListener {
                        isPrepared = true
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
                    setOnErrorListener { _, _, _ ->
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
        }
    }
}

/** What the fragment's playback controls can ask of whichever attachment is on show. */
interface VideoPlaybackControls {
    fun togglePlayback()
    fun seekTo(positionMs: Int)
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
