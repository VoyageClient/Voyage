/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import com.airbnb.epoxy.TypedEpoxyController
import im.vector.lib.mediatranscode.VideoEditExporter
import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import javax.inject.Inject

class AttachmentBigPreviewController @Inject constructor() : TypedEpoxyController<AttachmentsPreviewViewState>() {

    var playbackListener: VideoPlaybackListener? = null
    var previewReadyListener: ((String) -> Unit)? = null
    var loopVideos: Boolean = false

    /** Orientation-dependent sizing; the screen keeps its views across a rotation, so it is pushed. */
    var showInlineArt: Boolean = true
        private set
    var waveformHeightPx: Int = 0
        private set

    fun setOrientationSizing(showInlineArt: Boolean, waveformHeightPx: Int) {
        if (this.showInlineArt == showInlineArt && this.waveformHeightPx == waveformHeightPx) return
        this.showInlineArt = showInlineArt
        this.waveformHeightPx = waveformHeightPx
        // TypedEpoxyController forbids requestModelBuild(); re-submitting the retained data
        // is the supported way to rebuild.
        currentData?.let { setData(it) }
    }

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
                    .takeIf { !data.isSvg(contentAttachmentData) || data.rasterize.contains(data.stableIdOf(contentAttachmentData)) }
                    ?.takeUnless { data.isSentOriginal(contentAttachmentData) }
            val requestedSize = settings?.width?.let { width -> settings.height?.let { width to it } }
            val targetSize = if (contentAttachmentData.type == ContentAttachmentData.Type.VIDEO && requestedSize != null) {
                // Older devices upload the original video because their platform cannot transcode it.
                if (VideoEditExporter.isSupported()) {
                    val width = contentAttachmentData.displayWidth?.toInt()?.takeIf { it > 0 } ?: requestedSize.first
                    val height = contentAttachmentData.displayHeight?.toInt()?.takeIf { it > 0 } ?: requestedSize.second
                    VideoEditExporter.outputSize(width, height, requestedSize.first, requestedSize.second)
                } else null
            } else requestedSize
            DebugLog.i {
                "UPLOADPREVIEW model type=${contentAttachmentData.type} source=${contentAttachmentData.displayWidth}x${contentAttachmentData.displayHeight} " +
                        "requested=$requestedSize preview=$targetSize original=${data.isSentOriginal(contentAttachmentData)}"
            }
            attachmentBigPreviewItem {
                id(data.stableIdOf(contentAttachmentData))
                attachment(data.previewOf(contentAttachmentData))
                activePage(data.currentAttachmentIndex == index)
                playbackAllowed(host.playbackAllowed)
                loopVideos(host.loopVideos)
                playbackListener(host.playbackListener)
                previewReadyListener(host.previewReadyListener)
                showInlineArt(host.showInlineArt)
                waveformHeightPx(host.waveformHeightPx)
                targetSize(targetSize)
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
                attachment(data.previewOf(contentAttachmentData))
                checked(data.currentAttachmentIndex == index)
                sentOriginal(data.isSentOriginal(contentAttachmentData))
                clickListener { _ ->
                    host.callback?.onAttachmentClicked(index, contentAttachmentData)
                }
            }
        }
    }
}
