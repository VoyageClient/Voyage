/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer.voice

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.databinding.ViewVoiceMessageRecorderBinding
import im.vector.app.features.home.room.detail.timeline.helper.AudioMessagePlaybackTracker
import im.vector.lib.core.utils.timer.Clock
import im.vector.lib.core.utils.timer.CountUpTimer
import javax.inject.Inject

/**
 * Encapsulates the voice message recording view and animations.
 */
@AndroidEntryPoint
class VoiceMessageRecorderView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr), AudioMessagePlaybackTracker.Listener {

    interface Callback {
        fun onVoicePlaybackButtonClicked()
        fun onDeleteVoiceMessage()
        fun onRecordingWaveformClicked()
        fun onVoiceWaveformTouchedUp(percentage: Float, duration: Int)
        fun onVoiceWaveformMoved(percentage: Float, duration: Int)
    }

    @Inject lateinit var clock: Clock

    private val voiceMessageViews: VoiceMessageViews
    lateinit var callback: Callback

    private var recordingTicker: CountUpTimer? = null
    private var lastKnownState: RecordingUiState? = null
    private var recordingDuration: Long = 0

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.composer_min_height)
        inflate(this.context, R.layout.view_voice_message_recorder, this)
        voiceMessageViews = VoiceMessageViews(
                this.context.resources,
                ViewVoiceMessageRecorderBinding.bind(this)
        )
        initListeners()
    }

    private fun initListeners() {
        voiceMessageViews.start(object : VoiceMessageViews.Actions {
            override fun onDeleteVoiceMessage() = callback.onDeleteVoiceMessage()
            override fun onWaveformClicked() {
                when (lastKnownState) {
                    is RecordingUiState.Recording -> callback.onRecordingWaveformClicked()
                    else -> Unit
                }
            }

            override fun onVoicePlaybackButtonClicked() = callback.onVoicePlaybackButtonClicked()
            override fun onVoiceWaveformTouchedUp(percentage: Float) {
                if (lastKnownState == RecordingUiState.Draft) {
                    callback.onVoiceWaveformTouchedUp(percentage, recordingDuration.toInt())
                }
            }

            override fun onVoiceWaveformMoved(percentage: Float) {
                if (lastKnownState == RecordingUiState.Draft) {
                    callback.onVoiceWaveformMoved(percentage, recordingDuration.toInt())
                }
            }
        })
    }

    fun applyComposerStyle(classic: Boolean) = voiceMessageViews.applyComposerStyle(classic)

    fun render(recordingState: RecordingUiState) {
        if (lastKnownState == recordingState) return
        when (recordingState) {
            RecordingUiState.Idle -> {
                reset()
            }
            is RecordingUiState.Recording -> {
                if (lastKnownState !is RecordingUiState.Recording) {
                    startRecordingTicker(startAt = recordingState.recordingStartTimestamp)
                }
                voiceMessageViews.showRecordingViews()
            }
            RecordingUiState.Draft -> {
                stopRecordingTicker()
                voiceMessageViews.showDraftViews()
            }
        }
        lastKnownState = recordingState
    }

    private fun reset() {
        stopRecordingTicker()
        voiceMessageViews.initViews()
    }

    private fun startRecordingTicker(startAt: Long) {
        val startMs = ((clock.epochMillis() - startAt)).coerceAtLeast(0)
        recordingTicker?.stop()
        recordingTicker = CountUpTimer().apply {
            tickListener = CountUpTimer.TickListener { milliseconds ->
                onRecordingTick(milliseconds + startMs)
            }
            start()
        }
        onRecordingTick(milliseconds = startMs)
    }

    private fun onRecordingTick(milliseconds: Long) {
        voiceMessageViews.renderRecordingTimer(milliseconds / 1_000)
    }

    private fun stopRecordingTicker() {
        recordingDuration = recordingTicker?.elapsedTime() ?: 0
        recordingTicker?.stop()
        recordingTicker = null
    }

    override fun onUpdate(state: AudioMessagePlaybackTracker.Listener.State) {
        when (state) {
            is AudioMessagePlaybackTracker.Listener.State.Recording -> {
                voiceMessageViews.renderRecordingWaveform(state.amplitudeList.toList())
                // Re-applying a draft (as on every return to the app) stops its playback without telling this view.
                if (lastKnownState == RecordingUiState.Draft) voiceMessageViews.renderIdle()
            }
            is AudioMessagePlaybackTracker.Listener.State.Playing -> {
                voiceMessageViews.renderPlaying(state)
            }
            is AudioMessagePlaybackTracker.Listener.State.Paused -> {
                voiceMessageViews.renderPaused()
            }
            is AudioMessagePlaybackTracker.Listener.State.Error,
            is AudioMessagePlaybackTracker.Listener.State.Idle -> {
                voiceMessageViews.renderIdle()
            }
        }
    }

    sealed interface RecordingUiState {
        object Idle : RecordingUiState
        data class Recording(val recordingStartTimestamp: Long) : RecordingUiState
        object Draft : RecordingUiState
    }
}
