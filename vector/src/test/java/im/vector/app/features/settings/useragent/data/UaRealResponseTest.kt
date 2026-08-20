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
import org.amshove.kluent.shouldBeTrue
import org.junit.Test

/** Runs each provider against a real captured response, so structural surprises are caught here, not on-device. */
class UaRealResponseTest {

    private fun resource(name: String): String =
            javaClass.getResourceAsStream("/useragent/$name")!!.bufferedReader().readText()

    private fun runText(body: String, provider: UaDataProvider): List<UaOption> {
        val http = mockk<UaHttp>()
        coEvery { http.get(any()) } returns body
        return runBlocking { provider.fetch(http) }
    }

    @Test
    fun `chrome parses the real browser csv`() {
        val options = runText(resource("statcounter_browser.csv"), StatCounterProvider("chrome", "u", UaMappers::chrome))
        options.isNotEmpty().shouldBeTrue()
    }

    @Test
    fun `android parses the real csv`() {
        runText(resource("statcounter_android.csv"), StatCounterProvider("android", "u", UaMappers::android)).isNotEmpty().shouldBeTrue()
    }

    @Test
    fun `ios version parses the real csv`() {
        runText(resource("statcounter_ios.csv"), StatCounterProvider("ios", "u", UaMappers::ios)).isNotEmpty().shouldBeTrue()
    }

    @Test
    fun `macos parses the real csv`() {
        runText(resource("statcounter_macos.csv"), StatCounterProvider("macos", "u", UaMappers::macos)).isNotEmpty().shouldBeTrue()
    }
}
