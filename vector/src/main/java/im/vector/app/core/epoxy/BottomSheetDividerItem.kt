/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.core.epoxy

import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R

@EpoxyModelClass
abstract class BottomSheetDividerItem : VectorEpoxyModel<BottomSheetDividerItem.Holder>(R.layout.item_divider_on_surface) {

    @EpoxyAttribute var subtle: Boolean = false

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.view.alpha = if (subtle) 0.35f else 1f
    }

    class Holder : VectorEpoxyHolder()
}
