/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.avatar

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.shapes.RectShape
import im.vector.app.features.settings.AvatarShape

/** An [AvatarShape]'s outline as a drawable shape, so letter avatars can take any of them. */
class AvatarPathShape(private val shape: AvatarShape) : RectShape() {

    private val path = Path()
    private val bounds = RectF()

    override fun onResize(width: Float, height: Float) {
        super.onResize(width, height)
        bounds.set(0f, 0f, width, height)
        AvatarShapes.path(shape, bounds, path)
    }

    override fun draw(canvas: Canvas, paint: Paint) {
        canvas.drawPath(path, paint)
    }
}
