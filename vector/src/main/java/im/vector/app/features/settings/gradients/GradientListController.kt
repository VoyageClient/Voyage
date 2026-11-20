/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.gradients

import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.R
import im.vector.app.core.epoxy.dividerItem
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.imagepack.edit.imagePackListItem
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

class GradientListController @Inject constructor(
        private val stringProvider: StringProvider,
) : TypedEpoxyController<List<CustomGradient>>() {

    interface Listener {
        fun onGradientClicked(gradient: CustomGradient)
        fun onAddGradient()
    }

    var listener: Listener? = null

    override fun buildModels(data: List<CustomGradient>?) {
        val host = this
        val gradients = data ?: return

        gradients.forEachIndexed { index, gradient ->
            if (index > 0) {
                dividerItem { id("divider_${gradient.label}") }
            }
            gradientListItem {
                id(gradient.label)
                gradient(gradient)
                onClickListener { host.listener?.onGradientClicked(gradient) }
            }
        }

        if (gradients.isNotEmpty()) dividerItem { id("divider_add") }
        imagePackListItem {
            id("add")
            title(host.stringProvider.getString(CommonStrings.gradient_add))
            placeholderIconRes(R.drawable.ic_plus)
            onClickListener { host.listener?.onAddGradient() }
        }
    }
}
