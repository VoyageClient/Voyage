/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.locale

import android.widget.RadioButton
import android.widget.TextView
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.extensions.setTextOrHide
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay

@EpoxyModelClass
abstract class LocaleItem : VectorEpoxyModel<LocaleItem.Holder>(R.layout.item_locale) {

    @EpoxyAttribute var flag: String? = null
    @EpoxyAttribute var title: String? = null
    @EpoxyAttribute var subtitle: String? = null
    @EpoxyAttribute var selected: Boolean = false
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var clickListener: ClickListener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.view.onClick(clickListener)
        holder.flagView.setTextOrHide(flag?.prepareForDisplay())
        holder.titleView.setTextOrHide(title)
        holder.subtitleView.setTextOrHide(subtitle)
        holder.radioView.isVisible = true
        holder.radioView.isChecked = selected
    }

    class Holder : VectorEpoxyHolder() {
        val flagView by bind<TextView>(R.id.localeFlag)
        val radioView by bind<RadioButton>(R.id.localeRadio)
        val titleView by bind<TextView>(R.id.localeTitle)
        val subtitleView by bind<TextView>(R.id.localeSubtitle)
    }
}
