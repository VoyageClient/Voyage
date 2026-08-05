/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import com.airbnb.epoxy.TypedEpoxyController
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import javax.inject.Inject

class AttachmentBigPreviewController @Inject constructor() : TypedEpoxyController<AttachmentsPreviewViewState>() {

    var playbackListener: VideoPlaybackListener? = null

    var playbackAllowed: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            // TypedEpoxyController forbids requestModelBuild(); re-submitting the retained data
            // is the supported way to rebuild.
            currentData?.let { setData(it) }
        }

    override fun buildModels(data: AttachmentsPreviewViewState) {
        val host = this
        data.attachments.forEachIndexed { index, contentAttachmentData ->
            val settings = data.compressionSettings[data.stableIdOf(contentAttachmentData)]
            attachmentBigPreviewItem {
                id(data.stableIdOf(contentAttachmentData))
                attachment(contentAttachmentData)
                activePage(data.currentAttachmentIndex == index)
                playbackAllowed(host.playbackAllowed)
                playbackListener(host.playbackListener)
                // So the preview is shaped like what will actually be sent.
                targetSize(settings?.width?.let { width -> settings.height?.let { width to it } })
            }
        }
    }
}

class AttachmentMiniaturePreviewController @Inject constructor() : TypedEpoxyController<AttachmentsPreviewViewState>() {

    interface Callback {
        fun onAttachmentClicked(position: Int, contentAttachmentData: ContentAttachmentData)
    }

    var callback: Callback? = null

    override fun buildModels(data: AttachmentsPreviewViewState) {
        val host = this
        data.attachments.forEachIndexed { index, contentAttachmentData ->
            attachmentMiniaturePreviewItem {
                id(data.stableIdOf(contentAttachmentData))
                attachment(contentAttachmentData)
                checked(data.currentAttachmentIndex == index)
                clickListener { _ ->
                    host.callback?.onAttachmentClicked(index, contentAttachmentData)
                }
            }
        }
    }
}
