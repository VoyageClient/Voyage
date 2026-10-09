/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.location

import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterThan
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

class UrlMapProviderTest {

    private val provider = UrlMapProvider(mockk(), mockk(), LocationSharingConfig(mapTilerKey = "key"))

    // The map's centre as the URL asks for it: "…/static/<lon>,<lat>,<zoom>/<w>x<h>.png…".
    private fun centerOf(url: String): Pair<Double, Double> {
        val (lon, lat) = url.removePrefix(STATIC_MAP_BASE_URL).substringBefore("/").split(",")
        return lon.toDouble() to lat.toDouble()
    }

    @Test
    fun `without a pin offset the map is centred on the location`() {
        centerOf(provider.buildStaticMapUrl(LocationData(48.85, 2.35, null), 17.0, 400, 400)) shouldBeEqualTo (2.35 to 48.85)
    }

    @Test
    fun `a pin offset moves the centre north by that many pixels, leaving the longitude alone`() {
        val zoom = 17.0
        val (lon, lat) = centerOf(provider.buildStaticMapUrl(LocationData(0.0, 2.35, null), zoom, 400, 400, pinTipBelowCenter = 100))
        lon shouldBeEqualTo 2.35
        lat shouldBeGreaterThan 0.0
        // At the equator Mercator is locally linear: 360° of latitude span the world's 512 * 2^zoom pixels.
        val expected = 100 * 360.0 / (512 * 2.0.pow(zoom))
        (abs(lat - expected) < expected * 0.01) shouldBeEqualTo true
    }
}
