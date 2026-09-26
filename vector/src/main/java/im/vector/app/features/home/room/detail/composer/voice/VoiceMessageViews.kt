/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer.voice

import android.content.res.Resources
import android.text.format.DateUtils
import android.view.MotionEvent
import android.view.View
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import im.vector.app.R
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
    }

    private fun getTouchedPositionPercentage(motionEvent: MotionEvent, view: View) = (motionEvent.x / view.width).coerceIn(0f, 1f)

    private fun hideRecordingViews() {
        views.voiceMessageBackgroundView.isVisible = false
        views.voiceMessagePlaybackLayout.isVisible = false
        hideToast()
    }

    private fun hideToast() {
        views.voiceMessageToast.isVisible = false
    }

    fun showDraftViews() {
        hideRecordingViews()
        views.voiceMessageBackgroundView.isVisible = true
        views.voiceMessagePlaybackLayout.isVisible = true
        views.voiceMessagePlaybackTimerIndicator.isVisible = false
        views.voicePlaybackControlButton.isVisible = true
        views.voicePlaybackWaveform.importantForAccessibilityCompat = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun showRecordingViews() {
        hideRecordingViews()
        views.voiceMessageBackgroundView.isVisible = true
        views.voiceMessagePlaybackLayout.isVisible = true
        views.voiceMessagePlaybackTimerIndicator.isVisible = true
        views.voicePlaybackControlButton.isVisible = false
        views.voicePlaybackWaveform.importantForAccessibilityCompat = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        renderToast(resources.getString(CommonStrings.voice_message_tap_to_stop_toast))
    }

    fun initViews() {
        hideRecordingViews()
        views.voicePlaybackWaveform.post { views.voicePlaybackWaveform.clear() }
    }

    fun renderPlaying(state: AudioMessagePlaybackTracker.Listener.State.Playing) {
        views.voicePlaybackControlButton.setImageResource(R.drawable.ic_play_pause_pause)
        views.voicePlaybackControlButton.contentDescription = resources.getString(CommonStrings.a11y_pause_voice_message)
        val formattedTimerText = DateUtils.formatElapsedTime((state.playbackTime / 1000).toLong())
        views.voicePlaybackTime.text = formattedTimerText
        val waveformColorIdle = ThemeUtils.getColor(views.voicePlaybackWaveform.context, im.vector.lib.ui.styles.R.attr.vctr_content_quaternary)
        val waveformColorPlayed = ThemeUtils.getColor(views.voicePlaybackWaveform.context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
        views.voicePlaybackWaveform.updateColors(state.percentage, waveformColorPlayed, waveformColorIdle)
    }

    fun renderIdle() {
        views.voicePlaybackControlButton.setImageResource(R.drawable.ic_play_pause_play)
        views.voicePlaybackControlButton.contentDescription = resources.getString(CommonStrings.a11y_play_voice_message)
        views.voicePlaybackWaveform.summarize()
    }

    fun renderToast(message: String) {
        views.voiceMessageToast.removeCallbacks(hideToastRunnable)
        views.voiceMessageToast.text = message
        views.voiceMessageToast.isVisible = true
        views.voiceMessageToast.postDelayed(hideToastRunnable, 2_000)
    }

    private val hideToastRunnable = Runnable {
        views.voiceMessageToast.isVisible = false
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
                // The tracker resends the full amplitude list each tick, so rebuild instead of appending
                clear()
                amplitudeList.forEach { add(AudioWaveformView.FFT(it.toFloat(), waveformColor)) }
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
