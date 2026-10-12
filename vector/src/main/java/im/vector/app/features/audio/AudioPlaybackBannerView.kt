/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.audio

import android.content.Context
import android.graphics.Bitmap
import android.text.format.DateUtils
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import im.vector.app.R
import im.vector.app.databinding.ViewAudioPlaybackBannerBinding
import im.vector.app.features.attachments.preview.AudioDetails
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.core.utils.text.neutralizeDirectionOverrides
import im.vector.lib.strings.CommonStrings

class AudioPlaybackBannerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
        LinearLayout(context, attrs, defStyleAttr) {
    private val views: ViewAudioPlaybackBannerBinding
    private var artwork: Bitmap? = null
    private var displayedPlaying: Boolean? = null
    var controller: AudioPlaybackController? = null
    var onControls: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        inflate(context, R.layout.view_audio_playback_banner, this)
        views = ViewAudioPlaybackBannerBinding.bind(this)
        views.audioPlayPause.setOnClickListener { controller?.toggle() }
        views.audioClose.setOnClickListener { controller?.stop() }
        views.audioTrack.setOnClickListener { onControls?.invoke() }
        views.audioProgress.trackColor = ColorUtils.setAlphaComponent(ThemeUtils.getColor(context, com.google.android.material.R.attr.colorPrimary), 51)
    }

    fun render(state: AudioPlaybackController.State?) {
        isVisible = state != null
        if (state == null) {
            views.audioArt.setImageDrawable(null)
            artwork = null
            return
        }
        views.audioTitle.text = state.title.neutralizeDirectionOverrides()
        views.audioSubtitle.text = state.credits(context)
        views.audioSubtitle.isVisible = views.audioSubtitle.text.isNotBlank()
        views.audioTime.text = context.getString(CommonStrings.video_position_of_duration,
                DateUtils.formatElapsedTime(state.position / 1000L), DateUtils.formatElapsedTime(state.duration / 1000L))
        views.audioArt.isVisible = state.art != null
        if (artwork !== state.art) {
            artwork = state.art
            if (state.art == null) {
                views.audioArt.setImageDrawable(null)
            } else {
                views.audioArt.setImageDrawable(AudioDetails.roundedSquareArt(context, state.art))
            }
        }
        views.audioProgress.progress = if (state.duration > 0) (state.position.toLong() * 1000 / state.duration).toInt().coerceIn(0, 1000) else 0
        views.audioPlayPause.isEnabled = !state.loading
        if (displayedPlaying != state.playing) {
            displayedPlaying = state.playing
            views.audioPlayPause.setImageDrawable(AppCompatResources.getDrawable(context, if (state.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow))
            views.audioPlayPause.contentDescription = context.getString(if (state.playing) CommonStrings.action_pause else CommonStrings.action_play)
        }
    }
}
