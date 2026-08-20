/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.useragent.data

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class UaDataProvidersTest {

    private val http = mockk<UaHttp>()

    private fun provide(csvOrJson: String, provider: UaDataProvider): List<UaOption> {
        coEvery { http.get(any()) } returns csvOrJson
        return runBlocking { provider.fetch(http) }
    }

    @Test
    fun `csv line parsing respects quoted commas`() {
        parseCsvLine("\"Date\",\"10.0\",\"6.0 Marshmallow\"") shouldBeEqualTo listOf("Date", "10.0", "6.0 Marshmallow")
    }

    @Test
    fun `android provider maps the latest row, drops Other, and sorts newest-first`() {
        val csv = """
            "Date","10.0","6.0 Marshmallow","9.0 Pie","11.0","Other"
            2025-06,20.0,5.0,10.0,30.0,35.0
            2025-07,18.0,4.0,9.0,33.0,36.0
        """.trimIndent()
        val options = provide(csv, StatCounterProvider("android", "url", UaMappers::android))
        options.map { it.value } shouldBeEqualTo listOf("11", "10", "9", "6.0")
        options.first { it.value == "11" }.share shouldBeEqualTo 33.0
        options.first { it.value == "11" }.label shouldBeEqualTo "Android 11 (33.0%)"
    }

    @Test
    fun `statcounter picks the newest month even when rows are not in date order`() {
        // Real StatCounter CSVs put some early months at the very end; the newest row is not last.
        val csv = """
            "Date","10.0","11.0"
            2026-08,18.0,40.0
            2026-07,20.0,30.0
            2008-11,0,0
            2008-12,0,0
        """.trimIndent()
        val options = provide(csv, StatCounterProvider("android", "url", UaMappers::android))
        options.map { it.value } shouldBeEqualTo listOf("11", "10")
        options.first { it.value == "11" }.share shouldBeEqualTo 40.0
    }

    @Test
    fun `chrome provider keeps only Chrome entries and freezes the patch`() {
        val csv = """
            "Date","Chrome 131.0","Chrome 130.0","Firefox 133.0","Chrome for Android","Safari 18.0"
            2025-07,40.0,10.0,3.0,2.0,15.0
        """.trimIndent()
        val options = provide(csv, StatCounterProvider("chrome", "url", UaMappers::chrome))
        options.map { it.value } shouldBeEqualTo listOf("131.0.0.0", "130.0.0.0")
        options.first().label shouldBeEqualTo "Chrome 131 (40.0%)"
    }

    @Test
    fun `firefox provider reads Mozilla release-history keys, newest major first`() {
        val json = """{"140.0":"2025-01-01","139.0":"2024-11-01","128.0":"2024-06-01","3.6":"2010-01-01"}"""
        val options = provide(json, JsonKeyVersionProvider("firefox", "u") { UaOption("$it.0", "Firefox $it", null) })
        options.map { it.value } shouldBeEqualTo listOf("140.0", "139.0", "128.0", "3.0")
        options.first().label shouldBeEqualTo "Firefox 140"
    }

    @Test
    fun `macos provider maps marketing names to tokens`() {
        val csv = """
            "Date","macOS Sequoia","macOS Sonoma","macOS Catalina"
            2025-07,30.0,25.0,5.0
        """.trimIndent()
        val options = provide(csv, StatCounterProvider("macos", "url", UaMappers::macos))
        options.map { it.value } shouldBeEqualTo listOf("15_6", "14_6", "10_15_7")
    }

    @Test
    fun `dart provider enumerates recent minors from the stable version`() {
        val options = provide("""{"date":"2026-08-18","version":"3.13.1"}""", DartVersionProvider("dart", "u", 5))
        options.map { it.value } shouldBeEqualTo listOf("3.13", "3.12", "3.11", "3.10", "3.9")
        options.first().label shouldBeEqualTo "Dart 3.13"
    }

    @Test
    fun `desktop app version keeps clean semver and drops rc tags`() {
        val json = """[{"tag_name":"v1.12.13"},{"tag_name":"v1.12.13-rc.0"},{"tag_name":"v1.12.12"}]"""
        val provider = GithubVersionProvider("d", "u", "tag_name") { Regex("^v?(\\d+\\.\\d+\\.\\d+)$").find(it)?.groupValues?.get(1) }
        provide(json, provider).map { it.value } shouldBeEqualTo listOf("1.12.13", "1.12.12")
    }

    @Test
    fun `element x ios app version strips the release prefix`() {
        val json = """[{"tag_name":"release/26.08.2"},{"tag_name":"nightly/x"},{"tag_name":"release/26.08.1"}]"""
        val provider = GithubVersionProvider("e", "u", "tag_name") { it.removePrefix("release/").takeIf { v -> v != it && v.firstOrNull()?.isDigit() == true } }
        provide(json, provider).map { it.value } shouldBeEqualTo listOf("26.08.2", "26.08.1")
    }

    @Test
    fun `github provider extracts curl versions in order and drops rc tags`() {
        val json = """[{"tag_name":"curl-8_21_0"},{"tag_name":"rc-8_22_0-1"},{"tag_name":"curl-8_20_0"}]"""
        val provider = GithubVersionProvider("curl", "url", "tag_name") { tag ->
            Regex("^curl-(\\d+)_(\\d+)_(\\d+)$").find(tag)?.let { "${it.groupValues[1]}.${it.groupValues[2]}.${it.groupValues[3]}" }
        }
        provide(json, provider).map { it.value } shouldBeEqualTo listOf("8.21.0", "8.20.0")
    }
}
