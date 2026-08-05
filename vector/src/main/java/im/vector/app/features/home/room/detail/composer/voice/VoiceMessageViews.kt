/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer.voice

import android.content.res.ColorStateList
import android.content.res.Resources
import android.text.format.DateUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.ImageViewCompat
import im.vector.app.R
import im.vector.app.core.extensions.importantForAccessibilityCompat
import im.vector.app.core.extensions.setAttributeBackground
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.databinding.ViewVoiceMessageRecorderBinding
import im.vector.app.features.home.room.detail.composer.voice.VoiceMessageRecorderView.DraggingState
import im.vector.app.features.home.room.detail.timeline.helper.AudioMessagePlaybackTracker
import im.vector.app.features.themes.ThemeUtils
import im.vector.app.features.voice.AudioWaveformView
import im.vector.lib.strings.CommonStrings

class VoiceMessageViews(
        private val resources: Resources,
        private val views: ViewVoiceMessageRecorderBinding,
        private val dimensionConverter: DimensionConverter,
) {

    private val distanceToCancel = dimensionConverter.dpToPx(120).toFloat()
    private val rtlXMultiplier = resources.getInteger(im.vector.lib.ui.styles.R.integer.rtl_x_multiplier)

    private var classicComposer = false

    fun applyClassicComposerStyle() {
        classicComposer = true
        applyClassicMicStyle()
    }

    // Match the classic composer's other buttons: accent tint, no ripple. The mic drawable is
    // hardcoded to ?vctr_content_tertiary, so it needs an explicit tint rather than a theme attr.
    private fun applyClassicMicStyle() {
        views.voiceMessageMicButton.background = null
        val accent = ThemeUtils.getColor(views.voiceMessageMicButton.context, com.google.android.material.R.attr.colorAccent)
        ImageViewCompat.setImageTintList(views.voiceMessageMicButton, ColorStateList.valueOf(accent))
    }

    fun start(actions: Actions) {
        views.voiceMessageSendButton.setOnClickListener {
            views.voiceMessageSendButton.isVisible = false
            actions.onSendVoiceMessage()
        }

        views.voiceMessageDeletePlayback.setOnClickListener {
            views.voiceMessageSendButton.isVisible = false
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
        observeMicButton(actions)
    }

    private fun getTouchedPositionPercentage(motionEvent: MotionEvent, view: View) = (motionEvent.x / view.width).coerceIn(0f, 1f)

    private fun observeMicButton(actions: Actions) {
        views.voiceMessageMicButton.setOnClickListener {
            actions.onRequestRecording()
        }
    }

    fun renderStarted(distanceX: Float) {
        val translationAmount = distanceX.coerceAtMost(distanceToCancel)
        views.voiceMessageMicButton.translationX = -translationAmount * rtlXMultiplier
        views.voiceMessageSlideToCancel.translationX = -translationAmount / 2 * rtlXMultiplier
    }

    fun renderCancelling(distanceX: Float) {
        val translationAmount = distanceX.coerceAtMost(distanceToCancel)
        views.voiceMessageMicButton.translationX = -translationAmount * rtlXMultiplier
        views.voiceMessageSlideToCancel.translationX = -translationAmount / 2 * rtlXMultiplier
        val reducedAlpha = (1 - translationAmount / distanceToCancel / 1.5).toFloat()
        views.voiceMessageSlideToCancel.alpha = reducedAlpha
        views.voiceMessageTimerIndicator.alpha = reducedAlpha
        views.voiceMessageTimer.alpha = reducedAlpha
        views.voiceMessageSlideToCancelDivider.isVisible = true
        views.voiceMessageMicButton.translationY = 0F
    }

    fun hideRecordingViews(resetMic: Boolean) {
        views.voiceMessageBackgroundView.isVisible = false
        views.voiceMessageSlideToCancelDivider.isVisible = false
        views.voiceMessageSlideToCancel.isVisible = false
        views.voiceMessageSlideToCancel.animate().translationX(0f).translationY(0f).start()
        views.voiceMessagePlaybackLayout.isVisible = false
        views.voiceMessageTimerIndicator.isVisible = false
        views.voiceMessageTimer.isVisible = false

        if (resetMic) {
            ViewCompat.animate(views.voiceMessageMicButton)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationX(0f)
                    .translationY(0f)
                    .setDuration(150L)
                    .withEndAction {
                        resetMicButtonUi()
                    }
                    .start()
        } else {
            views.voiceMessageMicButton.apply {
                scaleX = 1f
                scaleY = 1f
                translationX = 0f
                translationY = 0f
            }
        }
        hideToast()
    }

    fun resetMicButtonUi() {
        views.voiceMessageMicButton.isVisible = true
        views.voiceMessageMicButton.setImageResource(R.drawable.ic_microphone)
        views.voiceMessageMicButton.setAttributeBackground(android.R.attr.selectableItemBackgroundBorderless)
        views.voiceMessageMicButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            if (rtlXMultiplier == -1) {
                // RTL
                setMargins(dimensionConverter.dpToPx(12), 0, 0, dimensionConverter.dpToPx(12))
            } else {
                setMargins(0, 0, dimensionConverter.dpToPx(12), dimensionConverter.dpToPx(12))
            }
        }
        if (classicComposer) applyClassicMicStyle()
    }

    fun hideToast() {
        views.voiceMessageToast.isVisible = false
    }

    fun showDraftViews() {
        hideRecordingViews(resetMic = false)
        views.voiceMessageBackgroundView.isVisible = true
        views.voiceMessageMicButton.isVisible = false
        views.voiceMessageSendButton.isVisible = false
        views.voiceMessagePlaybackLayout.isVisible = true
        views.voiceMessagePlaybackTimerIndicator.isVisible = false
        views.voicePlaybackControlButton.isVisible = true
        views.voicePlaybackWaveform.importantForAccessibilityCompat = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun showRecordingViews() {
        hideRecordingViews(resetMic = false)
        views.voiceMessageMicButton.isVisible = false
        views.voiceMessageBackgroundView.isVisible = true
        views.voiceMessagePlaybackLayout.isVisible = true
        views.voiceMessagePlaybackTimerIndicator.isVisible = true
        views.voicePlaybackControlButton.isVisible = false
        views.voiceMessageSendButton.isVisible = false
        views.voicePlaybackWaveform.importantForAccessibilityCompat = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        renderToast(resources.getString(CommonStrings.voice_message_tap_to_stop_toast))
    }

    fun initViews() {
        hideRecordingViews(resetMic = true)
        views.voiceMessageMicButton.isVisible = true
        views.voiceMessageSendButton.isVisible = false
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

    fun renderVisibilityChanged(parentChanged: Boolean, visibility: Int) {
        if (parentChanged && visibility == ConstraintLayout.VISIBLE) {
            views.voiceMessageMicButton.contentDescription = resources.getString(CommonStrings.a11y_start_voice_message)
        } else {
            views.voiceMessageMicButton.contentDescription = ""
        }
    }

    interface Actions {
        fun onRequestRecording()
        fun onMicButtonReleased()
        fun onMicButtonDrag(nextDragStateCreator: (DraggingState) -> DraggingState)
        fun onSendVoiceMessage()
        fun onDeleteVoiceMessage()
        fun onWaveformClicked()
        fun onVoicePlaybackButtonClicked()
        fun onVoiceWaveformTouchedUp(percentage: Float)
        fun onVoiceWaveformMoved(percentage: Float)
    }
}
