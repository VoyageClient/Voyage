/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.autocomplete.command

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick
import im.vector.app.features.home.AvatarRenderer
import org.matrix.android.sdk.api.util.MatrixItem

@EpoxyModelClass
abstract class AutocompleteCommandItem : VectorEpoxyModel<AutocompleteCommandItem.Holder>(R.layout.item_autocomplete_command) {

    @EpoxyAttribute
    var name: String? = null

    @EpoxyAttribute
    var parameters: String? = null

    @EpoxyAttribute
    var description: String? = null

    @EpoxyAttribute
    var source: MatrixItem.UserItem? = null

    @EpoxyAttribute
    lateinit var avatarRenderer: AvatarRenderer

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var clickListener: ClickListener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.view.onClick(clickListener)
        holder.nameView.text = name
        holder.parametersView.text = parameters
        holder.descriptionView.text = description
        source?.let {
            holder.avatarView.visibility = View.VISIBLE
            avatarRenderer.render(it, holder.avatarView)
        } ?: run {
            holder.avatarView.visibility = View.GONE
        }
    }

    class Holder : VectorEpoxyHolder() {
        val avatarView by bind<ImageView>(R.id.commandAvatar)
        val nameView by bind<TextView>(R.id.commandName)
        val parametersView by bind<TextView>(R.id.commandParameter)
        val descriptionView by bind<TextView>(R.id.commandDescription)
    }
}
