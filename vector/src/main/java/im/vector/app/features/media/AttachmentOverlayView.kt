/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import android.util.AttributeSet
import android.view.Menu
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.PopupMenu
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isVisible
import im.vector.app.R
import im.vector.app.core.files.isLocalMediaUri
import im.vector.app.databinding.MergeImageAttachmentOverlayBinding
import im.vector.app.features.attachments.editor.video.PlaybackSpeed
import im.vector.app.features.attachments.editor.video.PlaybackSpeedDialog
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.attachmentviewer.AttachmentEventListener
import im.vector.lib.attachmentviewer.AttachmentEvents
import im.vector.lib.strings.CommonStrings
import java.util.concurrent.Executors

class AttachmentOverlayView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr), AttachmentEventListener {

    var interactionListener: AttachmentInteractionListener? = null
    val views: MergeImageAttachmentOverlayBinding

    private var isPlaying = false
    private var suspendSeekBarUpdate = false
    private var playbackSpeed = PlaybackSpeed()
    private var isVideo = false

    /**
     * Telegram-style reveal: opening a video shows the chrome without the centre play/pause;
     * the button only joins once the chrome has been hidden and brought back.
     */
    private var centerButtonUnlocked = false

    private var seekBarAnimator: ObjectAnimator? = null

    /**
     * Scrub preview, after Telegram's VideoSeekPreviewImage: frames pulled on a single background
     * thread, re-extracted only when the thumb has moved a whole pixel, one in flight at a time
     * with only the newest request kept waiting.
     */
    private var seekPreviewSource: String? = null
    private val seekPreviewExecutor by lazy { Executors.newSingleThreadExecutor() }
    private var seekPreviewRetriever: MediaMetadataRetriever? = null
    private var seekPreviewRetrieverSource: String? = null
    private var previewBusy = false
    private var pendingPreviewMs = -1
    private var lastPreviewPixel = -1
    private var scrubbing = false
    private var moreMenuOpen = false
    private var speedDialogOpen = false

    /** The auto-hide countdown must not fire out from under an active scrub or an open menu. */
    fun isUserInteracting(): Boolean = scrubbing || moreMenuOpen || speedDialogOpen

