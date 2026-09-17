/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions.data

import im.vector.app.core.resources.BuildMeta
import im.vector.app.features.emoji.TwemojiProvider
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EmojiCatalogCacheTest {

    private val context = RuntimeEnvironment.getApplication()
    private var twemojiEnabled = true
    private var versionName = "1.2.3"

    private val twemojiProvider = mockk<TwemojiProvider>().also { every { it.enabled } answers { twemojiEnabled } }
    private val buildMeta = mockk<BuildMeta>().also { every { it.versionName } answers { versionName } }

    private fun cache() = EmojiCatalogCache(context, twemojiProvider, buildMeta)

    private val categories = listOf(
            EmojiCatalogCategory("Smileys & People", "😀", listOf("😀", "😃", "😄")),
            EmojiCatalogCategory("Animals & Nature", "🐶", listOf("🐶", "🐱")),
    )

    @Test
    fun `categories survive a round trip`() {
        cache().write(categories)

        cache().read() shouldBeEqualTo categories
    }

    @Test
    fun `nothing stored reads as null`() {
        cache().read().shouldBeNull()
    }

    /** What is renderable depends on which sprite set is in play, so the two must not share a catalog. */
    @Test
    fun `switching twemoji off invalidates the catalog`() {
        cache().write(categories)

        twemojiEnabled = false

        cache().read().shouldBeNull()
    }

    /** The categories ship with the app, so an upgrade may change them. */
    @Test
    fun `a new app version invalidates the catalog`() {
        cache().write(categories)

        versionName = "1.2.4"

        cache().read().shouldBeNull()
    }

    @Test
    fun `a category with no glyph left is dropped rather than shown empty`() {
        cache().write(categories + EmojiCatalogCategory("Empty", null, emptyList()))

        cache().read() shouldBeEqualTo categories
    }

    @Test
    fun `a garbled catalog reads as null rather than throwing`() {
        cache().write(categories)
        java.io.File(context.filesDir, "emoji-catalog.txt").writeText("nonsense")

        cache().read().shouldBeNull()
    }
}
