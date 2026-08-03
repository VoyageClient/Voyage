/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.timezone

import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.Success
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.platform.VectorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.Session
import java.util.TimeZone

class TimezonePickerViewModel @AssistedInject constructor(
        @Assisted initialState: TimezonePickerViewState,
        private val session: Session,
) : VectorViewModel<TimezonePickerViewState, TimezonePickerAction, TimezonePickerViewEvents>(initialState) {

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<TimezonePickerViewModel, TimezonePickerViewState> {
        override fun create(initialState: TimezonePickerViewState): TimezonePickerViewModel
    }

    companion object : MavericksViewModelFactory<TimezonePickerViewModel, TimezonePickerViewState> by hiltMavericksViewModelFactory()

    init {
        setState { copy(currentTimezoneId = session.profileService().getCachedTimezone(session.myUserId)) }
        viewModelScope.launch {
            val ids = withContext(Dispatchers.Default) { TimeZone.getAvailableIDs().toList().sorted() }
            setState { copy(timezones = Success(ids)) }
        }
    }

    override fun handle(action: TimezonePickerAction) {
        when (action) {
            is TimezonePickerAction.SelectTimezone -> saveTimezone(action.id)
            TimezonePickerAction.ClearTimezone -> saveTimezone("")
            is TimezonePickerAction.UpdateFilter -> setState { copy(filter = action.filter) }
        }
    }

    private fun saveTimezone(id: String) {
        viewModelScope.launch {
            tryOrNull { session.profileService().setTimezone(session.myUserId, id) }
            _viewEvents.post(TimezonePickerViewEvents.Close)
        }
    }
}
