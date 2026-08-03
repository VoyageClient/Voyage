/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.timezone

import com.airbnb.epoxy.TypedEpoxyController
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.Uninitialized
import im.vector.app.core.epoxy.errorWithRetryItem
import im.vector.app.core.epoxy.loadingItem
import im.vector.app.core.epoxy.noResultItem
import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.profile.TimezoneFormatter
import im.vector.app.core.resources.StringProvider
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

class TimezonePickerController @Inject constructor(
        private val stringProvider: StringProvider,
        private val errorFormatter: ErrorFormatter,
        private val timezoneFormatter: TimezoneFormatter,
) : TypedEpoxyController<TimezonePickerViewState>() {

    var listener: Listener? = null

    override fun buildModels(data: TimezonePickerViewState?) {
        data ?: return
        val host = this
        val filter = data.filter.trim()

        if (filter.isEmpty()) {
            timezoneItem {
                id("none")
                title(host.stringProvider.getString(CommonStrings.settings_timezone_none))
                clickListener { host.listener?.onClearClicked() }
            }
        }

        when (val list = data.timezones) {
            Uninitialized,
            is Loading ->
                loadingItem { id("loading") }
            is Success -> {
                val filtered = list().filter { filter.isEmpty() || it.contains(filter, ignoreCase = true) }
                if (filtered.isEmpty()) {
                    noResultItem {
                        id("noResult")
                        text(host.stringProvider.getString(CommonStrings.no_result_placeholder))
                    }
                } else {
                    filtered.forEach { id ->
                        timezoneItem {
                            id(id)
                            title(id)
                            subtitle(host.timezoneFormatter.formatToShort(id))
                            clickListener { host.listener?.onTimezoneClicked(id) }
                        }
                    }
                }
            }
            is Fail ->
                errorWithRetryItem {
                    id("error")
                    text(host.errorFormatter.toHumanReadable(list.error))
                }
        }
    }

    interface Listener {
        fun onClearClicked()
        fun onTimezoneClicked(id: String)
    }
}
