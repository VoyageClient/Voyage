/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.signout.soft.epoxy

import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.features.settings.AppLogo

@EpoxyModelClass
abstract class LoginHeaderItem : VectorEpoxyModel<LoginHeaderItem.Holder>(R.layout.item_login_header) {

    override fun bind(holder: Holder) {
        super.bind(holder)
        val context = holder.view.context
        holder.logo.setImageDrawable(AppCompatResources.getDrawable(context, AppLogo.current(context).logoRes))
    }

    class Holder : VectorEpoxyHolder() {
        val logo by bind<ImageView>(im.vector.lib.ui.styles.R.id.loginLogo)
    }
}
