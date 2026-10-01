/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor

import android.graphics.Matrix

object FrameTransform {

    /**
     * Unrotated source pixels to the pixels of its frame at [quarterTurns], turned about the center
     * by the quarter turns and [tiltDegrees]. A tilt swings corners outside the frame, which a crop
     * normalised past 0..1 reaches.
     */
    fun sourceToFrame(sourceWidth: Int, sourceHeight: Int, quarterTurns: Int, tiltDegrees: Float): Matrix {
        val (frameWidth, frameHeight) = RotatedCrop.frameSize(sourceWidth.toFloat(), sourceHeight.toFloat(), quarterTurns)
        return Matrix().apply {
            postTranslate(-sourceWidth / 2f, -sourceHeight / 2f)
            postRotate(quarterTurns + tiltDegrees)
            postTranslate(frameWidth / 2f, frameHeight / 2f)
        }
    }
}
