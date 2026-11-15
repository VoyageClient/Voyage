/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import dagger.hilt.android.scopes.ActivityScoped
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.utils.TextUtils
import im.vector.app.features.home.room.detail.timeline.MessageColorProvider
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.content.ContentUploadStateTracker
import org.matrix.android.sdk.api.session.room.send.SendState
import javax.inject.Inject

@ActivityScoped
class ContentUploadStateTrackerBinder @Inject constructor(
        private val activeSessionHolder: ActiveSessionHolder,
        private val messageColorProvider: MessageColorProvider,
) {

    private data class Binding(
            val eventId: String,
            val tracker: ContentUploadStateTracker,
            val listener: ContentUploadStateTracker.UpdateListener,
    )

    private val bindings = mutableMapOf<ViewGroup, Binding>()

    fun bind(
            eventId: String,
            isLocalFile: Boolean,
            progressLayout: ViewGroup
    ) {
        bindings.remove(progressLayout)?.also { it.tracker.untrack(it.eventId, it.listener) }
        val session = activeSessionHolder.getSafeActiveSession() ?: return
        val tracker = session.contentUploadProgressTracker()
        val listener = ContentMediaProgressUpdater(progressLayout, isLocalFile, messageColorProvider)
        bindings[progressLayout] = Binding(eventId, tracker, listener)
        tracker.track(eventId, listener)
    }

    fun unbind(eventId: String, progressLayout: ViewGroup) {
        val binding = bindings[progressLayout]?.takeIf { it.eventId == eventId } ?: return
        bindings.remove(progressLayout)
        binding.tracker.untrack(binding.eventId, binding.listener)
    }

    fun clear() {
        bindings.values.forEach { it.tracker.untrack(it.eventId, it.listener) }
        bindings.clear()
    }
}

