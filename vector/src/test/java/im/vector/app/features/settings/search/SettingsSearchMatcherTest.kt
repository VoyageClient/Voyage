/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.search

import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class SettingsSearchMatcherTest {

    private fun entry(title: String, summary: String? = null, vararg breadcrumb: String) =
            SettingsSearchEntry(title, summary, breadcrumb.toList(), emptyList(), SettingsSearchEntry.Highlight(null, title))

    @Test
    fun `ignores case and accents on both sides`() {
        val matcher = SettingsSearchMatcher(listOf(entry("Thème"), entry("Language")))

        matcher.search("THEME").map { it.title } shouldBeEqualTo listOf("Thème")
        matcher.search("thème").map { it.title } shouldBeEqualTo listOf("Thème")
    }

    @Test
    fun `requires every word, from any of title, summary and breadcrumb`() {
        val matcher = SettingsSearchMatcher(
                listOf(
                        entry("Show inline URL previews", "Previews for links", "Preferences", "Timeline"),
                        entry("Show read receipts", null, "Preferences", "Timeline"),
                )
        )

        matcher.search("timeline url").map { it.title } shouldBeEqualTo listOf("Show inline URL previews")
        matcher.search("links show").map { it.title } shouldBeEqualTo listOf("Show inline URL previews")
        matcher.search("timeline missing").shouldBeEmpty()
    }

    @Test
    fun `ranks title prefix, then word prefix, then title, then summary, then breadcrumb`() {
        val matcher = SettingsSearchMatcher(
                listOf(
                        entry("Unrelated", null, "Theme"),
                        entry("Unrelated too", "Pick a theme"),
                        entry("Autotheme"),
                        entry("Dark theme"),
                        entry("Theme"),
                )
        )

        matcher.search("theme").map { it.title } shouldBeEqualTo
                listOf("Theme", "Dark theme", "Autotheme", "Unrelated too", "Unrelated")
    }

    @Test
    fun `keeps settings order between equal matches`() {
        val matcher = SettingsSearchMatcher(listOf(entry("Show A"), entry("Show B"), entry("Show C")))

        matcher.search("show").map { it.title } shouldBeEqualTo listOf("Show A", "Show B", "Show C")
    }

    @Test
    fun `a blank query matches nothing`() {
        SettingsSearchMatcher(listOf(entry("Theme"))).search("  ").shouldBeEmpty()
    }
}
