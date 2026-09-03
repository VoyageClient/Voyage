/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.extensions

import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat

private const val ACTION_END_PADDING_DP = 8

/**
 * Action icons carry only 5dp of padding app-wide (Widget.Vector.ActionButton), which leaves the last
 * one flush against the screen edge on toolbars that have no other end content.
 */
fun Toolbar.padActionsFromScreenEdge(pad: Boolean = true) {
    val end = if (pad) (ACTION_END_PADDING_DP * resources.displayMetrics.density).toInt() else 0
    ViewCompat.setPaddingRelative(this, ViewCompat.getPaddingStart(this), paddingTop, end, paddingBottom)
}
