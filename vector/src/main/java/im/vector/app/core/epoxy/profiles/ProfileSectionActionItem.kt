/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.epoxy.profiles

import android.widget.ImageView
import android.widget.TextView
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick

/** A profile section header with a trailing reset action, mirroring [im.vector.app.core.preference.VectorPreferenceCategoryWithAction]. */
@EpoxyModelClass
abstract class ProfileSectionActionItem : VectorEpoxyModel<ProfileSectionActionItem.Holder>(R.layout.item_profile_section_with_action) {

    @EpoxyAttribute
    lateinit var title: String

    @EpoxyAttribute
    var actionEnabled: Boolean = false

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var actionClickListener: ClickListener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.sectionView.text = title
        holder.actionView.isEnabled = actionEnabled
        holder.actionView.alpha = if (actionEnabled) 1f else 0.4f
        holder.actionView.onClick(if (actionEnabled) actionClickListener else null)
    }

    class Holder : VectorEpoxyHolder() {
        val sectionView by bind<TextView>(R.id.itemProfileSectionView)
        val actionView by bind<ImageView>(R.id.itemProfileSectionAction)
    }
}
