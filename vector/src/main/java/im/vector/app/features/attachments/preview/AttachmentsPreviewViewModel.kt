/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import im.vector.app.core.platform.VectorViewModel
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.util.MimeTypes

class AttachmentsPreviewViewModel(initialState: AttachmentsPreviewViewState) :
        VectorViewModel<AttachmentsPreviewViewState, AttachmentsPreviewAction, AttachmentsPreviewViewEvents>(initialState) {

    override fun handle(action: AttachmentsPreviewAction) {
        when (action) {
            is AttachmentsPreviewAction.MoveAttachment -> handleMoveAttachment(action)
            is AttachmentsPreviewAction.SetCurrentAttachment -> handleSetCurrentAttachment(action)
            is AttachmentsPreviewAction.UpdateCurrentAttachment -> handleUpdateCurrentAttachment(action)
            AttachmentsPreviewAction.RemoveCurrentAttachment -> handleRemoveCurrentAttachment()
            AttachmentsPreviewAction.RestoreOriginalAttachment -> handleRestoreOriginalAttachment()
            is AttachmentsPreviewAction.SetRasterize -> handleSetRasterize(action)
            is AttachmentsPreviewAction.SetCompression -> handleSetCompression(action)
            is AttachmentsPreviewAction.SetKeepOriginalSize -> handleSetKeepOriginalSize(action)
            is AttachmentsPreviewAction.SetSendAsSpoiler -> handleSetSendAsSpoiler(action)
            is AttachmentsPreviewAction.SetCaption -> handleSetCaption(action)
            is AttachmentsPreviewAction.SetSharesOneCaption -> handleSetSharesOneCaption(action)
        }
    }

    private fun handleMoveAttachment(action: AttachmentsPreviewAction.MoveAttachment) = setState {
        if (action.fromIndex !in attachments.indices || action.toIndex !in attachments.indices || action.fromIndex == action.toIndex) {
            return@setState this
        }
        val current = attachments.getOrNull(currentAttachmentIndex)
        val reordered = attachments.toMutableList().apply { add(action.toIndex, removeAt(action.fromIndex)) }
        copy(attachments = reordered, currentAttachmentIndex = reordered.indexOf(current).coerceAtLeast(0))
    }

    private fun handleSetSharesOneCaption(action: AttachmentsPreviewAction.SetSharesOneCaption) = setState {
        if (!action.shared) return@setState copy(sharesOneCaption = false)
        val shared = attachments.firstNotNullOfOrNull { captions[stableIdOf(it)]?.takeIf { caption -> caption.isNotBlank() } }
        copy(
                sharesOneCaption = true,
                captions = if (shared == null) captions else attachments.associate { stableIdOf(it) to shared },
        )
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

    private fun handleSetRasterize(action: AttachmentsPreviewAction.SetRasterize) = withState {
        val current = it.attachments.getOrNull(it.currentAttachmentIndex) ?: return@withState
        val key = it.stableIdOf(current)
        val record = it.editRecords[current.queryUri]?.takeUnless { action.rasterize }
        setState {
            val settings = when {
                record?.sourceRasterSize != null -> {
                    val (width, height) = record.sourceRasterSize
                    compressionSettings + (key to (compressionSettings[key] ?: CompressionSettings()).copy(width = width, height = height))
                }
                action.rasterize && !compressionSettings.containsKey(key) -> compressionSettings + (key to CompressionSettings.recommendedRasterization(
                        current.displayWidth?.toInt(), current.displayHeight?.toInt()
                ))
                else -> compressionSettings
            }
            copy(
                    attachments = if (record == null) attachments else attachments.map { attachment ->
                        if (attachment.queryUri == current.queryUri) record.original else attachment
                    },
                    editRecords = if (record == null) editRecords else editRecords - current.queryUri,
                    rasterize = if (action.rasterize) rasterize + key else rasterize - key,
                    compressionSettings = settings
            )
        }
    }

    private fun handleSetKeepOriginalSize(action: AttachmentsPreviewAction.SetKeepOriginalSize) = withState {
        val current = it.attachments.getOrNull(it.currentAttachmentIndex) ?: return@withState
        val key = it.stableIdOf(current)
        setState { copy(keepOriginalSize = if (action.keep) keepOriginalSize + key else keepOriginalSize - key) }
    }

    private fun handleSetSendAsSpoiler(action: AttachmentsPreviewAction.SetSendAsSpoiler) = withState {
        val current = it.attachments.getOrNull(it.currentAttachmentIndex) ?: return@withState
        val key = it.stableIdOf(current)
        setState { copy(sendAsSpoiler = if (action.sendAsSpoiler) sendAsSpoiler + key else sendAsSpoiler - key) }
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
        val key = it.stableIdOf(current)
        setState {
            val settings = record.sourceRasterSize?.let { (width, height) ->
                (compressionSettings[key] ?: CompressionSettings()).copy(width = width, height = height)
            }
            copy(attachments = attachments, editRecords = editRecords - current.queryUri,
                    compressionSettings = if (settings == null) compressionSettings else compressionSettings + (key to settings))
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
                        // The export has the source rotation baked into its pixels and carries no EXIF.
                        exifOrientation = 0,
                        mimeType = action.mimeType ?: contentAttachmentData.mimeType,
                        // Editing re-encodes (image -> jpg/png, video -> mp4, video -> animated webp),
                        // and the old extension would otherwise mislabel the uploaded file.
                        name = MimeTypes.renameForMimeType(contentAttachmentData.name, action.mimeType),
                        duration = if (action.replacementType == ContentAttachmentData.Type.IMAGE) {
                            null
                        } else {
                            action.duration ?: contentAttachmentData.duration
                        },
                        type = action.replacementType ?: contentAttachmentData.type,
                )
            } else {
                contentAttachmentData
            }
        }
        val editRecords = it.editRecords.toMutableMap().apply {
            previousUri?.let { uri -> remove(uri) }
            action.editRecord?.let { record -> put(action.newUri.toString(), record) }
        }
        val replacedId = it.attachments.getOrNull(it.currentAttachmentIndex)
                ?.takeIf { action.replacementType != null }
                ?.let { current -> it.stableIdOf(current) }
        val currentId = it.attachments.getOrNull(it.currentAttachmentIndex)?.let { current -> it.stableIdOf(current) }
        val rasterSize = if (action.editRecord?.original?.getSafeMimeType() == MimeTypes.Svg && action.width != null && action.height != null) {
            currentId?.let { key -> key to (it.compressionSettings[key] ?: CompressionSettings()).copy(
                    width = action.width.toInt(), height = action.height.toInt()
            ) }
        } else null
        setState {
            if (replacedId == null) {
                copy(attachments = attachments, editRecords = editRecords,
                        compressionSettings = if (rasterSize == null) compressionSettings else compressionSettings + rasterSize)
            } else {
                copy(
                        attachments = attachments,
                        editRecords = editRecords,
                        stableIdAliases = stableIdAliases + (action.newUri.toString() to replacedId),
                        // Chosen for the old media; a video's bitrate and size mean nothing to a still.
                        compressionSettings = compressionSettings - replacedId,
                )
            }
        }
    }

    private fun handleSetCurrentAttachment(action: AttachmentsPreviewAction.SetCurrentAttachment) = setState {
        copy(currentAttachmentIndex = action.index)
    }
}
