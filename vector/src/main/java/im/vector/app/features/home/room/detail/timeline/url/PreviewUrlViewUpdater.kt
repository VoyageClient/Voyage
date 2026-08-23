/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.url

import androidx.core.view.isVisible
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.style.TimelineMessageLayout
import im.vector.app.features.media.ImageContentRenderer

/**
 * Subscribes a timeline item's [PreviewUrlView] to the [PreviewUrlRetriever] for the bound event.
 * One instance per Epoxy model: bind from `bind`, release from `unbind`.
 */
class PreviewUrlViewUpdater : PreviewUrlRetriever.PreviewUrlRetrieverListener {
    private var previewUrlView: PreviewUrlView? = null
    private var imageContentRenderer: ImageContentRenderer? = null
    private var retriever: PreviewUrlRetriever? = null
    private var stableId: String? = null

    fun bind(
            view: PreviewUrlView,
            retriever: PreviewUrlRetriever?,
            callback: TimelineEventController.PreviewUrlCallback?,
            imageContentRenderer: ImageContentRenderer?,
            stableId: String,
            messageLayout: TimelineMessageLayout,
    ) {
        previewUrlView = view
        this.imageContentRenderer = imageContentRenderer
        this.retriever = retriever
        this.stableId = stableId
        view.delegate = callback
        view.renderMessageLayout(messageLayout)
        if (retriever == null) {
            view.isVisible = false
        } else {
            retriever.addListener(stableId, this)
        }
    }

    fun unbind() {
        stableId?.let { retriever?.removeListener(it, this) }
        previewUrlView = null
        imageContentRenderer = null
        retriever = null
        stableId = null
    }

    override fun onStateUpdated(state: PreviewUrlUiState) {
        val safeImageContentRenderer = imageContentRenderer
        if (safeImageContentRenderer == null) {
            previewUrlView?.isVisible = false
            return
        }
        previewUrlView?.render(state, safeImageContentRenderer)
    }
}
