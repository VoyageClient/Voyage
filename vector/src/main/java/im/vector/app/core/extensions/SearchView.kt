/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.extensions

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat

/**
 * Starts a toolbar action-view SearchView's text where the toolbar title starts. Our SearchView style isn't
 * iconified by default, which makes AppCompat show a 32dp magnifier inside the expanded field, on top of
 * its own 8dp frame margin and 8dp text padding.
 */
fun SearchView.alignWithToolbarTitle() {
    // A null drawable keeps the magnifier gone: SearchView re-evaluates its visibility from the drawable.
    findViewById<ImageView>(androidx.appcompat.R.id.search_mag_icon)?.apply {
        setImageDrawable(null)
        visibility = View.GONE
    }
    findViewById<View>(androidx.appcompat.R.id.search_edit_frame)?.let { frame ->
        // The frame's margin is declared as left/right, not start/end.
        (frame.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            if (ViewCompat.getLayoutDirection(this) == ViewCompat.LAYOUT_DIRECTION_RTL) params.rightMargin = 0 else params.leftMargin = 0
            frame.layoutParams = params
        }
    }
    findViewById<View>(androidx.appcompat.R.id.search_src_text)?.let { text ->
        ViewCompat.setPaddingRelative(text, 0, text.paddingTop, ViewCompat.getPaddingEnd(text), text.paddingBottom)
    }
}
