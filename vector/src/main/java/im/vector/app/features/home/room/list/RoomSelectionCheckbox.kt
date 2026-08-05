/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.list

import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import im.vector.app.R

fun ImageView.renderRoomSelectionCheckbox(isSelected: Boolean) {
    val res = if (isSelected) R.drawable.ic_checkbox_selection_on else R.drawable.ic_checkbox_selection_off
    setImageDrawable(AppCompatResources.getDrawable(context, res))
}
