/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.colorpicker

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import im.vector.lib.strings.CommonStrings
import kotlin.math.abs
import im.vector.lib.ui.styles.R as StylesR

/** One palette slot. [onLight] and [onDark] are the same resource for theme-independent palettes. */
data class PaletteColor(
        @StringRes val nameRes: Int,
        @ColorRes val onLight: Int,
        @ColorRes val onDark: Int,
) {
    constructor(@StringRes nameRes: Int, @ColorRes color: Int) : this(nameRes, color, color)

    @ColorRes
    fun forTheme(light: Boolean) = if (light) onLight else onDark
}

/**
 * Colors for display names, and for the default avatars of users, rooms and spaces alike. Every
 * entry is offered for both, apart from [NONE], which only makes sense for names.
 *
 * The element-web entries carry the hash that palette shipped alongside: the room trios were
 * indexed by the sum of the id's characters, the name palettes by a rolling hash, until element-web
 * settled on the character sum for both in 2024.
 */
enum class ColorPalette(@StringRes val titleRes: Int, val colors: List<PaletteColor>) {
    NONE(CommonStrings.color_palette_none, emptyList()),
    RIOT_ALPHA_ROOMS(CommonStrings.color_palette_riot_alpha_rooms, ALPHA_ROOM_COLORS),
    LEGACY_ROOMS(CommonStrings.color_palette_legacy_rooms, LEGACY_ROOM_COLORS),
    RIOT_ALPHA(CommonStrings.color_palette_riot_alpha, ALPHA_PEOPLE_COLORS),
    LEGACY(CommonStrings.color_palette_legacy, LEGACY_PEOPLE_COLORS),
    MODERN(CommonStrings.color_palette_modern, MODERN_COLORS),
    TWITTER_EGG_2010(CommonStrings.color_palette_twitter_egg_2010, TWITTER_EGG_2010_COLORS),
    TWITTER_EGG_2014(CommonStrings.color_palette_twitter_egg_2014, TWITTER_EGG_2014_COLORS),
    DISCORD_2015(CommonStrings.color_palette_discord_2015, DISCORD_2015_COLORS),
    DISCORD_2021(CommonStrings.color_palette_discord_2021, DISCORD_2021_COLORS);

    fun indexOf(id: String?): Int = when (this) {
        NONE -> -1
        RIOT_ALPHA_ROOMS, LEGACY_ROOMS, MODERN -> sumIndex(id, colors.size)
        else -> rollingHashIndex(id, colors.size)
    }

    @ColorRes
    fun colorFor(id: String?, light: Boolean) = colors[indexOf(id)].forTheme(light)
}

private fun sumIndex(id: String?, size: Int) = (id?.sumOf { it.code } ?: 0) % size

// Element's oldest name hash, borrowed in 2018 from the phased-rollout bucketing: hash * 31 per char.
private fun rollingHashIndex(id: String?, size: Int): Int {
    var hash = 0
    id?.forEach { hash = (hash shl 5) - hash + it.code }
    return (abs(hash.toLong()) % size).toInt()
}

// element-web 2018-2019: the first colors display names ever got.
private val ALPHA_PEOPLE_COLORS = listOf(
        PaletteColor(CommonStrings.profile_color_alpha_sky, StylesR.color.element_name_alpha_01),
        PaletteColor(CommonStrings.profile_color_alpha_orchid, StylesR.color.element_name_alpha_02),
        PaletteColor(CommonStrings.profile_color_alpha_mint, StylesR.color.element_name_alpha_03),
        PaletteColor(CommonStrings.profile_color_alpha_blush, StylesR.color.element_name_alpha_04),
        PaletteColor(CommonStrings.profile_color_alpha_butter, StylesR.color.element_name_alpha_05),
        PaletteColor(CommonStrings.profile_color_alpha_seafoam, StylesR.color.element_name_alpha_06),
        PaletteColor(CommonStrings.profile_color_alpha_indigo, StylesR.color.element_name_alpha_07),
        PaletteColor(CommonStrings.profile_color_alpha_chartreuse, StylesR.color.element_name_alpha_08),
)

// element-web 2019-2023, with the July 2020 green.
private val LEGACY_PEOPLE_COLORS = listOf(
        PaletteColor(CommonStrings.profile_color_name_azure, StylesR.color.element_name_01),
        PaletteColor(CommonStrings.profile_color_name_grape, StylesR.color.element_name_02),
        PaletteColor(CommonStrings.profile_color_name_element_green, StylesR.color.element_name_03),
        PaletteColor(CommonStrings.profile_color_name_polly, StylesR.color.element_name_04),
        PaletteColor(CommonStrings.profile_color_name_melon, StylesR.color.element_name_05),
        PaletteColor(CommonStrings.profile_color_name_aqua, StylesR.color.element_name_06),
        PaletteColor(CommonStrings.profile_color_name_prune, StylesR.color.element_name_07),
        PaletteColor(CommonStrings.profile_color_name_kiwi, StylesR.color.element_name_08),
)

