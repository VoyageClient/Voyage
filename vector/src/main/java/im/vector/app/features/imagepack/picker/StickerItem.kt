/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.picker

import android.widget.ImageView
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.glide.AnimatedContentImageViewTarget
import im.vector.app.core.glide.GlideApp

@EpoxyModelClass
abstract class StickerItem : VectorEpoxyModel<StickerItem.Holder>(R.layout.item_sticker) {

    @EpoxyAttribute var resolvedUrl: String? = null
    @EpoxyAttribute var contentDescription: String? = null
    @EpoxyAttribute var autoplay: Boolean = true
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var onClickListener: ClickListener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.image.contentDescription = contentDescription
        // Downsample to the cell size so animated stickers play without the full-resolution decode cost.
        GlideApp.with(holder.image)
                .load(resolvedUrl)
                .override(128, 128)
                // Glide derives this from the view's scale type only for a bare ImageView target.
                .optionalFitCenter()
                .let { if (autoplay) it else it.dontAnimate() }
                .into(AnimatedContentImageViewTarget(holder.image, autoplay))
        holder.image.onClick(onClickListener)
    }

    override fun unbind(holder: Holder) {
        GlideApp.with(holder.image.context.applicationContext).clear(holder.image)
        super.unbind(holder)
    }

    class Holder : VectorEpoxyHolder() {
        val image by bind<ImageView>(R.id.stickerImage)
    }
}
