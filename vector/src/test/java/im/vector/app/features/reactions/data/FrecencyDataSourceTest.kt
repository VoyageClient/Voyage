/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions.data

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class FrecencyDataSourceTest {

    private val now = 1_000_000_000_000L
    private fun daysAgo(days: Int) = now - days * 24 * 60 * 60 * 1000L

    @Test
    fun `each use weighs by its age`() {
        FrecencyDataSource.score(listOf(daysAgo(0)), now) shouldBeEqualTo 100
        FrecencyDataSource.score(listOf(daysAgo(3), daysAgo(4)), now) shouldBeEqualTo 170
        FrecencyDataSource.score(listOf(daysAgo(30), daysAgo(45), daysAgo(80), daysAgo(81)), now) shouldBeEqualTo 91
    }

    @Test
    fun `only the last ten uses count`() {
        FrecencyDataSource.score(List(9) { daysAgo(100) } + List(10) { daysAgo(1) }, now) shouldBeEqualTo 1000
    }

    @Test
    fun `frecency scales the total by the sampled uses' average weight`() {
        FrecencyDataSource.frecency(totalUses = 25, recentUses = listOf(daysAgo(1), daysAgo(20)), now = now) shouldBeEqualTo 1875
        FrecencyDataSource.frecency(totalUses = 3, recentUses = emptyList(), now = now) shouldBeEqualTo 0
    }

    @Test
    fun `legacy recents migrate as uses at migration time, ordered by their old counts`() {
        val legacyEmotes = mapOf("recent_emoticons" to listOf(listOf(listOf("mxc://a", "blob"), 2), listOf(listOf("mxc://b", "cat"), 5)))
        val legacyStickers = mapOf("recent_stickers" to listOf(listOf(mapOf("url" to "mxc://s", "body" to "wave"), 3)))

        val history = FrecencyDataSource.History.parse(content = null, legacyEmotes = { legacyEmotes }, legacyStickers = { legacyStickers }, now = now)
        val snapshot = snapshotOf(history)

        history.migrated shouldBeEqualTo true
        snapshot.rank(emptyList()) shouldBeEqualTo listOf("mxc://b" to 500, "mxc://a" to 200)
        snapshot.frequentStickers().map { it.mxcUrl } shouldBeEqualTo listOf("mxc://s")
    }

    @Test
    fun `ranking merges the shared recents with local uses, any variation selector being the same emoji`() {
        val history = FrecencyDataSource.History.parse(content = null, legacyEmotes = { null }, legacyStickers = { null }, now = now)
        history.emoji["❤"] = FrecencyDataSource.Entry(totalUses = 2, recentUses = listOf(daysAgo(40), daysAgo(50)))
        history.emoji["🔥"] = FrecencyDataSource.Entry(totalUses = 1, recentUses = listOf(daysAgo(1)))
        history.emoticons["mxc://a"] = FrecencyDataSource.Entry(totalUses = 3, recentUses = listOf(daysAgo(1)))

        // ❤️ was also used 6 times elsewhere; 👍 only elsewhere, so counted as just now.
        snapshotOf(history).rank(listOf("❤️" to 6, "👍" to 2)) shouldBeEqualTo listOf(
                "mxc://a" to 300,
                "👍" to 200,
                "❤" to 120,
                "🔥" to 100,
        )
    }

    @Test
    fun `an existing frecency event wins over the legacy recents`() {
        val content = mapOf("emoticons" to emptyMap<String, Any>(), "stickers" to emptyMap<String, Any>())
        val legacyEmotes = mapOf("recent_emoticons" to listOf(listOf(listOf("mxc://a", "blob"), 2)))

        val history = FrecencyDataSource.History.parse(content = content, legacyEmotes = { legacyEmotes }, legacyStickers = { null }, now = now)

        history.migrated shouldBeEqualTo false
        history.emoticons shouldBeEqualTo emptyMap()
    }

    @Test
    fun `stored content round-trips`() {
        val history = FrecencyDataSource.History.parse(content = null, legacyEmotes = { null }, legacyStickers = { null }, now = now)
        history.emoji["😀"] = FrecencyDataSource.Entry(totalUses = 4, recentUses = listOf(daysAgo(2), daysAgo(1)))

        val reparsed = FrecencyDataSource.History.parse(content = history.toContent(now), legacyEmotes = { null }, legacyStickers = { null }, now = now)

        reparsed.emoji shouldBeEqualTo history.emoji
        snapshotOf(reparsed).score("😀️") shouldBeEqualTo 200
        snapshotOf(reparsed).score("mxc://never") shouldBeEqualTo null
    }

    private fun snapshotOf(history: FrecencyDataSource.History) = FrecencyDataSource.Snapshot(history, now) { it.replace("️", "") }
}