internal class ContentMediaProgressUpdater(
        private val progressLayout: ViewGroup,
        private val isLocalFile: Boolean,
        private val messageColorProvider: MessageColorProvider,
) : ContentUploadStateTracker.UpdateListener {

    private val progressBar: ProgressBar = progressLayout.findViewById(R.id.mediaProgressBar)
    private val progressTextView: TextView = progressLayout.findViewById(R.id.mediaProgressTextView)

    override fun onUpdate(state: ContentUploadStateTracker.State) {
        when (state) {
            is ContentUploadStateTracker.State.Idle -> handleIdle()
            is ContentUploadStateTracker.State.EncryptingThumbnail -> handleEncryptingThumbnail()
            is ContentUploadStateTracker.State.UploadingThumbnail -> handleProgressThumbnail(state)
            is ContentUploadStateTracker.State.Encrypting -> handleEncrypting(state)
            is ContentUploadStateTracker.State.Uploading -> handleProgress(state)
            is ContentUploadStateTracker.State.UploadingGalleryItem -> handleGalleryProgress(state)
            is ContentUploadStateTracker.State.Failure -> handleFailure()
            is ContentUploadStateTracker.State.Success -> handleSuccess()
            is ContentUploadStateTracker.State.CompressingImage -> handleCompressingImage()
            is ContentUploadStateTracker.State.CompressingVideo -> handleCompressingVideo(state)
            is ContentUploadStateTracker.State.ProcessingAudio -> handleProcessingAudio()
            is ContentUploadStateTracker.State.ProcessingVideo -> handleProcessingVideo(state)
            is ContentUploadStateTracker.State.PreparingThumbnail -> handlePreparingThumbnail()
        }
    }

    private fun handlePreparingThumbnail() {
        progressLayout.visibility = View.VISIBLE
        progressBar.isVisible = true
        progressBar.isIndeterminate = true
        progressTextView.isVisible = true
        progressTextView.text = progressLayout.context.getString(CommonStrings.send_file_step_preparing_thumbnail)
        progressTextView.setTextColor(messageColorProvider.getMessageTextColor(SendState.SENDING))
    }

    private fun handleProcessingVideo(state: ContentUploadStateTracker.State.ProcessingVideo) {
        val percent = (state.percent * 100).toInt().coerceIn(0, 100)
        progressLayout.visibility = View.VISIBLE
        progressBar.isVisible = true
        progressBar.isIndeterminate = false
        progressBar.progress = percent
        progressTextView.isVisible = true
        progressTextView.text = progressLayout.context.getString(CommonStrings.send_file_step_processing_video, percent)
        progressTextView.setTextColor(messageColorProvider.getMessageTextColor(SendState.SENDING))
    }

    private fun handleProcessingAudio() {
        progressLayout.visibility = View.VISIBLE
        progressBar.isVisible = true
        progressBar.isIndeterminate = true
        progressTextView.isVisible = true
        progressTextView.text = progressLayout.context.getString(CommonStrings.send_file_step_processing_audio)
        progressTextView.setTextColor(messageColorProvider.getMessageTextColor(SendState.SENDING))
    }

    private fun handleIdle() {
        if (isLocalFile) {
            progressLayout.isVisible = true
            progressBar.isVisible = true
            progressBar.isIndeterminate = true
            progressBar.progress = 0
            progressTextView.text = progressLayout.context.getString(CommonStrings.send_file_step_idle)
            progressTextView.setTextColor(messageColorProvider.getMessageTextColor(SendState.UNSENT))
        } else {
            progressLayout.isVisible = false
        }
    }

    private fun handleEncryptingThumbnail() {
        doHandleEncrypting(CommonStrings.send_file_step_encrypting_thumbnail, 0, 0)
    }

    private fun handleProgressThumbnail(state: ContentUploadStateTracker.State.UploadingThumbnail) {
        doHandleProgress(CommonStrings.send_file_step_sending_thumbnail, state.current, state.total, state.isRetrying, state.isAwaitingResponse)
    }

    private fun handleEncrypting(state: ContentUploadStateTracker.State.Encrypting) {
        doHandleEncrypting(CommonStrings.send_file_step_encrypting_file, state.current, state.total)
    }

    private fun handleProgress(state: ContentUploadStateTracker.State.Uploading) {
        doHandleProgress(CommonStrings.send_file_step_sending_file, state.current, state.total, state.isRetrying, state.isAwaitingResponse)
    }

    @SuppressLint("SetTextI18n")
    private fun handleGalleryProgress(state: ContentUploadStateTracker.State.UploadingGalleryItem) {
        doHandleProgress(CommonStrings.send_file_step_sending_file, state.overallCurrent, state.overallTotal, state.isRetrying, state.isAwaitingResponse)
        progressTextView.text = "${progressTextView.text} (${state.completedCount}/${state.itemCount})"
    }

    private fun handleCompressingImage() {
        progressLayout.visibility = View.VISIBLE
        progressBar.isVisible = true
        progressBar.isIndeterminate = true
        progressTextView.isVisible = true
        progressTextView.text = progressLayout.context.getString(CommonStrings.send_file_step_compressing_image)
        progressTextView.setTextColor(messageColorProvider.getMessageTextColor(SendState.SENDING))
    }

    @SuppressLint("StringFormatMatches")
    private fun handleCompressingVideo(state: ContentUploadStateTracker.State.CompressingVideo) {
        progressLayout.visibility = View.VISIBLE
        progressBar.isVisible = true
        progressBar.isIndeterminate = false
        progressBar.progress = state.percent.toInt()
        progressTextView.isVisible = true
        progressTextView.text = progressLayout.context.getString(CommonStrings.send_file_step_compressing_video, state.percent.toInt())
        progressTextView.setTextColor(messageColorProvider.getMessageTextColor(SendState.SENDING))
    }

    private fun doHandleEncrypting(resId: Int, current: Long, total: Long) {
        progressLayout.visibility = View.VISIBLE
        val percent = if (total > 0) (100L * (current.toFloat() / total.toFloat())) else 0f
        progressBar.isIndeterminate = false
        progressBar.progress = percent.toInt()
        progressTextView.isVisible = true
        progressTextView.text = progressLayout.context.getString(resId)
        progressTextView.setTextColor(messageColorProvider.getMessageTextColor(SendState.ENCRYPTING))
    }

    private fun doHandleProgress(resId: Int, current: Long, total: Long, isRetrying: Boolean = false, isAwaitingResponse: Boolean = false) {
        progressLayout.visibility = View.VISIBLE
        val percent = if (total > 0) (100.0 * current / total).toInt().coerceIn(0, 100) else 0
        progressBar.isVisible = true
        progressBar.isIndeterminate = total <= 0
        progressBar.progress = percent
        progressTextView.isVisible = true
        progressTextView.text = when {
            isRetrying -> progressLayout.context.getString(CommonStrings.send_file_step_retrying_upload)
            isAwaitingResponse -> progressLayout.context.getString(CommonStrings.send_file_step_waiting_for_server)
            total <= 0 -> progressLayout.context.getString(CommonStrings.send_file_step_sending_unknown_size)
            else -> progressLayout.context.getString(
                    resId,
                    TextUtils.formatFileSize(progressLayout.context, current, true),
                    TextUtils.formatFileSize(progressLayout.context, total.coerceAtLeast(0), true)
            )
        }
        progressTextView.setTextColor(messageColorProvider.getMessageTextColor(SendState.SENDING))
    }

    // The message's own send state reports the failure; the raw error is too technical to show here.
    private fun handleFailure() {
        progressLayout.visibility = View.GONE
    }

    private fun handleSuccess() {
        progressLayout.visibility = View.GONE
    }
}
