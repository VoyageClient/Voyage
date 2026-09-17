/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import im.vector.app.core.ui.model.Size
import kotlin.math.max
import kotlin.math.min

/** Smallest a picture is allowed to be drawn at, whatever it declares: below this it is unreadable. */
const val MIN_MEDIA_SIDE_DP = 96

/** Share minimum sizing between display and outgoing SVG metadata. */
fun Size.atLeastMinimumMediaSize(floorPx: Int, maxWidth: Int, maxHeight: Int): Size {
    if (width <= 0 || height <= 0) return this
    val longest = max(width, height)
    if (longest >= floorPx) return this
    // The longer side, not the shorter one: a long thin strip is already legible, and scaling by its
    // short side would blow it up to absurd width.
    val scale = min(
            floorPx.toFloat() / longest,
            min(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
    )
    if (scale <= 1f) return this
    return Size((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
}
