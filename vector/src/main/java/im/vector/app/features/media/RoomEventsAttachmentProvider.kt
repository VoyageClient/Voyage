/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import im.vector.app.core.date.VectorDateFormatter
import im.vector.app.core.resources.StringProvider
import im.vector.lib.attachmentviewer.AttachmentInfo
import kotlinx.coroutines.CoroutineScope
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.crypto.attachments.toElementToDecrypt
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.file.FileService
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageGalleryContent
import org.matrix.android.sdk.api.session.room.model.message.MessageImageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageImageInfoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageStickerContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVideoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.session.room.model.message.getFileName
import org.matrix.android.sdk.api.session.room.model.message.getFileUrl
import org.matrix.android.sdk.api.session.room.model.message.getThumbnailUrl
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.util.MimeTypes
import java.io.File

/** The gallery items the viewer pages over; files and audio have nothing to show there. */
fun isGalleryViewerPage(item: MessageWithAttachmentContent): Boolean =
        item is MessageImageInfoContent || item is MessageVideoContent

/** One viewer page: a plain media event, or one image/video item of an MSC4274 gallery. */
data class RoomTimelineAttachment(
        val event: TimelineEvent,
        /** Pre-resolved gallery item content; null for plain events (resolved from the event). */
        val galleryItem: MessageWithAttachmentContent?,
        val galleryIndex: Int?,
) {
    val uid: String get() = galleryPageId(event.eventId, galleryIndex)
}

class RoomEventsAttachmentProvider(
        attachments: List<TimelineEvent>,
        imageContentRenderer: ImageContentRenderer,
        dateFormatter: VectorDateFormatter,
        fileService: FileService,
        coroutineScope: CoroutineScope,
        stringProvider: StringProvider,
        // Media a redaction purged from the server: only a local copy can render it.
        private val preservedFileResolver: (roomId: String, eventId: String) -> File? = { _, _ -> null },
) : BaseAttachmentProvider<RoomTimelineAttachment>(
        attachments = expand(attachments),
        imageContentRenderer = imageContentRenderer,
        fileService = fileService,
        coroutineScope = coroutineScope,
        dateFormatter = dateFormatter,
        stringProvider = stringProvider
) {

    fun indexForEvent(eventId: String): Int {
        for (position in 0 until getItemCount()) {
            val event = getItem(position).event
            // Also by transaction id: a just-sent event can have swapped its local-echo id for the
            // server one between the tap and this lookup.
            if (event.eventId == eventId ||
                    event.root.unsignedData?.transactionId?.takeIf { it.isNotEmpty() } == eventId) {
                return position
            }
        }
        return -1
    }

    private fun contentAt(position: Int): MessageWithAttachmentContent? {
        val item = getItem(position)
        item.galleryItem?.let { return it }
        val clearContent = item.event.root.getClearContent()
        return (clearContent.toModel<MessageContent>() ?: clearContent.toModel<MessageStickerContent>())
                as? MessageWithAttachmentContent
    }

    override fun getAttachmentInfoAt(position: Int): AttachmentInfo {
        val item = getItem(position)
        val it = item.event
        val content = contentAt(position)
        return if (content is MessageImageContent || content is MessageStickerContent) {
            val info = (content as? MessageImageContent)?.info ?: (content as? MessageStickerContent)?.info
            val allowNonMxcUrls = content is MessageImageContent && it.root.sendState.isSending()
            val data = ImageContentRenderer.Data(
                    eventId = it.eventId,
                    stableId = item.uid,
                    filename = content.getFileName(),
                    mimeType = content.mimeType,
                    url = content.getFileUrl(),
                    elementToDecrypt = content.encryptedFileInfo?.toElementToDecrypt(),
                    maxHeight = -1,
                    maxWidth = -1,
                    width = null,
                    height = null,
                    allowNonMxcUrls = allowNonMxcUrls,
                    blurHash = info?.blurHash,
                    preservedFile = it.preservedFile(),
            )
            if (content.mimeType in ANIMATED_IMAGE_MIME_TYPES) {
                AttachmentInfo.AnimatedImage(
                        uid = item.uid,
                        url = content.url ?: "",
                        data = data
                )
            } else {
                AttachmentInfo.Image(
                        uid = item.uid,
                        url = content.url ?: "",
                        data = data
                )
            }
        } else if (content is MessageVideoContent) {
            val thumbnailData = ImageContentRenderer.Data(
                    eventId = it.eventId,
                    stableId = item.uid,
                    filename = content.getFileName(),
                    mimeType = content.mimeType,
                    url = content.videoInfo?.getThumbnailUrl(),
                    elementToDecrypt = content.videoInfo?.thumbnailFile?.toElementToDecrypt(),
                    height = content.videoInfo?.height,
                    maxHeight = -1,
                    width = content.videoInfo?.width,
                    maxWidth = -1,
                    allowNonMxcUrls = it.root.sendState.isSending(),
                    blurHash = content.videoInfo?.blurHash,
                    preservedFile = it.preservedFile(),
            )
            val data = VideoContentRenderer.Data(
                    eventId = it.eventId,
                    filename = content.getFileName(),
                    mimeType = content.mimeType,
                    url = content.getFileUrl(),
                    elementToDecrypt = content.encryptedFileInfo?.toElementToDecrypt(),
                    thumbnailMediaData = thumbnailData,
                    allowNonMxcUrls = it.root.sendState.isSending(),
                    preservedFile = it.preservedFile(),
                    durationMs = content.videoInfo?.duration?.toLong(),
            )
            AttachmentInfo.Video(
                    uid = item.uid,
                    url = content.getFileUrl() ?: "",
                    data = data,
                    thumbnail = AttachmentInfo.Image(
                            uid = item.uid,
                            url = content.videoInfo?.getThumbnailUrl() ?: "",
                            data = thumbnailData
                    )
            )
        } else {
            AttachmentInfo.Image(
                    uid = item.uid,
                    url = "",
                    data = null
            )
        }
    }

    private fun TimelineEvent.preservedFile(): File? = root.roomId?.let { preservedFileResolver(it, eventId) }

    override fun getTimelineEventAtPosition(position: Int): TimelineEvent? {
        return getItem(position).event
    }

    override suspend fun getFileForSharing(position: Int): File? {
        return contentAt(position)
                ?.let { messageContent ->
                    tryOrNull {
                        fileService.downloadFile(
                                fileName = messageContent.getFileName(),
                                mimeType = messageContent.mimeType,
                                url = messageContent.getFileUrl(),
                                elementToDecrypt = messageContent.encryptedFileInfo?.toElementToDecrypt()
                        )
                    }
                }
    }

    companion object {
        // PNG is included because APNGs are frequently labelled image/png; the APNG decoder
        // inspects the stream and falls through to the default PNG path for non-animated files.
        private val ANIMATED_IMAGE_MIME_TYPES = setOf(
                MimeTypes.Gif,
                MimeTypes.Webp,
                MimeTypes.Apng,
                MimeTypes.Png,
                MimeTypes.Jxl,
        )

        private fun expand(events: List<TimelineEvent>): List<RoomTimelineAttachment> {
            return events.flatMap { event ->
                val gallery = event.root.getClearContent().toModel<MessageContent>() as? MessageGalleryContent
                if (gallery != null) {
                    gallery.galleryItems().mapIndexedNotNull { index, item ->
                        RoomTimelineAttachment(event, item, index).takeIf { isGalleryViewerPage(item) }
                    }
                } else {
                    listOf(RoomTimelineAttachment(event, null, null))
                }
            }
        }
    }
}