    init {
        inflate(context, R.layout.merge_image_attachment_overlay, this)
        views = MergeImageAttachmentOverlayBinding.bind(this)
        setBackgroundColor(Color.TRANSPARENT)
        views.overlayBackButton.setOnClickListener {
            interactionListener?.onDismiss()
        }
        views.overlayDownloadButton.setOnClickListener {
            interactionListener?.onDownload()
        }
        views.overlayMoreButton.setOnClickListener { showMoreMenu() }
        views.overlayCenterPlayPause.setOnClickListener {
            interactionListener?.onPlayPause(!isPlaying)
        }

        views.overlaySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || seekBar == null) return
                views.overlayVideoTime.text = context.getString(
                        CommonStrings.video_position_of_duration, elapsed(progress), elapsed(seekBar.max)
                )
                updatePreviewPosition(progress)
                requestPreviewFrame(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                suspendSeekBarUpdate = true
                scrubbing = true
                if (seekPreviewSource != null && seekBar != null) {
                    updatePreviewPosition(seekBar.progress)
                    requestPreviewFrame(seekBar.progress)
                    animatePreview(show = true)
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // The bar runs in milliseconds now; the seek command still speaks percent.
                seekBar?.let { interactionListener?.videoSeekTo(it.progress * 100 / it.max.coerceAtLeast(1)) }
                suspendSeekBarUpdate = false
                scrubbing = false
                pendingPreviewMs = -1
                animatePreview(show = false)
            }
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            views.overlaySeekPreview.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(6f))
                }
            }
            views.overlaySeekPreview.clipToOutline = true
        }
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    fun setSeekPreviewSource(source: String?) {
        if (seekPreviewSource == source) return
        seekPreviewSource = source
        lastPreviewPixel = -1
        pendingPreviewMs = -1
        if (source == null) views.overlaySeekPreview.isVisible = false
    }

    private fun animatePreview(show: Boolean) {
        val preview = views.overlaySeekPreview
        if (show && seekPreviewSource == null) return
        preview.animate().cancel()
        if (show) {
            preview.isVisible = true
            preview.scaleX = 0.5f
            preview.scaleY = 0.5f
            preview.alpha = 0f
        }
        preview.animate()
                .alpha(if (show) 1f else 0f)
                .scaleX(if (show) 1f else 0.5f)
                .scaleY(if (show) 1f else 0.5f)
                .setDuration(150)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        preview.animate().setListener(null)
                        if (!show) preview.isVisible = false
                    }
                })
                .start()
    }

    /** Where the thumb is, in this view's coordinates, clamped so the box never leaves the screen. */
    private fun updatePreviewPosition(progressMs: Int) {
        val bar = views.overlaySeekBar
        val preview = views.overlaySeekPreview
        val track = (bar.width - bar.paddingLeft - bar.paddingRight).coerceAtLeast(1)
        val thumbX = bar.x + bar.paddingLeft + track.toFloat() * progressMs / bar.max.coerceAtLeast(1)
        val min = dp(10f)
        val max = (width - dp(10f) - preview.width).coerceAtLeast(min)
        preview.translationX = (thumbX - preview.width / 2f).coerceIn(min, max)
    }

    private fun requestPreviewFrame(positionMs: Int) {
        val bar = views.overlaySeekBar
        if (seekPreviewSource == null) return
        val track = (bar.width - bar.paddingLeft - bar.paddingRight).coerceAtLeast(1)
        val pixel = (positionMs.toLong() * track / bar.max.coerceAtLeast(1)).toInt()
        if (pixel == lastPreviewPixel) return
        lastPreviewPixel = pixel
        if (previewBusy) {
            pendingPreviewMs = positionMs
            return
        }
        previewBusy = true
        extractPreviewFrame(positionMs)
    }

    private fun extractPreviewFrame(positionMs: Int) {
        val source = seekPreviewSource ?: run { previewBusy = false; return }
        runCatching {
            seekPreviewExecutor.execute {
                val bitmap = frameAt(source, positionMs)
                post {
                    if (bitmap != null && scrubbing && source == seekPreviewSource) {
                        showPreviewBitmap(bitmap)
                    }
                    previewBusy = false
                    val pending = pendingPreviewMs
                    pendingPreviewMs = -1
                    if (pending >= 0 && scrubbing) {
                        previewBusy = true
                        extractPreviewFrame(pending)
                    }
                }
            }
        }.onFailure { previewBusy = false }
    }

    /** Runs on the preview executor thread only. */
    private fun frameAt(source: String, positionMs: Int): Bitmap? = runCatching {
        if (seekPreviewRetrieverSource != source) {
            runCatching { seekPreviewRetriever?.release() }
            // Assigned before it is configured, so a failing setDataSource still gets released.
            seekPreviewRetriever = MediaMetadataRetriever()
            seekPreviewRetrieverSource = null
            seekPreviewRetriever?.apply {
                if (source.isLocalMediaUri()) {
                    setDataSource(context, Uri.parse(source))
                } else {
                    setDataSource(source)
                }
            }
            seekPreviewRetrieverSource = source
        }
        val retriever = seekPreviewRetriever ?: return null
        val timeUs = positionMs * 1000L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val box = dp(150f).toInt()
            retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, box, box)
        } else {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
    }.onFailure {
        runCatching { seekPreviewRetriever?.release() }
        seekPreviewRetriever = null
        seekPreviewRetrieverSource = null
    }.getOrNull()

    /** Telegram sizes the box to the frame's aspect inside a 150dp square; so do we. */
    private fun showPreviewBitmap(bitmap: Bitmap) {
        val preview = views.overlaySeekPreview
        val boxPx = dp(150f).toInt()
        val aspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
        val targetWidth: Int
        val targetHeight: Int
        if (aspect > 1f) {
            targetWidth = boxPx
            targetHeight = (boxPx / aspect).toInt()
        } else {
            targetHeight = boxPx
            targetWidth = (boxPx * aspect).toInt()
        }
        if (preview.layoutParams.width != targetWidth || preview.layoutParams.height != targetHeight) {
            preview.layoutParams = preview.layoutParams.apply {
                width = targetWidth
                height = targetHeight
            }
        }
        preview.setImageBitmap(bitmap)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        runCatching {
            seekPreviewExecutor.execute {
                runCatching { seekPreviewRetriever?.release() }
                seekPreviewRetriever = null
                seekPreviewRetrieverSource = null
            }
            seekPreviewExecutor.shutdown()
        }
    }

    /** Called on every change of attachment, where the speed goes back to normal with the holder's. */
    fun showVideoControls(show: Boolean, durationMs: Long? = null) {
        views.overlayVideoControlsGroup.isVisible = show
        isVideo = show
        playbackSpeed = PlaybackSpeed()
        centerButtonUnlocked = false
        refreshCenterButton()
        // The declared duration, so the label doesn't sit empty while the player spins up.
        seekBarAnimator?.cancel()
        views.overlaySeekBar.max = (durationMs?.toInt() ?: 0).coerceAtLeast(1)
        views.overlaySeekBar.progress = 0
        views.overlayVideoTime.text = if (show && durationMs != null && durationMs > 0) {
            context.getString(CommonStrings.video_position_of_duration, elapsed(0), elapsed(durationMs.toInt()))
        } else {
            ""
        }
    }

    private fun refreshCenterButton() {
        views.overlayCenterPlayPause.isVisible = isVideo && centerButtonUnlocked
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // The activity pads this overlay by the system-bar insets, which pulls the constraint
        // centre away from the middle of the screen — where the video is.
        views.overlayCenterPlayPause.translationY = (paddingBottom - paddingTop) / 2f
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // Hiding the chrome is what unlocks the centre button for the next reveal.
        if (changedView === this && visibility != View.VISIBLE && isVideo) {
            centerButtonUnlocked = true
            refreshCenterButton()
        }
    }

    private fun showMoreMenu() {
        val themed = ContextThemeWrapper(context, ThemeUtils.getApplicationThemeRes(context))
        val popup = PopupMenu(themed, views.overlayMoreButton)
        popup.inflate(R.menu.menu_attachment_viewer_overlay)
        val speedItem = popup.menu.findItem(R.id.attachmentViewerPlaybackSpeed)
        // MediaPlayer only takes a speed from API 23, and nothing below it can stand in.
        speedItem.isVisible = isVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        tintMenuIcons(themed, popup.menu)
        // A popup menu hides its icons until forced to, on every level appcompat covers.
        popup.setForceShowIcon(true)
        moreMenuOpen = true
        // The dismissing tap lands in the popup's own window, so the viewer never sees a touch
        // to restart its auto-hide countdown from — it has to be told.
        popup.setOnDismissListener {
            moreMenuOpen = false
            interactionListener?.onControlsInteractionEnded()
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.attachmentViewerShare -> interactionListener?.onShare()
                R.id.attachmentViewerForward -> interactionListener?.onForward()
                R.id.attachmentViewerShowInChat -> interactionListener?.onShowInChat()
                R.id.attachmentViewerPlaybackSpeed -> showSpeedDialog()
            }
            true
        }
        popup.show()
    }

    /** The icons are flat colours of their own, which the app's own theme may well not read against. */
    private fun tintMenuIcons(themed: Context, menu: Menu) {
        val tint = ThemeUtils.getColorFromContextTheme(themed, im.vector.lib.ui.styles.R.attr.vctr_content_primary)
        for (index in 0 until menu.size()) {
            val icon = menu.getItem(index).icon?.mutate() ?: continue
            DrawableCompat.setTint(icon, tint)
            menu.getItem(index).icon = icon
        }
    }

    private fun showSpeedDialog() {
        speedDialogOpen = true
        PlaybackSpeedDialog(
                // The viewer's own theme is a bare fullscreen one, so the sheet takes the app's.
                context = ContextThemeWrapper(context, ThemeUtils.getApplicationThemeRes(context)),
                initial = playbackSpeed,
                allowPitchChoice = true,
                onChanged = { speed ->
                    playbackSpeed = speed
                    interactionListener?.onPlaybackSpeedChanged(speed.speed, speed.changePitch)
                },
                onDismiss = {
                    speedDialogOpen = false
                    interactionListener?.onControlsInteractionEnded()
                }
        ).show()
    }

    private fun elapsed(milliseconds: Int) = DateUtils.formatElapsedTime((milliseconds / 1000).toLong())

    fun updateWith(counter: String, senderInfo: String) {
        views.overlayCounterText.text = counter
        views.overlayInfoText.text = senderInfo
    }

    override fun onEvent(event: AttachmentEvents) {
        when (event) {
            is AttachmentEvents.VideoEvent -> {
                // Pausing (the clip ending included) always surfaces the button, even if the
                // chrome was never hidden and it is still locked away.
                if (!event.isPlaying && isPlaying && !centerButtonUnlocked) {
                    centerButtonUnlocked = true
                    refreshCenterButton()
                }
                views.overlayCenterPlayPause.setImageResource(if (!event.isPlaying) R.drawable.ic_play_arrow else R.drawable.ic_pause)
                views.overlayVideoTime.text = context.getString(
                        CommonStrings.video_position_of_duration, elapsed(event.progress), elapsed(event.duration)
                )
                if (!suspendSeekBarUpdate) {
                    isPlaying = event.isPlaying
                    val bar = views.overlaySeekBar
                    seekBarAnimator?.cancel()
                    if (bar.max != event.duration.coerceAtLeast(1)) {
                        bar.max = event.duration.coerceAtLeast(1)
                        bar.progress = event.progress
                    } else {
                        // Millisecond resolution plus a linear glide between the 100ms reports,
                        // the way Telegram interpolates its scrubber; jumps (seeks, loops) snap.
                        val delta = event.progress - bar.progress
                        if (event.isPlaying && delta in 0..1200) {
                            seekBarAnimator = ObjectAnimator.ofInt(bar, "progress", event.progress).apply {
                                duration = 120L
                                interpolator = LinearInterpolator()
                                start()
                            }
                        } else {
                            bar.progress = event.progress
                        }
                    }
                }
            }
        }
    }
}
