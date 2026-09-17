/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor

import android.graphics.RectF

/**
 * The crop as the user last set it, and the rotation they set it at.
 *
 * Turning a frame sideways changes its shape, so a crop has to be refitted and loses whatever did not
 * fit. Turning back does not give that back: refitting the sideways crop again only trims it further,
 * and four turns shrink it twice. Half a turn away from where a crop was set the frame has the same
 * shape again, so the crop the user chose fits exactly — this hands it back rather than a shrunken
 * derivative of it. Adjusting the crop makes wherever it was adjusted the shape to return to.
 */
class CropRotationAnchor {

    private var rect: RectF? = null
    private var rotationDegrees = 0

    fun anchor(crop: RectF, rotationDegrees: Int) {
        rect = RectF(crop)
        this.rotationDegrees = normalise(rotationDegrees)
    }

    fun clear() {
        rect = null
        rotationDegrees = 0
    }

    /**
     * The crop to use at [rotationDegrees], or null where this rotation is sideways to the anchored one
     * and the refitted crop is the best there is.
     */
    fun cropFor(rotationDegrees: Int): RectF? {
        val anchored = rect ?: return null
        return when (normalise(rotationDegrees - this.rotationDegrees)) {
            0 -> RectF(anchored)
            // Half a turn maps (x, y) to (1 - x, 1 - y): the crop keeps its size and where it sits.
            180 -> RectF(1f - anchored.right, 1f - anchored.bottom, 1f - anchored.left, 1f - anchored.top)
            else -> null
        }
    }

    private fun normalise(degrees: Int) = ((degrees % 360) + 360) % 360
}
