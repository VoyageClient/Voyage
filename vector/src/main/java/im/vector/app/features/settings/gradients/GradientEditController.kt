/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.gradients

import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.R
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.imagepack.edit.imagePackListItem
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

class GradientEditController @Inject constructor(
        private val stringProvider: StringProvider,
) : TypedEpoxyController<List<EditableColor>>() {

    interface Listener {
        fun onColorClicked(color: EditableColor)
        fun onDeleteColor(color: EditableColor)
        fun onAddColor()
    }

    var listener: Listener? = null

    // The drag helper reorders the models, not our backing list.
    fun currentOrderedColors(): List<EditableColor> =
            adapter.copyOfModels.filterIsInstance<GradientColorItem_>().map { it.color() }

    override fun buildModels(data: List<EditableColor>?) {
        val host = this
        val colors = data.orEmpty()

        colors.forEachIndexed { index, color ->
            gradientColorItem {
                id(color.id)
                color(color)
                position(index + 1)
                onClickListener { host.listener?.onColorClicked(color) }
                onDeleteClick { host.listener?.onDeleteColor(color) }
            }
        }

        if (colors.size < CustomGradient.MAX_COLORS) {
            imagePackListItem {
                id("add_color")
                title(host.stringProvider.getString(CommonStrings.gradient_add_color))
                placeholderIconRes(R.drawable.ic_plus)
                onClickListener { host.listener?.onAddColor() }
            }
        }
    }
}
