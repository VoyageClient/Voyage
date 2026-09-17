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
import com.bumptech.glide.load.engine.DiskCacheStrategy
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.glide.AnimatedContentImageViewTarget
import im.vector.app.core.glide.GlideApp
import im.vector.app.features.imagepack.EmoteFrameCache

@EpoxyModelClass
abstract class StickerItem : VectorEpoxyModel<StickerItem.Holder>(R.layout.item_sticker) {

    @EpoxyAttribute var resolvedUrl: String? = null
    @EpoxyAttribute var mxcUrl: String? = null
    @EpoxyAttribute var contentDescription: String? = null
    @EpoxyAttribute var autoplay: Boolean = true
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var onClickListener: ClickListener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.image.contentDescription = contentDescription
        // A still frame while the animation decodes: an animated sticker scrolled back to otherwise
        // shows an empty cell until its decode lands.
        EmoteFrameCache.get(mxcUrl)?.let { holder.image.setImageBitmap(it) }
        // Downsample to the cell size so animated stickers play without the full-resolution decode cost.
        GlideApp.with(holder.image.context)
                .load(resolvedUrl)
                .override(CELL_PX, CELL_PX)
                // ALL, not RESOURCE: an animated drawable is not an encodable resource, so RESOURCE
                // kept nothing at all and every rebind went back to the network for the file.
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                // Glide derives this from the view's scale type only for a bare ImageView target.
                .optionalFitCenter()
                .let { if (autoplay) it else it.dontAnimate() }
                .into(AnimatedContentImageViewTarget(holder.image, autoplay))
        holder.image.onClick { view ->
            // What this cell drew stands in for the sticker in the timeline until its own decode lands.
            EmoteFrameCache.captureFrom(holder.image, mxcUrl)
            onClickListener?.invoke(view)
        }
    }

    override fun unbind(holder: Holder) {
        GlideApp.with(holder.image.context.applicationContext).clear(holder.image)
        super.unbind(holder)
    }

    class Holder : VectorEpoxyHolder() {
        val image by bind<ImageView>(R.id.stickerImage)
    }

    companion object {
        /** Cell decode size, shared with whatever warms the grid ahead of a scroll. */
        const val CELL_PX = 128
    }
}
