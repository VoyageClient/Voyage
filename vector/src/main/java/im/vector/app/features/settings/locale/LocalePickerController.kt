/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.locale

import com.airbnb.epoxy.TypedEpoxyController
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.Uninitialized
import im.vector.app.core.epoxy.errorWithRetryItem
import im.vector.app.core.epoxy.loadingItem
import im.vector.app.core.epoxy.noResultItem
import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.settings.VectorLocale
import im.vector.app.features.settings.VectorPreferences
import im.vector.lib.strings.CommonStrings
import java.util.Locale
import javax.inject.Inject

class LocalePickerController @Inject constructor(
        private val vectorPreferences: VectorPreferences,
        private val stringProvider: StringProvider,
        private val errorFormatter: ErrorFormatter,
        private val vectorLocale: VectorLocale,
) : TypedEpoxyController<LocalePickerViewState>() {

    var listener: Listener? = null

    override fun buildModels(data: LocalePickerViewState?) {
        val list = data?.locales ?: return
        val currentLocale = data.currentLocale ?: return
        val host = this

        when (list) {
            Uninitialized,
            is Loading -> {
                loadingItem {
                    id("loading")
                }
            }
            is Success ->
                if (list().isEmpty()) {
                    noResultItem {
                        id("noResult")
                        text(host.stringProvider.getString(CommonStrings.no_result_placeholder))
                    }
                } else {
                    val selectedLocale = findSelected(list(), currentLocale)
                    list().forEach { locale ->
                        val isSelected = locale == selectedLocale
                        localeItem {
                            id(locale.toString())
                            flag(host.vectorLocale.localeToFlagEmoji(locale))
                            title(host.vectorLocale.localeToLocalisedString(locale))
                            if (host.vectorPreferences.developerMode()) {
                                subtitle(host.vectorLocale.localeToLocalisedStringInfo(locale))
                            }
                            selected(isSelected)
                            clickListener {
                                if (!isSelected) host.listener?.onLocaleClicked(locale)
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

    // The saved locale is restored without its script, and a device-default one may carry a region we don't
    // ship, so neither is guaranteed to equal a list entry.
    private fun findSelected(locales: List<Locale>, current: Locale): Locale? {
        return locales.firstOrNull { it.language == current.language && it.country == current.country && it.variant == current.variant }
                ?: locales.firstOrNull { it.language == current.language }
    }

    interface Listener {
        fun onLocaleClicked(locale: Locale)
    }
}
