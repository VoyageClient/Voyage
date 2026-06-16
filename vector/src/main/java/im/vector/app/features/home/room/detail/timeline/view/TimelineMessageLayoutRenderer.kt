/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.view

import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.ui.views.BubbleDependentView
import im.vector.app.features.home.room.detail.timeline.item.BaseEventItem
import im.vector.app.features.home.room.detail.timeline.style.TimelineMessageLayout

interface TimelineMessageLayoutRenderer {
    // Element's native renderer entry point (Default / Bubble layouts)
    fun renderMessageLayout(messageLayout: TimelineMessageLayout)

    // SchildiChat bubble renderer entry points; default no-op for Element renderers
    fun <H : BaseEventItem.BaseHolder> renderMessageLayout(
            messageLayout: TimelineMessageLayout,
            bubbleDependentView: BubbleDependentView<H>,
            holder: H,
    ) {}

    fun <H : VectorEpoxyHolder> renderBaseMessageLayout(
            messageLayout: TimelineMessageLayout,
            bubbleDependentView: BubbleDependentView<H>,
            holder: H,
    ) {}
}

// Render the SC message layout - falls back to stub-only handling when the parent view is no SC renderer
fun <H : BaseEventItem.BaseHolder> TimelineMessageLayoutRenderer?.scRenderMessageLayout(
        messageLayout: TimelineMessageLayout,
        bubbleDependentView: BubbleDependentView<H>,
        holder: H,
) {
    if (this == null) {
        renderStubMessageLayout(messageLayout, holder.contentContainer)
    } else {
        renderMessageLayout(messageLayout, bubbleDependentView, holder)
    }
}
