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

class UaDesktopIosProvidersTest {

    @Test
    fun `electron parse keeps stable releases with their bundled chrome, newest-first`() {
        // electron-to-chromium's compact {electronVersion: chromeVersion} map.
        val json = """
            {
              "33.2.1":"130.0.6723.137",
              "34.0.0-nightly.1":"132.0.0.0",
              "32.2.0":"128.0.6613.186",
              "31.0.0":""
            }
        """.trimIndent()
        val releases = parseElectronReleases(json)
        // non-stable (nightly) dropped, chrome-less dropped, sorted newest-first by semver
        releases shouldBeEqualTo listOf(
                ElectronRelease("33.2.1", "130.0.6723.137"),
                ElectronRelease("32.2.0", "128.0.6613.186"),
        )
    }

    @Test
    fun `electron parse caps the list`() {
        val json = (1..100).joinToString(prefix = "{", postfix = "}", separator = ",") {
            """"1.0.$it":"1.0.0.$it""""
        }
        parseElectronReleases(json, limit = 60).size shouldBeEqualTo 60
    }

    @Test
    fun `ios parse keeps iPhone and iPad marketing names, deduped, newest-first`() {
        val json = """
            [
              {"name":"iPhone 15 Pro","identifier":"iPhone16,1"},
              {"name":"iPhone 16 Pro","identifier":"iPhone17,1"},
              {"name":"iPhone 15 Pro","identifier":"iPhone16,1"},
              {"name":"iPad Air (5th generation)","identifier":"iPad13,16"},
              {"name":"Apple Watch","identifier":"Watch7,1"},
              {"name":"iPhone 14","identifier":"iPhone14,7"}
            ]
        """.trimIndent()
        // iPhones (newest gen first), then iPads; watch dropped; duplicate deduped
        parseIosDevices(json) shouldBeEqualTo listOf(
                "iPhone 16 Pro", "iPhone 15 Pro", "iPhone 14", "iPad Air (5th generation)",
        )
    }

    @Test
    fun `ios device provider maps to options`() {
        val http = mockk<UaHttp>()
        coEvery { http.get(any()) } returns """[{"name":"iPhone 16 Pro","identifier":"iPhone17,1"}]"""
        val options = runBlocking { IosDeviceProvider("ios_device", "url").fetch(http) }
        options.map { it.value } shouldBeEqualTo listOf("iPhone 16 Pro")
    }
}
