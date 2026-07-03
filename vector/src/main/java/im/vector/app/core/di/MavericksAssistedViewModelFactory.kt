/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.di

import com.airbnb.mvrx.MavericksState
import com.airbnb.mvrx.MavericksViewModel

/**
 * This factory allows Mavericks to supply the initial or restored [MavericksState] to Hilt.
 *
 * Add this interface inside of your [MavericksViewModel] class, then register the factory in the
 * per-group module in MavericksViewModelModule.kt that matches your ViewModel's package (see
 * [mavericksViewModelGroupOf]):
 *
 *   @Binds
 *   @IntoMap
 *   @MavericksViewModelKey("im.vector.app.features.mine.MyViewModel")
 *   fun myViewModelFactory(factory: MyViewModel.Factory): MavericksAssistedViewModelFactory<*, *>
 *
 * The bindings are split across sibling components (keyed by ViewModel FQN, not Class) so Dalvik's
 * verifier doesn't resolve every ViewModel at once on ICS. Put the binding in the group whose
 * [mavericksViewModelGroupOf] the ViewModel maps to, or it won't be found at runtime.
 */
interface MavericksAssistedViewModelFactory<VM : MavericksViewModel<S>, S : MavericksState> {
    fun create(initialState: S): VM
}
