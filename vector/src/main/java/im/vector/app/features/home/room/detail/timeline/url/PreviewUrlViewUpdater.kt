/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.url

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
        view.boundStableId = stableId
        view.renderMessageLayout(messageLayout)
        if (retriever == null) {
            view.hide()
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
        val view = previewUrlView ?: return
        // A rebind doesn't unbind the previous model, so its updater stays registered with a view that
        // RecyclerView may later reuse for another message. Ignore the update unless the view still hosts
        // this event, else a late preview hides (or overwrites) an unrelated message's card. Deregister
        // too: a rebind re-registers, so an orphan would only pile up on a message we keep rebuilding.
        if (view.boundStableId != stableId) {
            unbind()
            return
        }
        val safeImageContentRenderer = imageContentRenderer
        if (safeImageContentRenderer == null) {
            view.hide()
            return
        }
        view.render(state, safeImageContentRenderer)
    }
}
