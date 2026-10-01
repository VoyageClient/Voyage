/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions

import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.imagepack.EmojiShortNames
import im.vector.app.features.imagepack.ImagePackProvider
import im.vector.app.features.reactions.data.EmojiCatalogCache
import im.vector.app.features.reactions.data.EmojiDataSource
import im.vector.app.features.reactions.data.EmojiItem
import im.vector.app.features.reactions.data.RecentEmojiDataSource
import im.vector.app.features.reactions.data.RecentEmoteDataSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class EmojiPickerSectionFilterTest {

    private val emojiDataSource = mockk<EmojiDataSource>()
    private val emojiShortNames = mockk<EmojiShortNames> {
        every { searchTerms(any()) } answers { listOf(firstArg()) }
        every { namesIn(any()) } returns emptyList()
    }
    private val factory = EmojiPickerSectionFactory(
            emojiDataSource = emojiDataSource,
            imagePackProvider = mockk<ImagePackProvider>(),
            activeSessionHolder = mockk<ActiveSessionHolder>(),
            recentEmojiDataSource = mockk<RecentEmojiDataSource>(),
            recentEmoteDataSource = mockk<RecentEmoteDataSource>(),
            catalogCache = mockk<EmojiCatalogCache>(),
            stringProvider = mockk<StringProvider>(),
            emojiShortNames = emojiShortNames,
    )

    private fun section(name: String, vararg items: EmojiPickerItem) =
            EmojiPickerSection(name = name, tabGlyph = null, tabImageUrl = null, items = items.toList())

    private fun emote(shortcode: String) =
            EmojiPickerItem.Emote(key = "mxc://x/$shortcode", shortcode = shortcode, resolvedUrl = null, contentDescription = shortcode)

    @Test
    fun `keeps categories with matches and drops the rest`() = runTest {
        coEvery { emojiDataSource.filterWith("cat") } returns listOf(EmojiItem(name = "cat", unicode = "1F431"))
        val sections = listOf(
                section("Blobs", emote("blobcat"), emote("blobwave")),
                section("Flags", EmojiPickerItem.Unicode("🇫🇷")),
                section("Animals", EmojiPickerItem.Unicode("🐱"), EmojiPickerItem.Unicode("🐶")),
        )

        val filtered = factory.filterSections(sections, "cat")

        filtered.map { it.name } shouldBeEqualTo listOf("Blobs", "Animals")
        filtered[0].items shouldBeEqualTo listOf(emote("blobcat"))
        filtered[1].items shouldBeEqualTo listOf(EmojiPickerItem.Unicode("🐱"))
    }

    @Test
    fun `an emoji in the query finds emotes named after it`() = runTest {
        coEvery { emojiDataSource.filterWith("😠") } returns emptyList()
        every { emojiShortNames.searchTerms("😠") } returns listOf("😠", "angry_face")
        val sections = listOf(section("Pack", emote("angry_face"), emote("Angry-Face_2"), emote("happy")))

        factory.filterSections(sections, "😠")[0].items shouldBeEqualTo listOf(emote("angry_face"), emote("Angry-Face_2"))
    }

    @Test
    fun `a name in the query finds emotes described by its emoji`() = runTest {
        coEvery { emojiDataSource.filterWith("angry") } returns emptyList()
        every { emojiShortNames.namesIn("😠") } returns listOf("angry_face")
        val mad = EmojiPickerItem.Emote(key = "mxc://x/mad", shortcode = "mad", resolvedUrl = null, contentDescription = "😠")
        val sections = listOf(section("Pack", mad, emote("happy")))

        factory.filterSections(sections, "angry")[0].items shouldBeEqualTo listOf(mad)
    }

    @Test
    fun `blank query returns the sections untouched`() = runTest {
        val sections = listOf(section("Blobs", emote("blobcat")))

        factory.filterSections(sections, "  ") shouldBeEqualTo sections
    }
}
