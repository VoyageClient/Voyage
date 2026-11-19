/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.uploads.media

import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick
import im.vector.app.features.media.ImageContentRenderer

@EpoxyModelClass
abstract class UploadsImageItem : VectorEpoxyModel<UploadsImageItem.Holder>(R.layout.item_uploads_image) {

    @EpoxyAttribute lateinit var imageContentRenderer: ImageContentRenderer
    @EpoxyAttribute lateinit var data: ImageContentRenderer.Data
    @EpoxyAttribute var animated: Boolean = false
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var listener: ClickListener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.view.onClick(listener)
        // Stills with a badge: a screen of tiles all playing is more than a scroll can keep up with.
        imageContentRenderer.render(data, holder.imageView, IMAGE_SIZE_DP, animate = false)
        holder.animatedBadge.isVisible = animated
        ViewCompat.setTransitionName(holder.imageView, "imagePreview_${id()}")
    }

    class Holder : VectorEpoxyHolder() {
        val imageView by bind<ImageView>(R.id.uploadsImagePreview)
        val animatedBadge by bind<ImageView>(R.id.uploadsImageAnimatedBadge)
    }
}
