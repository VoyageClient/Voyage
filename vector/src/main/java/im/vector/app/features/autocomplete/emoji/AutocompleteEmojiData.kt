/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.autocomplete.emoji

import im.vector.app.features.imagepack.ResolvedImage
import im.vector.app.features.reactions.data.EmojiItem

/**
 * An entry in the `:` autocomplete popup, which mixes unicode emojis with MSC2545 custom emotes.
 */
sealed interface AutocompleteEmojiData {
    data class Emoji(val emojiItem: EmojiItem) : AutocompleteEmojiData
    data class Emote(val image: ResolvedImage) : AutocompleteEmojiData
}
