/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
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
        holder.release()
        holder.resetZoom()
        super.unbind(holder)
    }

    private fun applyPlaybackState(holder: Holder) {
        val isVideo = attachment.type == ContentAttachmentData.Type.VIDEO
        holder.setVideo(attachment.queryUriAndroid.takeIf { isVideo })
        // Zoom is for stills; on a video the same surface is a tap target for play/pause.
        holder.setZoomEnabled(!isVideo)
        // Swiping away drops any zoom, so coming back lands at 1x.
        if (!activePage) holder.resetZoom()
        if (!isVideo) {
            holder.view.setOnClickListener(null)
            holder.view.isClickable = false
            return
        }
        holder.view.setOnClickListener { holder.togglePlayback() }
        if (!activePage || !playbackAllowed) {
            holder.release()
        }
    }

    class Holder : AttachmentPreviewItem.Holder() {
        override val imageView: ImageView
            get() = bigImageView
        private val bigImageView by bind<ZoomableImageView>(R.id.attachmentBigImageView)
        private val videoView by bind<TextureView>(R.id.attachmentBigVideoView)
        private val playBadge by bind<ImageView>(R.id.attachmentBigPlayBadge)

        private var videoUri: Uri? = null
        private var mediaPlayer: MediaPlayer? = null
        private var surface: Surface? = null
        private var isPrepared = false
        private var wantsPlayback = false
        private var waitingForFirstFrame = false
        private var videoWidth = 0
        private var videoHeight = 0
        private var listenerAttached = false
        private val transform = Matrix()

        fun resetZoom() = bigImageView.resetZoom()

        fun setZoomEnabled(enabled: Boolean) {
            bigImageView.zoomEnabled = enabled
        }

        fun setVideo(uri: Uri?) {
            if (videoUri != uri) {
                releasePlayer()
                videoUri = uri
                resetToThumbnail()
            }
            if (uri != null) {
                attachSurfaceListener()
                // ?vctr_accent would resolve against this activity's theme, which has no accent
                // variant and so always yields the default green. ThemeUtils resolves the
                // configured application theme instead.
                playBadge.setColorFilter(ThemeUtils.getColor(view.context, com.google.android.material.R.attr.colorAccent))
            }
        }

        fun togglePlayback() {
            val player = mediaPlayer
            if (player != null) {
                // Still preparing — the prepared listener will start it.
                if (!isPrepared) return
                if (player.isPlayingSafe()) {
                    player.pause()
                    playBadge.isVisible = true
                } else {
                    player.start()
                    playBadge.isVisible = false
                }
                return
            }
            wantsPlayback = true
            waitingForFirstFrame = true
            playBadge.isVisible = false
            // A gone TextureView never gets a SurfaceTexture, so reveal it (still transparent)
            // before waiting for the surface.
            videoView.alpha = 0f
            videoView.isVisible = true
            if (surface != null) {
                startPlayer()
            }
        }

        fun release() {
            releasePlayer()
            resetToThumbnail()
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
                        applyAspectMatrix()
                    }
                    setOnPreparedListener {
                        isPrepared = true
                        applyAspectMatrix()
                        if (wantsPlayback) it.start()
                    }
                    setOnCompletionListener {
                        wantsPlayback = false
                        playBadge.isVisible = true
                        it.seekTo(0)
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

        private fun releasePlayer() {
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
            videoWidth = 0
            videoHeight = 0
            videoView.isVisible = false
            videoView.alpha = 0f
            bigImageView.isVisible = true
            playBadge.isVisible = videoUri != null
        }

        private fun applyAspectMatrix() {
            val viewWidth = videoView.width.toFloat()
            val viewHeight = videoView.height.toFloat()
            if (viewWidth <= 0f || viewHeight <= 0f || videoWidth <= 0 || videoHeight <= 0) return
            val scale = minOf(viewWidth / videoWidth, viewHeight / videoHeight)
            val drawnWidth = videoWidth * scale
            val drawnHeight = videoHeight * scale
            transform.reset()
            transform.setScale(drawnWidth / viewWidth, drawnHeight / viewHeight)
            transform.postTranslate((viewWidth - drawnWidth) / 2f, (viewHeight - drawnHeight) / 2f)
            videoView.setTransform(transform)
            videoView.invalidate()
        }

        private fun MediaPlayer.isPlayingSafe() = try {
            isPlaying
        } catch (_: IllegalStateException) {
            false
        }
    }
}
