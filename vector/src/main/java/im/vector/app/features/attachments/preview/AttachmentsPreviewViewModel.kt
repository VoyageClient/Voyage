/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import im.vector.app.core.platform.VectorViewModel
import org.matrix.android.sdk.api.util.MimeTypes

class AttachmentsPreviewViewModel(initialState: AttachmentsPreviewViewState) :
        VectorViewModel<AttachmentsPreviewViewState, AttachmentsPreviewAction, AttachmentsPreviewViewEvents>(initialState) {

    override fun handle(action: AttachmentsPreviewAction) {
        when (action) {
            is AttachmentsPreviewAction.SetCurrentAttachment -> handleSetCurrentAttachment(action)
            is AttachmentsPreviewAction.UpdateCurrentAttachment -> handleUpdateCurrentAttachment(action)
            AttachmentsPreviewAction.RemoveCurrentAttachment -> handleRemoveCurrentAttachment()
            AttachmentsPreviewAction.RestoreOriginalAttachment -> handleRestoreOriginalAttachment()
            is AttachmentsPreviewAction.SetCompression -> handleSetCompression(action)
            is AttachmentsPreviewAction.SetKeepOriginalSize -> handleSetKeepOriginalSize(action)
            is AttachmentsPreviewAction.SetCaption -> handleSetCaption(action)
            is AttachmentsPreviewAction.SetSharesOneCaption -> setState { copy(sharesOneCaption = action.shared) }
        }
    }

    private fun handleSetCaption(action: AttachmentsPreviewAction.SetCaption) = withState {
        val current = it.attachments.getOrNull(it.currentAttachmentIndex) ?: return@withState
        setState {
            copy(
                    captions = if (sharesOneCaption) {
                        attachments.associate { attachment -> stableIdOf(attachment) to action.caption }
                    } else {
                        captions + (stableIdOf(current) to action.caption)
                    }
            )
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

    private fun handleSetKeepOriginalSize(action: AttachmentsPreviewAction.SetKeepOriginalSize) = withState {
        val current = it.attachments.getOrNull(it.currentAttachmentIndex) ?: return@withState
        val key = it.stableIdOf(current)
        setState { copy(keepOriginalSize = if (action.keep) keepOriginalSize + key else keepOriginalSize - key) }
    }

    private fun handleSetCompression(action: AttachmentsPreviewAction.SetCompression) = withState {
        val current = it.attachments.getOrNull(it.currentAttachmentIndex) ?: return@withState
        val key = it.stableIdOf(current)
        setState {
            copy(
                    compressionSettings = if (action.settings.isDefault) {
                        compressionSettings - key
                    } else {
                        compressionSettings + (key to action.settings)
                    }
            )
        }
    }

    private fun handleRestoreOriginalAttachment() = withState {
        val current = it.attachments.getOrNull(it.currentAttachmentIndex) ?: return@withState
        val record = it.editRecords[current.queryUri] ?: return@withState
        val attachments = it.attachments.toMutableList().apply { set(it.currentAttachmentIndex, record.original) }
        setState {
            copy(attachments = attachments, editRecords = editRecords - current.queryUri)
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
                        // Editing re-encodes (image -> jpg/png, video -> mp4, video -> animated webp),
                        // and the old extension would otherwise mislabel the uploaded file.
                        name = MimeTypes.renameForMimeType(contentAttachmentData.name, action.mimeType),
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
