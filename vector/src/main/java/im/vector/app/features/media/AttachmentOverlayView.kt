/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.text.format.DateUtils
import android.util.AttributeSet
import android.view.Menu
import android.widget.SeekBar
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.PopupMenu
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isVisible
import im.vector.app.R
import im.vector.app.databinding.MergeImageAttachmentOverlayBinding
import im.vector.app.features.attachments.editor.video.PlaybackSpeed
import im.vector.app.features.attachments.editor.video.PlaybackSpeedDialog
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.attachmentviewer.AttachmentEventListener
import im.vector.lib.attachmentviewer.AttachmentEvents
import im.vector.lib.strings.CommonStrings

class AttachmentOverlayView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr), AttachmentEventListener {

    var interactionListener: AttachmentInteractionListener? = null
    val views: MergeImageAttachmentOverlayBinding

    private var isPlaying = false
    private var suspendSeekBarUpdate = false
    private var playbackSpeed = PlaybackSpeed()
    private var isVideo = false

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
        views.overlayPlayPauseButton.setOnClickListener {
            interactionListener?.onPlayPause(!isPlaying)
        }

        views.overlaySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = Unit

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                suspendSeekBarUpdate = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let { interactionListener?.videoSeekTo(it.progress) }
                suspendSeekBarUpdate = false
            }
        })
    }

    /** Called on every change of attachment, where the speed goes back to normal with the holder's. */
    fun showVideoControls(show: Boolean) {
        views.overlayVideoControlsGroup.isVisible = show
        isVideo = show
        playbackSpeed = PlaybackSpeed()
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
        val tint = ThemeUtils.getColor(themed, im.vector.lib.ui.styles.R.attr.vctr_content_primary)
        for (index in 0 until menu.size()) {
            val icon = menu.getItem(index).icon?.mutate() ?: continue
            DrawableCompat.setTint(icon, tint)
            menu.getItem(index).icon = icon
        }
    }

    private fun showSpeedDialog() {
        PlaybackSpeedDialog(
                // The viewer's own theme is a bare fullscreen one, so the sheet takes the app's.
                context = ContextThemeWrapper(context, ThemeUtils.getApplicationThemeRes(context)),
                initial = playbackSpeed,
                allowPitchChoice = true,
                onChanged = { speed ->
                    playbackSpeed = speed
                    interactionListener?.onPlaybackSpeedChanged(speed.speed, speed.changePitch)
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
                views.overlayPlayPauseButton.setImageResource(if (!event.isPlaying) R.drawable.ic_play_arrow else R.drawable.ic_pause)
                views.overlayVideoTime.text = context.getString(
                        CommonStrings.video_position_of_duration, elapsed(event.progress), elapsed(event.duration)
                )
                if (!suspendSeekBarUpdate) {
                    val safeDuration = (if (event.duration == 0) 100 else event.duration).toFloat()
                    val percent = ((event.progress / safeDuration) * 100f).toInt().coerceAtMost(100)
                    isPlaying = event.isPlaying
                    views.overlaySeekBar.progress = percent
                }
            }
        }
    }
}
