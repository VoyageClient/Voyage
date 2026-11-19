/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.uploads.media

import android.view.View
import android.widget.ImageView
import com.airbnb.epoxy.EpoxyAsyncUtil
import com.airbnb.epoxy.TypedEpoxyController
import com.airbnb.epoxy.VisibilityState
import im.vector.app.core.epoxy.squareLoadingItem
import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.media.VideoContentRenderer
import im.vector.app.features.media.galleryPageId
import im.vector.app.features.roomprofile.uploads.RoomUploadsViewState
import org.matrix.android.sdk.api.session.crypto.attachments.toElementToDecrypt
import org.matrix.android.sdk.api.session.room.model.message.MessageImageInfoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.model.message.MessageVideoContent
import org.matrix.android.sdk.api.session.room.model.message.getFileUrl
import org.matrix.android.sdk.api.session.room.model.message.getThumbnailUrl
import org.matrix.android.sdk.api.session.room.uploads.UploadEvent
import org.matrix.android.sdk.api.util.MimeTypes
import java.io.File
import javax.inject.Inject

class UploadsMediaController @Inject constructor(
        private val errorFormatter: ErrorFormatter,
        private val imageContentRenderer: ImageContentRenderer,
        private val stringProvider: StringProvider,
        dimensionConverter: DimensionConverter
) : TypedEpoxyController<RoomUploadsViewState>(
        // Every page rebuilds and diffs the whole grid, which grows past a frame's budget on the main thread.
        EpoxyAsyncUtil.getAsyncBackgroundHandler(),
        EpoxyAsyncUtil.getAsyncBackgroundHandler(),
) {

    interface Listener {
        fun onOpenImageClicked(view: View, mediaData: ImageContentRenderer.Data)
        fun onOpenVideoClicked(view: View, mediaData: VideoContentRenderer.Data)
        fun loadMore()
    }

    var listener: Listener? = null

    var isLoadMoreVisible = false
        private set

    private val itemSize = dimensionConverter.dpToPx(IMAGE_SIZE_DP)

    override fun buildModels(data: RoomUploadsViewState?) {
        data ?: return
        val host = this

        buildMediaItems(data.visibleMediaEvents, data.preservedMediaFiles)

        if (data.hasMore) {
            squareLoadingItem {
                // A stable id: a new one recreates the view, restarting the spinner. The fragment asks for
                // the next page itself while the row stays visible, since this callback won't fire again.
                id("loadMore")
                onVisibilityStateChanged { _, _, visibilityState ->
                    when (visibilityState) {
                        VisibilityState.VISIBLE -> {
                            host.isLoadMoreVisible = true
                            host.listener?.loadMore()
                        }
                        VisibilityState.INVISIBLE -> host.isLoadMoreVisible = false
                    }
                }
            }
        }
    }

    private fun buildMediaItems(mediaEvents: List<UploadEvent>, preservedFiles: Map<String, File>) {
        val host = this
        mediaEvents.forEach { uploadEvent ->
            when (uploadEvent.contentWithAttachmentContent.msgType) {
                MessageType.MSGTYPE_IMAGE,
                MessageType.MSGTYPE_STICKER_LOCAL -> {
                    val messageContent = uploadEvent.contentWithAttachmentContent as? MessageImageInfoContent ?: return@forEach
                    val data = uploadEvent.toImageContentRendererData(messageContent, preservedFiles[uploadEvent.eventId])
                    val tileData = messageContent.encryptedThumbnailOf(data)
                    val isAnimated = messageContent.isAnimated()
                    uploadsImageItem {
                        id(with(host) { uploadEvent.uid() })
                        imageContentRenderer(host.imageContentRenderer)
                        data(tileData)
                        animated(isAnimated)
                        listener { clicked: View ->
                            // Nothing for the viewer to show, so spend the tap on another attempt.
                            val imageView = clicked as? ImageView
                            when {
                                imageView != null && host.imageContentRenderer.isRetrying(imageView) -> Unit
                                host.imageContentRenderer.isFailed(tileData) -> imageView?.let { host.imageContentRenderer.retry(it) }
                                else -> host.listener?.onOpenImageClicked(clicked, data)
                            }
                        }
                    }
                }
                MessageType.MSGTYPE_VIDEO -> {
                    val data = uploadEvent.toVideoContentRendererData() ?: return@forEach
                    uploadsVideoItem {
                        id(with(host) { uploadEvent.uid() })
                        imageContentRenderer(host.imageContentRenderer)
                        data(data)
                        listener {
                            host.listener?.onOpenVideoClicked(it, data)
                        }
                    }
                }
            }
        }
    }

    private fun UploadEvent.uid(): String = galleryPageId(eventId, galleryItemIndex)

    /** A redaction the app preserved locally still has its bytes, even once the server copy is gone. */
    private fun UploadEvent.toImageContentRendererData(messageContent: MessageImageInfoContent, preservedFile: File?): ImageContentRenderer.Data {
        return ImageContentRenderer.Data(
                eventId = eventId,
                stableId = uid(),
                galleryIndex = galleryItemIndex,
                filename = messageContent.body,
                url = messageContent.getFileUrl(),
                mimeType = messageContent.mimeType,
                elementToDecrypt = messageContent.encryptedFileInfo?.toElementToDecrypt(),
                height = messageContent.info?.height,
                maxHeight = itemSize,
                width = messageContent.info?.width,
                maxWidth = itemSize,
                blurHash = messageContent.info?.blurHash,
                preservedFile = preservedFile,
        )
    }

    // The server can't thumbnail an encrypted file, so without the sender's own thumbnail a tile
    // downloads, decrypts and decodes the whole original.
    private fun MessageImageInfoContent.encryptedThumbnailOf(data: ImageContentRenderer.Data): ImageContentRenderer.Data {
        if (encryptedFileInfo == null) return data
        val thumbnailFile = info?.thumbnailFile ?: return data
        val elementToDecrypt = thumbnailFile.toElementToDecrypt() ?: return data
        val thumbnailInfo = info?.thumbnailInfo?.takeIf { it.width > 0 && it.height > 0 }
        return data.copy(
                url = thumbnailFile.url,
                elementToDecrypt = elementToDecrypt,
                mimeType = info?.thumbnailInfo?.mimeType ?: data.mimeType,
                width = thumbnailInfo?.width ?: data.width,
                height = thumbnailInfo?.height ?: data.height,
        )
    }

    private fun MessageImageInfoContent.isAnimated(): Boolean =
            info?.isAnimated ?: (mimeType == MimeTypes.Gif || mimeType == MimeTypes.Apng)

    private fun UploadEvent.toVideoContentRendererData(): VideoContentRenderer.Data? {
        val messageContent = (contentWithAttachmentContent as? MessageVideoContent) ?: return null

        val thumbnailData = ImageContentRenderer.Data(
                eventId = eventId,
                stableId = uid(),
                galleryIndex = galleryItemIndex,
                filename = messageContent.body,
                mimeType = messageContent.mimeType,
                url = messageContent.videoInfo?.getThumbnailUrl(),
                elementToDecrypt = messageContent.videoInfo?.thumbnailFile?.toElementToDecrypt(),
                height = messageContent.videoInfo?.height,
                maxHeight = itemSize,
                width = messageContent.videoInfo?.width,
                maxWidth = itemSize,
                blurHash = messageContent.videoInfo?.blurHash,
        )

        return VideoContentRenderer.Data(
                eventId = eventId,
                galleryIndex = galleryItemIndex,
                filename = messageContent.body,
                mimeType = messageContent.mimeType,
                url = messageContent.getFileUrl(),
                elementToDecrypt = messageContent.encryptedFileInfo?.toElementToDecrypt(),
                thumbnailMediaData = thumbnailData,
                durationMs = messageContent.videoInfo?.duration?.toLong()
        )
    }
}
