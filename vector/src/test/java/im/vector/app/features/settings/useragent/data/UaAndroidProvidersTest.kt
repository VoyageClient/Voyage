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

class UaAndroidProvidersTest {

    private val http = mockk<UaHttp>()

    @Test
    fun `device provider decodes UTF-16, filters OEMs and empty rows, and dedupes`() {
        val csv = "Retail Branding,Marketing Name,Device,Model\n" +
                "\"Google\",\"Pixel 9\",\"tokay\",\"Pixel 9\"\n" +
                "\"\",\"\",\"x\",\"y\"\n" +
                "\"Samsung\",\"Galaxy S24\",\"e1q\",\"SM-S921B\"\n" +
                "\"NoName\",\"Whatever\",\"z\",\"Z1\"\n"
        coEvery { http.getBytes(any()) } returns csv.toByteArray(Charsets.UTF_16)
        val options = runBlocking { DeviceModelProvider("device", "url", perBrandCap = 50).fetch(http) }
        // Ordered by OEM global share (Samsung ranks above Google), then name.
        options.map { it.value } shouldBeEqualTo listOf("Samsung SM-S921B", "Google Pixel 9")
        // Value is the Build.MODEL; label carries the marketing name so search works.
        options.first { it.value == "Samsung SM-S921B" }.label shouldBeEqualTo "Galaxy S24 (SM-S921B)"
    }

    @Test
    fun `device provider caps per brand so one huge catalogue doesn't starve others`() {
        val csv = "Retail Branding,Marketing Name,Device,Model\n" +
                "\"Samsung\",\"a\",\"d1\",\"SM-1\"\n" +
                "\"Samsung\",\"b\",\"d2\",\"SM-2\"\n" +
                "\"Samsung\",\"c\",\"d3\",\"SM-3\"\n" +
                "\"Google\",\"p\",\"tokay\",\"Pixel 9\"\n"
        coEvery { http.getBytes(any()) } returns csv.toByteArray(Charsets.UTF_16)
        val options = runBlocking { DeviceModelProvider("device", "url", perBrandCap = 2).fetch(http) }
        // 2 Samsung (capped) then Google still present.
        options.map { it.value } shouldBeEqualTo listOf("Samsung SM-1", "Samsung SM-2", "Google Pixel 9")
    }

    @Test
    fun `sha extraction pulls the 9-char hash from a release body`() {
        val json = """[{"body":"https://github.com/matrix-org/matrix-rust-sdk/tree/0d225a0edf4c995bdbf3d8969e750abc"},{"body":"no link"}]"""
        coEvery { http.get(any()) } returns json
        val provider = GithubVersionProvider("sdk", "url", "body") { body ->
            Regex("matrix-rust-sdk/tree/([0-9a-f]{7,40})").find(body)?.groupValues?.get(1)?.take(9)
        }
        runBlocking { provider.fetch(http) }.map { it.value } shouldBeEqualTo listOf("0d225a0ed")
    }

    @Test
    fun `build-id table parses rows and filters by device and android major`() {
        val html = """
            <table>
            <tr><th>Build</th><th>Tag</th><th>Version</th><th>Devices</th></tr>
            <tr><td>BP1A.250505.005.D1</td><td>android-15.0.0_r36</td><td>Android15</td><td>Pixel 9, Pixel 9 Pro</td></tr>
            <tr><td>AP4A.250105.002</td><td>android-14.0.0_r1</td><td>Android14</td><td>Pixel 7, Pixel 8</td></tr>
            <tr><td>QP1A.190711.020</td><td>android-10.0.0_r1</td><td>Android10</td><td>Pixel, Pixel XL</td></tr>
            <tr><td>not-a-build-id</td><td>x</td><td>Android14</td><td>Pixel 8</td></tr>
            </table>
        """.trimIndent()
        val rows = parseBuildIdTable(html)
        rows.size shouldBeEqualTo 3

        buildIdOptions(rows, "Google Pixel 9", 15).map { it.value } shouldBeEqualTo listOf("BP1A.250505.005.D1")
        buildIdOptions(rows, "Google Pixel 9", 14).map { it.value } shouldBeEqualTo emptyList()
        buildIdOptions(rows, "Google Pixel 8", 14).map { it.value } shouldBeEqualTo listOf("AP4A.250105.002")
        // Word-boundary match: "Pixel 9" must NOT match the original "Pixel" row.
        buildIdOptions(rows, "Google Pixel 9", 10).map { it.value } shouldBeEqualTo emptyList()
        buildIdOptions(rows, "Google Pixel", 10).map { it.value } shouldBeEqualTo listOf("QP1A.190711.020")
    }
}