// Compound's text-decorative-1..6, which element-web has used for names and avatars alike since 2024.
private val MODERN_COLORS = listOf(
        PaletteColor(CommonStrings.profile_color_modern_lime, StylesR.color.element_name_modern_light_01, StylesR.color.element_name_modern_dark_01),
        PaletteColor(CommonStrings.profile_color_modern_cyan, StylesR.color.element_name_modern_light_02, StylesR.color.element_name_modern_dark_02),
        PaletteColor(CommonStrings.profile_color_modern_fuchsia, StylesR.color.element_name_modern_light_03, StylesR.color.element_name_modern_dark_03),
        PaletteColor(CommonStrings.profile_color_modern_purple, StylesR.color.element_name_modern_light_04, StylesR.color.element_name_modern_dark_04),
        PaletteColor(CommonStrings.profile_color_modern_pink, StylesR.color.element_name_modern_light_05, StylesR.color.element_name_modern_dark_05),
        PaletteColor(CommonStrings.profile_color_modern_orange, StylesR.color.element_name_modern_light_06, StylesR.color.element_name_modern_dark_06),
)

// The backgrounds Twitter's shaded default-avatar egg came on, default_profile_0..6.
private val TWITTER_EGG_2010_COLORS = listOf(
        PaletteColor(CommonStrings.profile_color_egg_harbor, StylesR.color.twitter_egg_2010_01),
        PaletteColor(CommonStrings.profile_color_egg_sprout, StylesR.color.twitter_egg_2010_02),
        PaletteColor(CommonStrings.profile_color_egg_pine, StylesR.color.twitter_egg_2010_03),
        PaletteColor(CommonStrings.profile_color_egg_apricot, StylesR.color.twitter_egg_2010_04),
        PaletteColor(CommonStrings.profile_color_egg_powder, StylesR.color.twitter_egg_2010_05),
        PaletteColor(CommonStrings.profile_color_egg_plum, StylesR.color.twitter_egg_2010_06),
        PaletteColor(CommonStrings.profile_color_egg_cherry, StylesR.color.twitter_egg_2010_07),
)

// And of the flat egg that replaced it in 2014, keeping the same numbering.
private val TWITTER_EGG_2014_COLORS = listOf(
        PaletteColor(CommonStrings.profile_color_egg_bluebird, StylesR.color.twitter_egg_2014_01),
        PaletteColor(CommonStrings.profile_color_egg_tangerine, StylesR.color.twitter_egg_2014_02),
        PaletteColor(CommonStrings.profile_color_egg_fern, StylesR.color.twitter_egg_2014_03),
        PaletteColor(CommonStrings.profile_color_egg_amber, StylesR.color.twitter_egg_2014_04),
        PaletteColor(CommonStrings.profile_color_egg_ash, StylesR.color.twitter_egg_2014_05),
        PaletteColor(CommonStrings.profile_color_egg_violet, StylesR.color.twitter_egg_2014_06),
        PaletteColor(CommonStrings.profile_color_egg_crimson, StylesR.color.twitter_egg_2014_07),
)

// The backgrounds of Discord's default avatar before the 2021 rebrand.
private val DISCORD_2015_COLORS = listOf(
        PaletteColor(CommonStrings.profile_color_discord_blurple, StylesR.color.discord_2015_01),
        PaletteColor(CommonStrings.profile_color_discord_gray, StylesR.color.discord_2015_02),
        PaletteColor(CommonStrings.profile_color_discord_green, StylesR.color.discord_2015_03),
        PaletteColor(CommonStrings.profile_color_discord_yellow, StylesR.color.discord_2015_04),
        PaletteColor(CommonStrings.profile_color_discord_red, StylesR.color.discord_2015_05),
)

// And after it, on the new blurple and with a fuchsia added.
private val DISCORD_2021_COLORS = listOf(
        PaletteColor(CommonStrings.profile_color_discord_blurple, StylesR.color.discord_2021_01),
        PaletteColor(CommonStrings.profile_color_discord_gray, StylesR.color.discord_2021_02),
        PaletteColor(CommonStrings.profile_color_discord_green, StylesR.color.discord_2021_03),
        PaletteColor(CommonStrings.profile_color_discord_yellow, StylesR.color.discord_2021_04),
        PaletteColor(CommonStrings.profile_color_discord_red, StylesR.color.discord_2021_05),
        PaletteColor(CommonStrings.profile_color_discord_fuchsia, StylesR.color.discord_2021_06),
)

// element-web's original room avatar trio, from October 2015.
private val ALPHA_ROOM_COLORS = listOf(
        PaletteColor(CommonStrings.profile_color_alpha_moss, StylesR.color.element_room_alpha_01),
        PaletteColor(CommonStrings.profile_color_alpha_lagoon, StylesR.color.element_room_alpha_02),
        PaletteColor(CommonStrings.profile_color_alpha_sand, StylesR.color.element_room_alpha_03),
)

// element-web 2019-2023 room avatars, with the July 2020 green.
private val LEGACY_ROOM_COLORS = listOf(
        PaletteColor(CommonStrings.profile_color_name_element_green, StylesR.color.element_room_01),
        PaletteColor(CommonStrings.profile_color_name_azure, StylesR.color.element_room_02),
        PaletteColor(CommonStrings.profile_color_name_grape, StylesR.color.element_room_03),
)
