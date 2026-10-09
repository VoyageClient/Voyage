/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.location

import im.vector.app.features.raw.wellknown.getElementWellknown
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.raw.RawService
import org.matrix.android.sdk.api.session.Session
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

class UrlMapProvider @Inject constructor(
        private val session: Session,
        private val rawService: RawService,
        locationSharingConfig: LocationSharingConfig,
) {
    private val keyParam = "?key=${locationSharingConfig.mapTilerKey}"

    private val fallbackMapUrl = buildString {
        append(MAP_BASE_URL)
        append(keyParam)
    }

    suspend fun getMapUrl(): String {
        val upstreamMapUrl = tryOrNull { rawService.getElementWellknown(session.sessionParams) }
                ?.getBestMapTileServerConfig()
                ?.mapStyleUrl
        return upstreamMapUrl ?: fallbackMapUrl
    }

    /**
     * [pinTipBelowCenter]: how many of the image's pixels below its centre the location lands, so a pin drawn
     * centred over the map has its tip on the location instead of its body above it.
     */
    fun buildStaticMapUrl(
            locationData: LocationData,
            zoom: Double,
            width: Int,
            height: Int,
            pinTipBelowCenter: Int = 0,
    ): String {
        return buildString {
            append(STATIC_MAP_BASE_URL)
            append(locationData.longitude)
            append(",")
            append(latitudeAbove(locationData.latitude, zoom, pinTipBelowCenter))
            append(",")
            append(zoom)
            append("/")
            append(width)
            append("x")
            append(height)
            append(".png")
            append(keyParam)
            // Since the default copyright font is too small we put a custom one on map
            append("&attribution=false")
        }
    }

    // The latitude [pixels] north of [latitude] on a Web Mercator map at [zoom], whose world is
    // STATIC_MAP_TILE_SIZE * 2^zoom pixels tall.
    private fun latitudeAbove(latitude: Double, zoom: Double, pixels: Int): Double {
        if (pixels == 0) return latitude
        val worldSize = STATIC_MAP_TILE_SIZE * 2.0.pow(zoom)
        val latRad = Math.toRadians(latitude)
        val y = (1 - ln(tan(latRad) + 1 / cos(latRad)) / PI) / 2 * worldSize
        return Math.toDegrees(atan(sinh(PI * (1 - 2 * (y - pixels) / worldSize))))
    }
}

// MapTiler's static maps use the vector styles' 512 px tiles.
private const val STATIC_MAP_TILE_SIZE = 512.0
