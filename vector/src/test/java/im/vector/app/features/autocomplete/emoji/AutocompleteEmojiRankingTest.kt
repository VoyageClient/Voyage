/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.autocomplete.emoji

import im.vector.app.features.imagepack.ResolvedImage
import im.vector.app.features.reactions.data.EmojiItem
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class AutocompleteEmojiRankingTest {

    private fun anEmote(shortcode: String) = AutocompleteEmojiData.Emote(
            ResolvedImage(shortcode = shortcode, mxcUrl = "mxc://example.org/$shortcode", body = null, info = null, usages = emptySet(), packDisplayName = null)
    )

    private fun anEmoji(vararg shortcodes: String) = AutocompleteEmojiData.Emoji(EmojiItem(name = shortcodes.first(), unicode = "1F525", shortcodes = shortcodes.toList()))

    private fun List<AutocompleteEmojiData>.names() = map {
        when (it) {
            is AutocompleteEmojiData.Emoji -> it.shortcode
            is AutocompleteEmojiData.Emote -> it.image.shortcode
        }
    }

    @Test
    fun `exact beats whole word beats prefix beats substring, emotes and emoji mixed`() {
        val rows = listOf(anEmoji("campfire"), anEmote("firework"), anEmote("on_fire"), anEmoji("fire_engine"), anEmote("fire"))

        AutocompleteEmojiRanking.sort(rows, "fire") { null }.names() shouldBeEqualTo listOf("fire", "fire_engine", "on_fire", "firework", "campfire")
    }

    @Test
    fun `a plural counts as a whole word`() {
        val rows = listOf(anEmote("catnip"), anEmote("BlobCat"), anEmote("cats"))

        AutocompleteEmojiRanking.sort(rows, "cat") { null }.names() shouldBeEqualTo listOf("cats", "catnip", "BlobCat")
    }

    @Test
    fun `equal scores fall back to the name`() {
        AutocompleteEmojiRanking.sort(listOf(anEmote("b_fire"), anEmote("a_fire")), "fire") { null }.names() shouldBeEqualTo listOf("a_fire", "b_fire")
    }

    @Test
    fun `an emoji ranks by its best shortcode and a disambiguated emote by its base`() {
        val rows = listOf(anEmote("crossed/hands"), anEmoji("fingers_crossed", "crossed_fingers"), anEmote("crossed_fingers/hands"))

        AutocompleteEmojiRanking.sort(rows, "crossed_fingers") { null }.names() shouldBeEqualTo listOf("crossed_fingers/hands", "fingers_crossed", "crossed/hands")
    }

    @Test
    fun `a keyword-only emoji ranks below every shortcode match`() {
        val rows = listOf(anEmoji("thumbsup"), anEmote("zzz_yes"))

        AutocompleteEmojiRanking.sort(rows, "yes") { null }.names() shouldBeEqualTo listOf("zzz_yes", "thumbsup")
    }

    @Test
    fun `frecency scales the match score`() {
        val rows = listOf(anEmote("fire"), anEmote("fire_engine"), anEmote("on_fire"))
        val frecency = mapOf("fire_engine" to 300, "fire" to 1)

        AutocompleteEmojiRanking.sort(rows, "fire") { frecency[(it as AutocompleteEmojiData.Emote).image.shortcode] }
                .names() shouldBeEqualTo listOf("fire_engine", "on_fire", "fire")
    }

    @Test
    fun `matching ignores case, spaces and underscores`() {
        AutocompleteEmojiRanking.matches("crossed_fingers", "crossedFing") shouldBeEqualTo true
        AutocompleteEmojiRanking.matches("crossed_fingers", "fingers crossed") shouldBeEqualTo false
        AutocompleteEmojiRanking.matches("fire/cats", "fire@emoji") shouldBeEqualTo true
    }
}
