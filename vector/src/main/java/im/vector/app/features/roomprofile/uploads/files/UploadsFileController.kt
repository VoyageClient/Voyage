/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.uploads.files

import com.airbnb.epoxy.TypedEpoxyController
import com.airbnb.epoxy.VisibilityState
import im.vector.app.core.date.DateFormatKind
import im.vector.app.core.date.VectorDateFormatter
import im.vector.app.core.epoxy.loadingItem
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.roomprofile.uploads.RoomUploadsViewState
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.room.uploads.UploadEvent
import javax.inject.Inject

class UploadsFileController @Inject constructor(
        private val stringProvider: StringProvider,
        private val dateFormatter: VectorDateFormatter
) : TypedEpoxyController<RoomUploadsViewState>() {

    interface Listener {
        fun loadMore()
        fun onOpenClicked(uploadEvent: UploadEvent)
        fun onDownloadClicked(uploadEvent: UploadEvent)
        fun onShareClicked(uploadEvent: UploadEvent)
    }

    var listener: Listener? = null

    override fun buildModels(data: RoomUploadsViewState?) {
        data ?: return
        val host = this

        buildFileItems(data.fileEvents)

        if (data.hasMore) {
            loadingItem {
                // Keyed on how much has loaded rather than on a counter: a fresh id every rebuild makes
                // Epoxy recreate the view, restarting the spinner's animation, and the list rebuilds often
                // while a sync is running. Loading more still changes the id, so the visibility callback
                // fires again for the next page.
                id("loadMore${data.fileEvents.size}")
                onVisibilityStateChanged { _, _, visibilityState ->
                    if (visibilityState == VisibilityState.VISIBLE) {
                        host.listener?.loadMore()
                    }
                }
            }
        }
    }

    private fun buildFileItems(fileEvents: List<UploadEvent>) {
        val host = this
        fileEvents.forEach { uploadEvent ->
            uploadsFileItem {
                id(uploadEvent.eventId)
                title(uploadEvent.contentWithAttachmentContent.body)
                subtitle(
                        host.stringProvider.getString(
                                CommonStrings.uploads_files_subtitle,
                                uploadEvent.senderInfo.disambiguatedDisplayName,
                                host.dateFormatter.format(uploadEvent.root.originServerTs, DateFormatKind.DEFAULT_DATE_AND_TIME)
                        )
                )
                listener(object : UploadsFileItem.Listener {
                    override fun onItemClicked() {
                        host.listener?.onOpenClicked(uploadEvent)
                    }

                    override fun onDownloadClicked() {
                        host.listener?.onDownloadClicked(uploadEvent)
                    }

                    override fun onShareClicked() {
                        host.listener?.onShareClicked(uploadEvent)
                    }
                })
            }
        }
    }
}
