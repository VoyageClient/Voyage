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
import java.io.IOException

class UaDataProvidersTest {

    private val http = mockk<UaHttp>()

    private fun provide(csvOrJson: String, provider: UaDataProvider): List<UaOption> {
        coEvery { http.get(any(), any(), any()) } returns csvOrJson
        return runBlocking { provider.fetch(http) }
    }

    private fun fixed(vararg values: String) = object : UaDataProvider {
        override val id = "fixed"
        override val primaryUrl: String? = null
        override suspend fun fetch(http: UaHttp) = values.map { UaOption(it, it, null) }
    }

    private fun throwing(e: Exception) = object : UaDataProvider {
        override val id = "throwing"
        override val primaryUrl: String? = null
        override suspend fun fetch(http: UaHttp): List<UaOption> = throw e
    }

    @Test
    fun `statcounter skips the all-zero current month`() {
        val csv = """
            "Date","16.0","15.0","Other"
            2026-09,37.0,16.0,1.0
            2026-10,38.0,15.0,1.0
            2026-11,0,0,0
        """.trimIndent()
        val options = provide(csv, StatCounterProvider("android", "url", UaMappers::android))
        options.map { it.value } shouldBeEqualTo listOf("16", "15")
        options.first().share shouldBeEqualTo 38.0
    }

    @Test
    fun `fallback is used when the primary parses to nothing`() {
        runBlocking { FallbackProvider(fixed(), fixed("2")).fetch(http) }.map { it.value } shouldBeEqualTo listOf("2")
    }

    @Test
    fun `fallback is used when the primary throws`() {
        runBlocking { FallbackProvider(throwing(IOException("HTTP 500")), fixed("2")).fetch(http) }.map { it.value } shouldBeEqualTo listOf("2")
    }

    @Test
    fun `fallback is skipped when the primary has data`() {
        runBlocking { FallbackProvider(fixed("1"), throwing(IOException("unused"))).fetch(http) }.map { it.value } shouldBeEqualTo listOf("1")
    }

    @Test(expected = UaNotModified::class)
    fun `primary not-modified propagates instead of falling back`() {
        runBlocking { FallbackProvider(throwing(UaNotModified("u")), fixed("2")).fetch(http) }
    }

    @Test
    fun `android distributions become percentage shares`() {
        val json = """[
            {"name":"KitKat","version":"4.4","apiLevel":19,"distributionPercentage":0.001,"descriptionBlocks":[{"title":"t","body":"b"}]},
            {"name":"Pie","version":"9.0","apiLevel":28,"distributionPercentage":0.02},
            {"name":"Baklava","version":"16","apiLevel":36,"distributionPercentage":0.254}
        ]"""
        val options = provide(json, ParsedProvider("android", "u", ::parseAndroidDistributions))
        options.map { it.value } shouldBeEqualTo listOf("16", "9", "4.4")
        options.first().label shouldBeEqualTo "Android 16 (25.4%)"
        mostPopularValue(options) shouldBeEqualTo "16"
    }

    @Test
    fun `chrome version history collapses builds to majors`() {
        val json = """{"versions":[{"version":"157.0.8103.27"},{"version":"157.0.8103.26"},{"version":"156.0.8000.1"}]}"""
        val options = provide(json, ParsedProvider("chrome", "u") { parseChromeVersionHistory(it) })
        options.map { it.value } shouldBeEqualTo listOf("157.0.0.0", "156.0.0.0")
        options.first().label shouldBeEqualTo "Chrome 157"
    }

    @Test
    fun `endoflife cycles map to ios versions and macos tokens`() {
        val ios = """[{"cycle":"27","latest":"27.2","eol":false,"support":true},{"cycle":"26","latest":"26.7.2","eol":false,"support":"2026-09-14"}]"""
        provide(ios, ParsedProvider("ios", "u") { parseEolIos(it) }).map { it.value } shouldBeEqualTo listOf("27.2", "26.7.2")
        val macos = """[{"cycle":"27","codename":"Golden Gate","latest":"27.2","eol":false},{"cycle":"10.15","codename":"Catalina","latest":"10.15.8","eol":"2026-02-02"}]"""
        val options = provide(macos, ParsedProvider("macos", "u") { parseEolMacos(it) })
        options.map { it.value } shouldBeEqualTo listOf("27_2", "10_15_8")
        options.first().label shouldBeEqualTo "macOS Golden Gate (27.2)"
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
