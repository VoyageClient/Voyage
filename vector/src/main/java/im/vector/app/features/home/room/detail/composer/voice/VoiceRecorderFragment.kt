/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer.voice

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.airbnb.mvrx.parentFragmentViewModel
import com.airbnb.mvrx.withState
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.databinding.FragmentVoiceRecorderBinding
import im.vector.app.features.home.room.detail.TimelineViewModel
import im.vector.app.features.home.room.detail.composer.MessageComposerAction
import im.vector.app.features.home.room.detail.composer.MessageComposerViewModel
import im.vector.app.features.home.room.detail.timeline.helper.AudioMessagePlaybackTracker
import javax.inject.Inject

@AndroidEntryPoint
class VoiceRecorderFragment : VectorBaseFragment<FragmentVoiceRecorderBinding>() {

    @Inject lateinit var audioMessagePlaybackTracker: AudioMessagePlaybackTracker

    private val timelineViewModel: TimelineViewModel by parentFragmentViewModel()
    private val messageComposerViewModel: MessageComposerViewModel by parentFragmentViewModel()

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentVoiceRecorderBinding {
        return FragmentVoiceRecorderBinding.inflate(inflater, container, false)
    }

    override fun onResume() {
        super.onResume()

        setupVoiceMessageView()
    }

    override fun onPause() {
        super.onPause()

        // Playback listeners belong to the current view instance.
        audioMessagePlaybackTracker.untrack(AudioMessagePlaybackTracker.RECORDING_ID, views.voiceMessageRecorderView)
        audioMessagePlaybackTracker.pauseAllPlaybacks()
    }

    override fun invalidate() = withState(timelineViewModel, messageComposerViewModel) { mainState, messageComposerState ->
        if (mainState.tombstoneEvent != null) return@withState

        views.root.isVisible = messageComposerState.isComposerVisible && messageComposerState.isVoiceRecording
        views.root.render(messageComposerState.voiceRecordingUiState)
    }

    private fun setupVoiceMessageView() {
        audioMessagePlaybackTracker.track(AudioMessagePlaybackTracker.RECORDING_ID, views.voiceMessageRecorderView)
        views.voiceMessageRecorderView.callback = object : VoiceMessageRecorderView.Callback {

            override fun onVoicePlaybackButtonClicked() {
                messageComposerViewModel.handle(MessageComposerAction.PlayOrPauseRecordingPlayback)
            }

            override fun onDeleteVoiceMessage() {
                messageComposerViewModel.handle(
                        MessageComposerAction.EndRecordingVoiceMessage(isCancelled = true, rootThreadEventId = getRootThreadEventId())
                )
                updateRecordingUiState(VoiceMessageRecorderView.RecordingUiState.Idle)
            }

            override fun onRecordingLimitReached() = pauseRecording()

            override fun onRecordingWaveformClicked() = pauseRecording()

            override fun onVoiceWaveformTouchedUp(percentage: Float, duration: Int) {
                messageComposerViewModel.handle(
                        MessageComposerAction.VoiceWaveformTouchedUp(AudioMessagePlaybackTracker.RECORDING_ID, duration, percentage)
                )
            }

            override fun onVoiceWaveformMoved(percentage: Float, duration: Int) {
                messageComposerViewModel.handle(
                        MessageComposerAction.VoiceWaveformTouchedUp(AudioMessagePlaybackTracker.RECORDING_ID, duration, percentage)
                )
            }

            private fun updateRecordingUiState(state: VoiceMessageRecorderView.RecordingUiState) {
                messageComposerViewModel.handle(
                        MessageComposerAction.OnVoiceRecordingUiStateChanged(state)
                )
            }

            private fun pauseRecording() {
                messageComposerViewModel.handle(
                        MessageComposerAction.PauseRecordingVoiceMessage
                )
                updateRecordingUiState(VoiceMessageRecorderView.RecordingUiState.Draft)
            }
        }
    }

    private fun getRootThreadEventId(): String? = withState(timelineViewModel) { it.rootThreadEventId }
}
