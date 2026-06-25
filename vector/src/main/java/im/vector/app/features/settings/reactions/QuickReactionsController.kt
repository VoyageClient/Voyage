/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.reactions

import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.EmojiCompatFontProvider
import im.vector.app.R
import android.view.Gravity
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.genericButtonItem
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.MatrixUrls.isMxcUrl
import org.matrix.android.sdk.api.session.content.ContentUrlResolver
import javax.inject.Inject

class QuickReactionsController @Inject constructor(
        private val stringProvider: StringProvider,
        private val activeSessionHolder: ActiveSessionHolder,
        val fontProvider: EmojiCompatFontProvider,
) : TypedEpoxyController<List<String>>() {

    interface Listener {
        fun onRemoveReaction(reaction: String)
        fun onAddReaction()
    }

    var listener: Listener? = null

    // Loaded asynchronously by the fragment: emoji glyph -> name, and custom-emote mxc -> disambiguated shortcode.
    var emojiNames: Map<String, String> = emptyMap()
    var emoteShortcodes: Map<String, String> = emptyMap()

    // Current reaction order as shown (the drag helper reorders the models, not our backing list).
    fun currentOrderedReactions(): List<String> =
            adapter.copyOfModels.filterIsInstance<QuickReactionItem_>().map { it.reaction() }

    override fun buildModels(data: List<String>?) {
        val host = this
        val reactions = data.orEmpty()
        val contentUrlResolver = activeSessionHolder.getSafeActiveSession()?.contentUrlResolver()

        reactions.forEach { reaction ->
            quickReactionItem {
                id(reaction)
                reaction(reaction)
                fontProvider(host.fontProvider)
                label(host.labelFor(reaction))
                resolvedUrl(reaction.takeIf { it.isMxcUrl() }?.let { contentUrlResolver?.resolveThumbnail(it, 64, 64, ContentUrlResolver.ThumbnailMethod.SCALE) })
                onRemoveClick { host.listener?.onRemoveReaction(reaction) }
            }
        }

        // "Add reaction" button; the '+' is an icon, not part of the label.
        genericButtonItem {
            id("add_reaction")
            text(host.stringProvider.getString(CommonStrings.quick_reactions_add))
            iconRes(R.drawable.ic_plus)
            gravity(Gravity.START)
            highlight(false)
            buttonClickAction { host.listener?.onAddReaction() }
        }
    }

    private fun labelFor(reaction: String): String = when {
        // Custom emote: its disambiguated shortcode, with leading/trailing colons.
        reaction.isMxcUrl() -> emoteShortcodes[reaction]?.let { ":$it:" }.orEmpty()
        // A single named emoji: its name from the picker data. Text / multi-emoji reactions have no name.
        else -> emojiNames[reaction].orEmpty()
    }
}
