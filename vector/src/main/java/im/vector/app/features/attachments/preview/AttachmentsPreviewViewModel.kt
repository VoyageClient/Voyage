/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import im.vector.app.core.platform.VectorViewModel

class AttachmentsPreviewViewModel(initialState: AttachmentsPreviewViewState) :
        VectorViewModel<AttachmentsPreviewViewState, AttachmentsPreviewAction, AttachmentsPreviewViewEvents>(initialState) {

    override fun handle(action: AttachmentsPreviewAction) {
        when (action) {
            is AttachmentsPreviewAction.SetCurrentAttachment -> handleSetCurrentAttachment(action)
            is AttachmentsPreviewAction.UpdateCurrentAttachment -> handleUpdateCurrentAttachment(action)
            AttachmentsPreviewAction.RemoveCurrentAttachment -> handleRemoveCurrentAttachment()
        }
    }

    private fun handleRemoveCurrentAttachment() = withState {
        val currentAttachment = it.attachments.getOrNull(it.currentAttachmentIndex) ?: return@withState
        val attachments = it.attachments.minusElement(currentAttachment)
        val newAttachmentIndex = it.currentAttachmentIndex.coerceAtMost(attachments.size - 1)
        setState {
            copy(
                    attachments = attachments,
                    currentAttachmentIndex = newAttachmentIndex,
                    editRecords = editRecords - currentAttachment.queryUri
            )
        }
    }

    private fun handleUpdateCurrentAttachment(action: AttachmentsPreviewAction.UpdateCurrentAttachment) = withState {
        val previousUri = it.attachments.getOrNull(it.currentAttachmentIndex)?.queryUri
        val attachments = it.attachments.mapIndexed { index, contentAttachmentData ->
            if (index == it.currentAttachmentIndex) {
                // Editing changes the dimensions and size too; leaving the originals in place
                // would upload the file described by the wrong metadata.
                contentAttachmentData.copy(
                        queryUri = action.newUri.toString(),
                        width = action.width ?: contentAttachmentData.width,
                        height = action.height ?: contentAttachmentData.height,
                        size = action.size ?: contentAttachmentData.size,
                        mimeType = action.mimeType ?: contentAttachmentData.mimeType,
                        duration = action.duration ?: contentAttachmentData.duration
                )
            } else {
                contentAttachmentData
            }
        }
        val editRecords = it.editRecords.toMutableMap().apply {
            previousUri?.let { uri -> remove(uri) }
            action.editRecord?.let { record -> put(action.newUri.toString(), record) }
        }
        setState {
            copy(attachments = attachments, editRecords = editRecords)
        }
    }

    private fun handleSetCurrentAttachment(action: AttachmentsPreviewAction.SetCurrentAttachment) = setState {
        copy(currentAttachmentIndex = action.index)
    }
}
