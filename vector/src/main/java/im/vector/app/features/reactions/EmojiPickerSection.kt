/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions

/**
 * A section in the reaction picker grid: either an emoji category or a custom-emote (MSC2545) pack.
 * Each section has its own tab.
 */
data class EmojiPickerSection(
        val name: String,
        /** Unicode glyph used for the tab (emoji categories). */
        val tabGlyph: String?,
        /** Resolved image url used for the tab (emote packs). */
        val tabImageUrl: String?,
        val items: List<EmojiPickerItem>,
        /** Drawable used for the tab instead of a glyph/image (e.g. the clock for "Frequently used"). */
        @androidx.annotation.DrawableRes val tabIconRes: Int? = null,
)

sealed interface EmojiPickerItem {
    /** A unicode emoji; [glyph] is the rendered character and also the reaction key. */
    data class Unicode(val glyph: String) : EmojiPickerItem

    /**
     * A custom emote; [key] is the `mxc://` reaction key, [shortcode] the `:name:` used when inserting
     * into the composer, [resolvedUrl] the thumbnail to display.
     */
    data class Emote(
            val key: String,
            val shortcode: String,
            val resolvedUrl: String?,
            val contentDescription: String,
    ) : EmojiPickerItem
}
