/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.useragent.data

import org.amshove.kluent.shouldBeGreaterThan
import org.junit.Test

class UaBuildIdPerfTest {
    @Test
    fun `parses the real AOSP page quickly and without hanging`() {
        val html = javaClass.getResourceAsStream("/useragent/build_numbers.html")!!.reader().readText()
        val start = System.nanoTime()
        val rows = parseBuildIdTable(html)
        val ms = (System.nanoTime() - start) / 1_000_000
        println("parseBuildIdTable: ${rows.size} rows in $ms ms (html ${html.length} chars)")
        rows.size shouldBeGreaterThan 100
    }
}
