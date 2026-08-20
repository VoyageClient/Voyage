/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.widget.TextView
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel

/**
 * A "Collapse" control rendered at the newest end of an expanded merged run, so a group taller than the
 * screen can be compacted from its far edge without scrolling back to the header at the top.
 */
@EpoxyModelClass
abstract class MergedCollapseFooterItem : VectorEpoxyModel<MergedCollapseFooterItem.Holder>(R.layout.item_timeline_event_merged_collapse_footer) {

    @EpoxyAttribute var onCollapseClicked: (() -> Unit)? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.collapseView.setOnClickListener { onCollapseClicked?.invoke() }
    }

    class Holder : VectorEpoxyHolder() {
        val collapseView by bind<TextView>(R.id.itemMergedCollapseFooterTextView)
    }
}
