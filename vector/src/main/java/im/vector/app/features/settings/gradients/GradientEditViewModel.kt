/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.gradients

import androidx.lifecycle.ViewModel

data class EditableColor(val id: Long, val color: Int)

class GradientEditViewModel : ViewModel() {
    val colors = mutableListOf<EditableColor>()
    var label = ""
    var initial = CustomGradient("", emptyList())
    var loaded = false

    // The row the color picker is open for, or [PICKING_NEW] when adding a color.
    var pickingId: Long? = null
    private var nextId = 0L

    fun newColor(color: Int) = EditableColor(nextId++, color)

    fun current() = CustomGradient(label, colors.map { it.color })

    companion object {
        const val PICKING_NEW = -1L
    }
}
