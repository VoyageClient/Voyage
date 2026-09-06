/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.autocomplete.command

import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.features.autocomplete.AutocompleteClickListener
import im.vector.app.features.home.AvatarRenderer
import javax.inject.Inject

class AutocompleteCommandController @Inject constructor(
        private val avatarRenderer: AvatarRenderer,
) : TypedEpoxyController<List<AutocompleteCommand>>() {

    var listener: AutocompleteClickListener<AutocompleteCommand>? = null

    override fun buildModels(data: List<AutocompleteCommand>?) {
        if (data.isNullOrEmpty()) {
            return
        }
        val host = this
        data.forEach { command ->
            autocompleteCommandItem {
                id(command.id)
                name(command.command)
                parameters(command.parameters)
                description(command.description)
                source(command.source)
                avatarRenderer(this@AutocompleteCommandController.avatarRenderer)
                clickListener { host.listener?.onItemClick(command) }
            }
        }
    }
}
