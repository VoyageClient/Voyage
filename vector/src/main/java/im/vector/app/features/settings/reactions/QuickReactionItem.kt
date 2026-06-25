/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.reactions

import android.graphics.Typeface
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.EmojiCompatFontProvider
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.glide.GlideApp

@EpoxyModelClass
abstract class QuickReactionItem : VectorEpoxyModel<QuickReactionItem.Holder>(R.layout.item_quick_reaction) {

    @EpoxyAttribute lateinit var reaction: String
    // A human-readable label: an emoji's name, a custom emote's `:shortcode:`, or empty for text reactions.
    @EpoxyAttribute var label: String = ""
    // Resolved when the reaction is a custom emote (mxc); null for a plain unicode emoji.
    @EpoxyAttribute var resolvedUrl: String? = null
    @EpoxyAttribute lateinit var fontProvider: EmojiCompatFontProvider
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var onRemoveClick: ClickListener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        if (resolvedUrl != null) {
            holder.glyph.isVisible = false
            holder.image.isVisible = true
            GlideApp.with(holder.image).load(resolvedUrl).into(holder.image)
        } else {
            holder.image.isVisible = false
            holder.glyph.isVisible = true
            // The bundled emoji font renders multi-codepoint emojis (ZWJ sequences, skin tones) correctly.
            holder.glyph.typeface = fontProvider.typeface ?: Typeface.DEFAULT
            holder.glyph.text = reaction
        }
        // Always present (even when empty) so its weight keeps the remove button pinned to the right edge.
        holder.label.text = label
        holder.remove.onClick(onRemoveClick)
    }

    override fun unbind(holder: Holder) {
        GlideApp.with(holder.image.context.applicationContext).clear(holder.image)
        super.unbind(holder)
    }

    class Holder : VectorEpoxyHolder() {
        val glyph by bind<TextView>(R.id.quickReactionGlyph)
        val image by bind<ImageView>(R.id.quickReactionImage)
        val label by bind<TextView>(R.id.quickReactionLabel)
        val remove by bind<ImageButton>(R.id.quickReactionRemove)
    }
}
