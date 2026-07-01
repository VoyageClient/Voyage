/*
 * Copyright 2021-2024 SchildiChat and New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.content.res.Resources
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.features.home.room.detail.timeline.item.MessageInformationData
import im.vector.app.features.home.room.detail.timeline.style.TimelineMessageLayout
import im.vector.app.features.home.room.detail.timeline.view.ScMessageBubbleWrapView

interface BubbleDependentView<H : VectorEpoxyHolder> {

    fun getScBubbleMargin(resources: Resources): Int =
            resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.dual_bubble_one_side_without_avatar_margin)

    fun getViewStubMinimumWidth(holder: H): Int = 0

    fun allowFooterOverlay(holder: H, bubbleWrapView: ScMessageBubbleWrapView): Boolean = false

    // The view an overlaid footer should hug the right edge of (the media thumbnail), or null to use the
    // full content width. A media reply's container is as wide as its (often wider) reply header, so
    // without this the timestamp lands in the empty gap to the right of a slim image.
    fun footerOverlayAnchorView(holder: H): android.view.View? = null

    // Whether to show the footer aligned below the viewStub - requires enough width!
    fun allowFooterBelow(holder: H): Boolean = true
    fun needsFooterReservation(): Boolean = false
    fun reserveFooterSpace(holder: H, width: Int, height: Int) {}
    fun getInformationData(): MessageInformationData? = null

    fun applyScBubbleStyle(messageLayout: TimelineMessageLayout.ScBubble, holder: H) {}
}
