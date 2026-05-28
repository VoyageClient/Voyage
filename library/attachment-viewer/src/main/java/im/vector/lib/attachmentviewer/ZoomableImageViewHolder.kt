/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.attachmentviewer

import android.view.View
import im.vector.lib.attachmentviewer.databinding.ItemImageAttachmentBinding

class ZoomableImageViewHolder constructor(itemView: View) :
        BaseViewHolder(itemView) {

    val views = ItemImageAttachmentBinding.bind(itemView)

    init {
        views.touchImageView.setAllowParentInterceptOnEdge(false)
        // The OnScaleChangeListener's `scaleFactor` parameter is the per-event delta, not the
        // absolute current scale — checking it leaves the pager-intercept flag stuck off if
        // the user happens to end a pinch on a small zoom-in delta (e.g. fingers wobbling
        // back toward each other on release). Use `scale` (the absolute value) instead. We
        // also watch matrix changes since a pan can finish without firing the scale listener.
        val updatePagerIntercept = {
            // The pitch comparison is fuzzy because PhotoView doesn't clamp to exactly 1.0
            views.touchImageView.setAllowParentInterceptOnEdge(views.touchImageView.scale <= 1.0008f)
        }
        views.touchImageView.setOnScaleChangeListener { _, _, _ -> updatePagerIntercept() }
        views.touchImageView.setOnMatrixChangeListener { updatePagerIntercept() }
        views.touchImageView.setScale(1.0f, true)
        views.touchImageView.setAllowParentInterceptOnEdge(true)
        // PhotoView's defaults are min=1, medium=1.75, max=3. Bump just the cap so users can
        // pinch further into the source image while leaving the double-tap step alone.
        views.touchImageView.setMaximumScale(6f)
    }

    internal val target = DefaultImageLoaderTarget.ZoomableImageTarget(this, views.touchImageView)

    override fun bind(attachmentInfo: AttachmentInfo) {
        super.bind(attachmentInfo)
        // Drop any prior pinch zoom so re-entering this page lands at 1x like the user expects.
        views.touchImageView.setScale(1f, false)
    }

    override fun onRecycled() {
        super.onRecycled()
        views.touchImageView.setImageDrawable(null)
        views.touchImageView.setScale(1f, false)
    }
}
