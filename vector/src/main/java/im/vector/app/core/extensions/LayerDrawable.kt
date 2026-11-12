/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.extensions

import android.graphics.drawable.LayerDrawable

/**
 * [LayerDrawable.setLayerInset] that also resizes the layer at once, inside a drawable sized [width] x [height].
 * The framework call only takes effect on the next bounds change, which a host view's padding change never causes.
 */
fun LayerDrawable.setLayerInsetNow(index: Int, left: Int, top: Int, right: Int, bottom: Int, width: Int, height: Int) {
    setLayerInset(index, left, top, right, bottom)
    getDrawable(index).setBounds(left, top, width - right, height - bottom)
}
