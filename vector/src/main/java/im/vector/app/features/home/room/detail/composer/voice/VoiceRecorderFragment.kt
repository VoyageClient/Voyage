/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer.voice

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.airbnb.mvrx.parentFragmentViewModel
import com.airbnb.mvrx.withState
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.hardware.vibrate
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.utils.PERMISSIONS_FOR_VOICE_MESSAGE
import im.vector.app.core.utils.checkPermissions
import im.vector.app.core.utils.registerForPermissionsResult
import im.vector.app.databinding.FragmentVoiceRecorderBinding
import im.vector.app.features.home.room.detail.TimelineViewModel
import im.vector.app.features.home.room.detail.composer.MessageComposerAction
import im.vector.app.features.home.room.detail.composer.MessageComposerViewEvents
import im.vector.app.features.home.room.detail.composer.MessageComposerViewModel
import im.vector.app.features.home.room.detail.composer.MessageComposerViewState
import im.vector.app.features.home.room.detail.composer.SendMode
import im.vector.app.features.home.room.detail.composer.boolean
import im.vector.app.features.home.room.detail.timeline.helper.AudioMessagePlaybackTracker
import javax.inject.Inject

@AndroidEntryPoint
class VoiceRecorderFragment : VectorBaseFragment<FragmentVoiceRecorderBinding>() {

    @Inject lateinit var audioMessagePlaybackTracker: AudioMessagePlaybackTracker

    private val timelineViewModel: TimelineViewModel by parentFragmentViewModel()
    private val messageComposerViewModel: MessageComposerViewModel by parentFragmentViewModel()

    // Denial is silent by design: a snackbar here covers the composer the user is reaching for.
    private val permissionVoiceMessageLauncher = registerForPermissionsResult { _, _ -> }

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentVoiceRecorderBinding {
        return FragmentVoiceRecorderBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        messageComposerViewModel.observeViewEvents {
            when (it) {
                is MessageComposerViewEvents.AnimateSendButtonVisibility -> handleSendButtonVisibilityChanged(it.isVisible)
                else -> Unit
            }
        }

        messageComposerViewModel.onEach(MessageComposerViewState::sendMode, MessageComposerViewState::canSendMessage) { mode, canSend ->
            if (!canSend.boolean()) {
                return@onEach
            }
            if (mode is SendMode.Voice) {
                views.voiceMessageRecorderView.isVisible = true
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Removed listeners should be set again
        setupVoiceMessageView()
    }

    override fun onPause() {
        super.onPause()

        // Symmetric with the (re-)track in onResume: listeners are per-instance now, so a stale
        // recorder view must deregister itself instead of being overwritten by the next track().
        audioMessagePlaybackTracker.untrack(AudioMessagePlaybackTracker.RECORDING_ID, views.voiceMessageRecorderView)
        audioMessagePlaybackTracker.pauseAllPlaybacks()
    }

    override fun invalidate() = withState(timelineViewModel, messageComposerViewModel) { mainState, messageComposerState ->
        if (mainState.tombstoneEvent != null) return@withState

        val hasVoiceDraft = messageComposerState.voiceRecordingUiState is VoiceMessageRecorderView.RecordingUiState.Draft
        val shouldBeVisible = messageComposerState.isVoiceMessageRecorderVisible || hasVoiceDraft
        with(views.root) {
            isVisible = shouldBeVisible
            if (shouldBeVisible) render(messageComposerState.voiceRecordingUiState)
        }
    }

    private fun handleSendButtonVisibilityChanged(isSendButtonVisible: Boolean) {
        // Voice states stack the recorder above the composer; only idle-typing swaps them.
        if (withState(messageComposerViewModel) { it.isVoiceRecording }) return
        if (isSendButtonVisible) {
            views.root.isVisible = false
        } else {
            views.root.alpha = 0f
            views.root.isVisible = true
            views.root.animate().alpha(1f).setDuration(150).start()
        }
    }

    private fun setupVoiceMessageView() {
        audioMessagePlaybackTracker.track(AudioMessagePlaybackTracker.RECORDING_ID, views.voiceMessageRecorderView)
        views.voiceMessageRecorderView.callback = object : VoiceMessageRecorderView.Callback {

            override fun onVoiceRecordingStarted() {
                if (checkPermissions(PERMISSIONS_FOR_VOICE_MESSAGE, requireActivity(), permissionVoiceMessageLauncher)) {
                    messageComposerViewModel.handle(MessageComposerAction.StartRecordingVoiceMessage)
                    vibrate(requireContext())
                }
            }

            override fun onVoicePlaybackButtonClicked() {
                messageComposerViewModel.handle(MessageComposerAction.PlayOrPauseRecordingPlayback)
            }

            override fun onVoiceRecordingCancelled() {
                messageComposerViewModel.handle(MessageComposerAction.EndRecordingVoiceMessage(isCancelled = true, rootThreadEventId = getRootThreadEventId()))
                vibrate(requireContext())
                updateRecordingUiState(VoiceMessageRecorderView.RecordingUiState.Idle)
            }

            override fun onVoiceRecordingEnded() {
                onSendVoiceMessage()
            }

            override fun onSendVoiceMessage() {
                messageComposerViewModel.handle(
                        MessageComposerAction.EndRecordingVoiceMessage(isCancelled = false, rootThreadEventId = getRootThreadEventId())
                )
                updateRecordingUiState(VoiceMessageRecorderView.RecordingUiState.Idle)
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

    /**
     * Returns the root thread event if we are in a thread room, otherwise returns null.
     */
    fun getRootThreadEventId(): String? = withState(timelineViewModel) { it.rootThreadEventId }
}
