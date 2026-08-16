/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.image

import im.vector.app.features.home.room.detail.timeline.helper.timelineStableId
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.media.galleryPageId
import org.matrix.android.sdk.api.session.crypto.attachments.toElementToDecrypt
import org.matrix.android.sdk.api.session.events.model.isGalleryMessage
import org.matrix.android.sdk.api.session.events.model.isImageMessage
import org.matrix.android.sdk.api.session.events.model.isSticker
import org.matrix.android.sdk.api.session.events.model.isVideoMessage
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageGalleryContent
import org.matrix.android.sdk.api.session.room.model.message.MessageImageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageImageInfoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageStickerContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVideoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.session.room.model.message.getFileUrl
import org.matrix.android.sdk.api.session.room.model.message.getThumbnailUrl
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent

/**
 * The thumbnail render data for a media event's preview (reply composer, long-press sheet, …).
 * For an MSC4274 gallery, [galleryItemIndex] targets one item; when null the first visual
 * item stands in for the whole gallery.
 */
fun TimelineEvent.buildImageContentRendererData(maxHeight: Int, galleryItemIndex: Int? = null): ImageContentRenderer.Data? {
    if (root.isGalleryMessage()) {
        val items = (root.getClearContent().toModel<MessageContent>() as? MessageGalleryContent)?.galleryItems().orEmpty()
        val index = galleryItemIndex
                ?: items.indexOfFirst { it is MessageImageInfoContent || it is MessageVideoContent }.takeIf { it >= 0 }
                ?: return null
        val stableId = galleryPageId(timelineStableId(), index)
        return when (val item = items.getOrNull(index)) {
            is MessageVideoContent -> videoThumbnailRendererData(item, stableId, maxHeight)
            is MessageImageInfoContent -> imageRendererData(item, stableId, maxHeight)
            else -> null
        }
    }
    return when {
        root.isImageMessage() -> root.getClearContent().toModel<MessageImageContent>()
                ?.let { imageRendererData(it, timelineStableId(), maxHeight) }
        root.isVideoMessage() -> root.getClearContent().toModel<MessageVideoContent>()
                ?.let { videoThumbnailRendererData(it, timelineStableId(), maxHeight) }
        root.isSticker() -> root.getClearContent().toModel<MessageStickerContent>()
                ?.let { imageRendererData(it, timelineStableId(), maxHeight) }
        else -> null
    }
}

private fun <T> TimelineEvent.imageRendererData(
        content: T,
        stableId: String,
        maxHeight: Int,
): ImageContentRenderer.Data where T : MessageImageInfoContent, T : MessageWithAttachmentContent {
    return ImageContentRenderer.Data(
            eventId = eventId,
            stableId = stableId,
            filename = content.body,
            mimeType = content.mimeType,
            url = content.getFileUrl(),
            elementToDecrypt = content.encryptedFileInfo?.toElementToDecrypt(),
            height = content.info?.height,
            maxHeight = maxHeight,
            width = content.info?.width,
            maxWidth = maxHeight * 2,
            allowNonMxcUrls = false,
            blurHash = content.info?.blurHash,
    )
}

private fun TimelineEvent.videoThumbnailRendererData(
        content: MessageVideoContent,
        stableId: String,
        maxHeight: Int,
): ImageContentRenderer.Data {
    val videoInfo = content.videoInfo
    return ImageContentRenderer.Data(
            eventId = eventId,
            stableId = stableId,
            filename = content.body,
            mimeType = videoInfo?.thumbnailInfo?.mimeType,
            url = videoInfo?.getThumbnailUrl(),
            elementToDecrypt = videoInfo?.thumbnailFile?.toElementToDecrypt(),
            // ThumbnailInfo's w/h default to 0 when absent, so they need the guard before
            // they can stand in for the video's own (unrotated) dimensions.
            height = videoInfo?.thumbnailInfo?.height?.takeIf { it > 0 } ?: videoInfo?.height,
            maxHeight = maxHeight,
            width = videoInfo?.thumbnailInfo?.width?.takeIf { it > 0 } ?: videoInfo?.width,
            maxWidth = maxHeight * 2,
            allowNonMxcUrls = false,
            blurHash = videoInfo?.blurHash,
    )
}
