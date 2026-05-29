/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.attachmentviewer

import android.view.View
import im.vector.lib.attachmentviewer.databinding.ItemAnimatedImageAttachmentBinding

class AnimatedImageViewHolder constructor(itemView: View) :
        BaseViewHolder(itemView) {

    val views = ItemAnimatedImageAttachmentBinding.bind(itemView)

    init {
        // Mirrors ZoomableImageViewHolder so animated images (GIFs, animated WebP) get the
        // same pinch-zoom + pager-intercept behaviour as still images.
        views.imageView.setAllowParentInterceptOnEdge(false)
        val updatePagerIntercept = {
            views.imageView.setAllowParentInterceptOnEdge(views.imageView.scale <= 1.0008f)
        }
        views.imageView.setOnScaleChangeListener { _, _, _ -> updatePagerIntercept() }
        views.imageView.setOnMatrixChangeListener { updatePagerIntercept() }
        views.imageView.setScale(1.0f, true)
        views.imageView.setAllowParentInterceptOnEdge(true)
        views.imageView.setMaximumScale(6f)
    }

    internal val target = DefaultImageLoaderTarget(this, views.imageView)

    override fun bind(attachmentInfo: AttachmentInfo) {
        super.bind(attachmentInfo)
        views.imageView.setScale(1f, false)
    }

    override fun onRecycled() {
        super.onRecycled()
        views.imageView.setImageDrawable(null)
        views.imageView.setScale(1f, false)
    }
}
