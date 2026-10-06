/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.uploads.files

import android.view.View
import com.airbnb.epoxy.TypedEpoxyController
import com.airbnb.epoxy.VisibilityState
import im.vector.app.core.date.DateFormatKind
import im.vector.app.core.date.VectorDateFormatter
import im.vector.app.core.epoxy.loadingItem
import im.vector.app.features.media.galleryPageId
import im.vector.app.features.roomprofile.uploads.RoomUploadsViewState
import org.matrix.android.sdk.api.session.room.model.message.getFileName
import org.matrix.android.sdk.api.session.room.uploads.UploadEvent
import javax.inject.Inject

class UploadsFileController @Inject constructor(
        private val dateFormatter: VectorDateFormatter
) : TypedEpoxyController<RoomUploadsViewState>() {

    interface Listener {
        fun loadMore()
        fun onOpenClicked(uploadEvent: UploadEvent)
        fun onDownloadClicked(uploadEvent: UploadEvent)
        fun onMoreClicked(uploadEvent: UploadEvent, anchor: View)
    }

    var listener: Listener? = null

    var isLoadMoreVisible = false
        private set

    override fun buildModels(data: RoomUploadsViewState?) {
        data ?: return
        val host = this

        buildFileItems(data.fileEvents)

        if (data.hasMore) {
            loadingItem {
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

    private fun buildFileItems(fileEvents: List<UploadEvent>) {
        val host = this
        fileEvents.forEach { uploadEvent ->
            uploadsFileItem {
                id(galleryPageId(uploadEvent.eventId, uploadEvent.galleryItemIndex))
                title(uploadEvent.contentWithAttachmentContent.getFileName())
                subtitle(
                        uploadEvent.senderInfo.disambiguatedDisplayName + " • " +
                                host.dateFormatter.format(uploadEvent.root.originServerTs, DateFormatKind.DEFAULT_DATE_AND_TIME)
                )
                listener(object : UploadsFileItem.Listener {
                    override fun onItemClicked() {
                        host.listener?.onOpenClicked(uploadEvent)
                    }

                    override fun onDownloadClicked() {
                        host.listener?.onDownloadClicked(uploadEvent)
                    }

                    override fun onMoreClicked(anchor: View) {
                        host.listener?.onMoreClicked(uploadEvent, anchor)
                    }
                })
            }
        }
    }
}
