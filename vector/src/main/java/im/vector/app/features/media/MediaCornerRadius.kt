/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import kotlin.math.max
import kotlin.math.min

/** What share of the shorter side a corner should take, once the media is small enough to matter. */
const val MEDIA_CORNER_FRACTION = 0.08f

/** Never more of the shorter side than this, however small the media gets: past it, it reads as a pill. */
const val MEDIA_CORNER_MAX_FRACTION = 0.2f

/** Below this share of the configured radius a corner stops reading as rounded at all. */
private const val MEDIA_CORNER_MIN_OF_CONFIGURED = 0.4f

/** Share capped radii between media, placeholders and viewer transitions to keep their shapes aligned. */
fun cappedMediaCornerRadius(radius: Float, width: Float, height: Float): Float {
    val shorter = min(width, height)
    val floor = min(radius * MEDIA_CORNER_MIN_OF_CONFIGURED, shorter * MEDIA_CORNER_MAX_FRACTION)
    return min(radius, max(shorter * MEDIA_CORNER_FRACTION, floor))
}
