/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer.voice

import android.content.res.Resources
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.view.MotionEvent
import android.view.View
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import im.vector.app.R
import im.vector.app.core.extensions.backgroundCompat
import im.vector.app.core.extensions.importantForAccessibilityCompat
import im.vector.app.databinding.ViewVoiceMessageRecorderBinding
import im.vector.app.features.home.room.detail.timeline.helper.AudioMessagePlaybackTracker
import im.vector.app.features.themes.ThemeUtils
import im.vector.app.features.voice.AudioWaveformView
import im.vector.lib.strings.CommonStrings

class VoiceMessageViews(
        private val resources: Resources,
        private val views: ViewVoiceMessageRecorderBinding,
) {

    fun start(actions: Actions) {
        views.voiceMessageDeletePlayback.setOnClickListener {
            actions.onDeleteVoiceMessage()
        }

        views.voicePlaybackWaveform.setOnTouchListener { view, motionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    actions.onWaveformClicked()
                }
                MotionEvent.ACTION_UP -> {
                    val percentage = getTouchedPositionPercentage(motionEvent, view)
                    actions.onVoiceWaveformTouchedUp(percentage)
                }
                MotionEvent.ACTION_MOVE -> {
                    val percentage = getTouchedPositionPercentage(motionEvent, view)
                    actions.onVoiceWaveformMoved(percentage)
                }
            }
            true
        }

        views.voicePlaybackControlButton.setOnClickListener {
            actions.onVoicePlaybackButtonClicked()
        }
        // The waveform is narrow on small screens, so the whole field takes the tap that stops a recording.
        views.voiceMessageField.setOnClickListener {
            actions.onWaveformClicked()
        }
    }

    /** The modern composer boxes its text field, so the clip gets the same box; the classic bar is flat. */
    fun applyComposerStyle(classic: Boolean) {
        if (classic) {
            views.voiceMessageField.backgroundCompat = null
            return
        }
        val density = resources.displayMetrics.density
        // Built here rather than from the composer's XML drawable: a theme attribute inside a shape needs API 21.
        views.voiceMessageField.backgroundCompat = GradientDrawable().apply {
            cornerRadius = 8 * density
            setStroke(
                    (density + 0.5f).toInt().coerceAtLeast(1),
                    ThemeUtils.getColor(views.root.context, im.vector.lib.ui.styles.R.attr.vctr_content_quinary)
            )
        }
    }

    private fun getTouchedPositionPercentage(motionEvent: MotionEvent, view: View) = (motionEvent.x / view.width).coerceIn(0f, 1f)

    fun showDraftViews() {
        views.voiceMessagePlaybackTimerIndicator.isVisible = false
        views.voicePlaybackControlButton.isVisible = true
        views.voiceMessageField.isClickable = false
        views.voicePlaybackWaveform.importantForAccessibilityCompat = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun showRecordingViews() {
        views.voiceMessagePlaybackTimerIndicator.isVisible = true
        views.voicePlaybackControlButton.isVisible = false
        views.voiceMessageField.isClickable = true
        views.voicePlaybackWaveform.importantForAccessibilityCompat = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        unplayedTimeText = null
    }

    fun initViews() {
        unplayedTimeText = null
        views.voicePlaybackWaveform.post { views.voicePlaybackWaveform.clear() }
    }

    // The recording's length, shown again once playback ends.
    private var unplayedTimeText: CharSequence? = null

    fun renderPlaying(state: AudioMessagePlaybackTracker.Listener.State.Playing) {
        if (unplayedTimeText == null) unplayedTimeText = views.voicePlaybackTime.text
        renderPlayButton(playing = true)
        views.voicePlaybackTime.text = DateUtils.formatElapsedTime((state.playbackTime / 1000).toLong())
        renderProgress(state.percentage)
    }

    fun renderPaused() = renderPlayButton(playing = false)

    fun renderIdle() {
        renderPlayButton(playing = false)
        unplayedTimeText?.let { views.voicePlaybackTime.text = it }
        unplayedTimeText = null
        views.voicePlaybackWaveform.summarize()
        renderProgress(0f)
    }

    private fun renderPlayButton(playing: Boolean) {
        views.voicePlaybackControlButton.setImageResource(if (playing) R.drawable.ic_play_pause_pause else R.drawable.ic_play_pause_play)
        views.voicePlaybackControlButton.contentDescription =
                resources.getString(if (playing) CommonStrings.a11y_pause_voice_message else CommonStrings.a11y_play_voice_message)
    }

    private fun renderProgress(percentage: Float) {
        val context = views.voicePlaybackWaveform.context
        views.voicePlaybackWaveform.updateColors(
                percentage,
                ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary),
                ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_quaternary),
        )
    }

    fun renderRecordingTimer(recordingTimeMillis: Long) {
        val formattedTimerText = DateUtils.formatElapsedTime(recordingTimeMillis)
        views.voicePlaybackTime.post {
            views.voicePlaybackTime.text = formattedTimerText
        }
    }

    fun renderRecordingWaveform(amplitudeList: List<Int>) {
        views.voicePlaybackWaveform.doOnLayout { waveFormView ->
            val waveformColor = ThemeUtils.getColor(waveFormView.context, im.vector.lib.ui.styles.R.attr.vctr_content_quaternary)
            (waveFormView as AudioWaveformView).apply {
                // Rebuilt each tick from the full list, normalized as it will be sent and stretched over the full width.
                clear()
                AudioWaveformView.normalize(amplitudeList, AudioWaveformView.MAX_FFT).forEach { add(AudioWaveformView.FFT(it.toFloat(), waveformColor)) }
                summarize()
            }
        }
    }

    interface Actions {
        fun onDeleteVoiceMessage()
        fun onWaveformClicked()
        fun onVoicePlaybackButtonClicked()
        fun onVoiceWaveformTouchedUp(percentage: Float)
        fun onVoiceWaveformMoved(percentage: Float)
    }
}
