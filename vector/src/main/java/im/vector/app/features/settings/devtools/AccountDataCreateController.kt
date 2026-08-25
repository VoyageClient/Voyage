/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.devtools

import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.R
import im.vector.app.core.epoxy.checkBoxItem
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.genericFooterItem
import im.vector.app.features.form.formEditTextItem
import im.vector.app.features.form.formMultiLineEditTextItem
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

class AccountDataCreateController @Inject constructor(
        private val stringProvider: StringProvider
) : TypedEpoxyController<AccountDataViewState>() {

    interface InteractionListener {
        fun processAction(action: AccountDataAction)
    }

    var interactionListener: InteractionListener? = null

    override fun buildModels(data: AccountDataViewState?) {
        if (data == null) return
        val host = this

        genericFooterItem {
            id("topSpace")
            text("".toEpoxyCharSequence())
        }
        formEditTextItem {
            id("account_data_type")
            layout(R.layout.item_form_text_input_compact)
            enabled(true)
            value(data.draft.type)
            hint(host.stringProvider.getString(CommonStrings.dev_tools_form_hint_type))
            onTextChange { text ->
                host.interactionListener?.processAction(AccountDataAction.DraftTypeChange(text))
            }
        }
        checkBoxItem {
            id("account_data_encrypt")
            title(host.stringProvider.getString(CommonStrings.account_data_encrypt))
            checked(data.draft.encrypt)
            checkChangeListener { _, checked ->
                host.interactionListener?.processAction(AccountDataAction.DraftEncryptChange(checked))
            }
        }
        formMultiLineEditTextItem {
            id("account_data_content")
            enabled(true)
            value(data.draft.content)
            hint(host.stringProvider.getString(CommonStrings.dev_tools_form_hint_event_content))
            onTextChange { text ->
                host.interactionListener?.processAction(AccountDataAction.DraftContentChange(text))
            }
        }
    }
}
