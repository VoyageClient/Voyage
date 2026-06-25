/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.autocomplete.emoji

import android.widget.ImageView
import android.widget.TextView
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.extensions.setTextOrHide
import im.vector.app.core.glide.GlideApp
import im.vector.app.features.imagepack.ResolvedImage

@EpoxyModelClass
abstract class AutocompleteEmoteItem : VectorEpoxyModel<AutocompleteEmoteItem.Holder>(R.layout.item_autocomplete_emote) {

    @EpoxyAttribute
    lateinit var image: ResolvedImage

    @EpoxyAttribute
    var resolvedUrl: String? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var onClickListener: ClickListener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.name.text = ":${image.shortcode}:"
        // Personal-pack emotes have no pack name; label them like the image-pack settings UI for consistency.
        holder.pack.setTextOrHide(
                if (image.personal) holder.view.context.getString(im.vector.lib.strings.CommonStrings.image_pack_your_personal_pack)
                else image.packDisplayName
        )
        GlideApp.with(holder.image)
                .load(resolvedUrl)
                .into(holder.image)
        holder.view.onClick(onClickListener)
    }

    override fun unbind(holder: Holder) {
        GlideApp.with(holder.image.context.applicationContext).clear(holder.image)
        super.unbind(holder)
    }

    class Holder : VectorEpoxyHolder() {
        val image by bind<ImageView>(R.id.itemAutocompleteEmoteImage)
        val name by bind<TextView>(R.id.itemAutocompleteEmoteName)
        val pack by bind<TextView>(R.id.itemAutocompleteEmotePack)
    }
}
