/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions.data

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class RecentEmojiFormatTest {

    @Test
    fun `both the stable objects and the older arrays parse`() {
        val stable = mapOf("recent_emoji" to listOf(mapOf("emoji" to "😅", "total" to 7.0), mapOf("emoji" to "👍", "total" to 84)))
        val legacy = mapOf("recent_emoji" to listOf(listOf("😅", 7.0), listOf("mxc://example.org/blob", 2)))

        RecentEmojiDataSource.parse(stable) shouldBeEqualTo listOf("😅" to 7, "👍" to 84)
        RecentEmojiDataSource.parse(legacy) shouldBeEqualTo listOf("😅" to 7, "mxc://example.org/blob" to 2)
        RecentEmojiDataSource.parse(null) shouldBeEqualTo null
    }

    @Test
    fun `invalid entries are dropped`() {
        val content = mapOf(
                "recent_emoji" to listOf(
                        mapOf("emoji" to "😅", "total" to -1),
                        mapOf("emoji" to "👍", "total" to 1.5),
                        mapOf("emoji" to "", "total" to 1),
                        mapOf("total" to 1),
                        "🔥",
                        mapOf("emoji" to "✅", "total" to 3),
                )
        )

        RecentEmojiDataSource.parse(content) shouldBeEqualTo listOf("✅" to 3)
    }

    @Test
    fun `the older type merges in like Element Web, keeping each emoji's first position and higher total`() {
        val stable = listOf("😅" to 7, "👍" to 4)
        val legacy = listOf("👍" to 9, "🔥" to 1, "😅" to 2)

        RecentEmojiDataSource.merge(stable, legacy) shouldBeEqualTo listOf("😅" to 7, "👍" to 9, "🔥" to 1)
    }
}
