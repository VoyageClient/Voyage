/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.autocomplete.emoji

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.widget.TextView
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.reactions.data.EmojiItem

@EpoxyModelClass
abstract class AutocompleteEmojiItem : VectorEpoxyModel<AutocompleteEmojiItem.Holder>(R.layout.item_autocomplete_emoji) {

    @EpoxyAttribute
    lateinit var emojiItem: EmojiItem

    @EpoxyAttribute
    lateinit var shortcode: String

    @EpoxyAttribute
    var emojiTypeFace: Typeface? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var onClickListener: ClickListener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.emojiText.text = emojiItem.emoji.prepareForDisplay()
        holder.emojiText.typeface = emojiTypeFace ?: Typeface.DEFAULT
        // A shortcode, which is an identifier rather than prose.
        @SuppressLint("SetTextI18n")
        holder.shortcodeText.text = ":$shortcode:"
        holder.emojiNameText.text = emojiItem.name
        holder.view.onClick(onClickListener)
    }

    class Holder : VectorEpoxyHolder() {
        val emojiText by bind<TextView>(R.id.itemAutocompleteEmoji)
        val shortcodeText by bind<TextView>(R.id.itemAutocompleteEmojiShortcode)
        val emojiNameText by bind<TextView>(R.id.itemAutocompleteEmojiName)
    }
}
