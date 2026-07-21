/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.devtools

import com.airbnb.mvrx.Async
import com.airbnb.mvrx.MavericksState
import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.Uninitialized
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Types
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.platform.VectorViewEvents
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.core.resources.StringProvider
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.launch
import okio.Buffer
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataEvent
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.api.util.MatrixJsonParser
import org.matrix.android.sdk.flow.flow

data class AccountDataViewState(
        val accountData: Async<List<UserAccountDataEvent>> = Uninitialized
) : MavericksState

sealed class AccountDataViewEvents : VectorViewEvents {
    data class Failure(val throwable: Throwable) : AccountDataViewEvents()
    object UpdateSuccess : AccountDataViewEvents()
}

class AccountDataViewModel @AssistedInject constructor(
        @Assisted initialState: AccountDataViewState,
        private val stringProvider: StringProvider,
        private val session: Session
) :
        VectorViewModel<AccountDataViewState, AccountDataAction, AccountDataViewEvents>(initialState) {

    private val contentAdapter: JsonAdapter<JsonDict> = MatrixJsonParser.getMoshi()
            .adapter(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))

    init {
        session.flow().liveUserAccountData(emptySet())
                .execute {
                    copy(accountData = it)
                }
    }

    override fun handle(action: AccountDataAction) {
        when (action) {
            is AccountDataAction.DeleteAccountData -> handleDeleteAccountData(action)
            is AccountDataAction.UpdateAccountData -> handleUpdateAccountData(action)
        }
    }

    private fun handleDeleteAccountData(action: AccountDataAction.DeleteAccountData) {
        viewModelScope.launch {
            try {
                session.accountDataService().updateUserAccountData(action.type, emptyMap())
            } catch (failure: Throwable) {
                _viewEvents.post(AccountDataViewEvents.Failure(failure))
            }
        }
    }

    private fun handleUpdateAccountData(action: AccountDataAction.UpdateAccountData) {
        viewModelScope.launch {
            try {
                val json = parseJsonLeniently(action.content)
                        ?: throw IllegalArgumentException(stringProvider.getString(CommonStrings.dev_tools_error_no_content))
                session.accountDataService().updateUserAccountData(action.type, json)
                _viewEvents.post(AccountDataViewEvents.UpdateSuccess)
            } catch (failure: Throwable) {
                _viewEvents.post(AccountDataViewEvents.Failure(failure))
            }
        }
    }

    // Coerce up front so the JSON we show (and prefill the editor with) is already correct —
    // integers, not floats from Moshi's Any adapter.
    @Suppress("UNCHECKED_CAST")
    fun sanitizedJson(event: UserAccountDataEvent): String {
        val sanitized = event.copy(content = (coerceWholeDoublesToLongs(event.content) as? JsonDict).orEmpty())
        return MatrixJsonParser.getMoshi()
                .adapter(UserAccountDataEvent::class.java)
                .toJson(sanitized)
    }

    @Suppress("UNCHECKED_CAST")
    fun prettyContent(event: UserAccountDataEvent): String {
        val sanitized = (coerceWholeDoublesToLongs(event.content) as? JsonDict).orEmpty()
        return contentAdapter.indent("    ").toJson(sanitized)
    }

    // Lenient so minor hand-editing of the JSON isn't rejected outright.
    @Suppress("UNCHECKED_CAST")
    private fun parseJsonLeniently(text: String): JsonDict? {
        return contentAdapter.fromJson(JsonReader.of(Buffer().writeUtf8(text)).apply { isLenient = true })
                ?.let { coerceWholeDoublesToLongs(it) as? JsonDict }
    }

    // Moshi's Any adapter parses every JSON number as Double, so re-serializing would emit "w":1080.0 —
    // Synapse strictly rejects that (M_BAD_JSON "Bad JSON value: float"). Round-trip whole-number Doubles
    // back to Long. Same fix as RoomDevToolViewModel / LocalEchoEventFactory.
    private fun coerceWholeDoublesToLongs(value: Any?): Any? = when (value) {
        is Double -> if (value.isFinite() && value % 1.0 == 0.0 &&
                value >= Long.MIN_VALUE.toDouble() && value <= Long.MAX_VALUE.toDouble()) {
            value.toLong()
        } else value
        is Map<*, *> -> value.mapValues { coerceWholeDoublesToLongs(it.value) }
        is List<*> -> value.map { coerceWholeDoublesToLongs(it) }
        else -> value
    }

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<AccountDataViewModel, AccountDataViewState> {
        override fun create(initialState: AccountDataViewState): AccountDataViewModel
    }

    companion object : MavericksViewModelFactory<AccountDataViewModel, AccountDataViewState> by hiltMavericksViewModelFactory()
}
