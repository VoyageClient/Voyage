/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.homeserver

import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick

/**
 * Section title of the homeserver URL list, with the button that rechecks the mirrors.
 */
@EpoxyModelClass
abstract class HomeserverUrlsItem : VectorEpoxyModel<HomeserverUrlsItem.Holder>(R.layout.item_settings_homeserver_urls) {

    @EpoxyAttribute @StringRes var titleResId: Int = 0
    @EpoxyAttribute var showRefresh: Boolean = false
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var onRefreshClick: ClickListener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.title.setText(titleResId)
        holder.refresh.isVisible = showRefresh
        holder.refresh.onClick(onRefreshClick)
    }

    class Holder : VectorEpoxyHolder() {
        val title by bind<TextView>(R.id.homeserverUrlsTitle)
        val refresh by bind<ImageButton>(R.id.homeserverUrlsRefresh)
    }
}
