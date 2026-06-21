/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.extensions

import android.content.Context
import android.content.res.TypedArray
import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources

/**
 * [TypedArray.getDrawable] framework-loads the drawable, which can't inflate a `<vector>` pre-21.
 * Resolve the resource via AppCompat so vector attributes work on KitKat.
 */
fun TypedArray.getDrawableCompat(context: Context, index: Int): Drawable? {
    val resId = getResourceId(index, 0)
    return if (resId != 0) AppCompatResources.getDrawable(context, resId) else null
}
