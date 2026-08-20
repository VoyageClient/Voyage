/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the home screen is on top. Its badge counters run several full room-list passes per
 * room-summary change, and a Mavericks view model keeps collecting while its activity is stopped —
 * so without this they recompute all the way through a timeline scroll in another activity, for
 * numbers nobody can see.
 */
@Singleton
class HomeScreenVisibility @Inject constructor() {

    private val visible = MutableStateFlow(false)

    fun onStarted() {
        visible.value = true
    }

    fun onStopped() {
        visible.value = false
    }

    /**
     * [source] while the home screen is showing, nothing while it is not. Re-collecting emits once
     * up front, so counters that went stale behind another screen refresh on the way back.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun <T> whileVisible(source: () -> Flow<T>, onResume: T): Flow<T> = visible.flatMapLatest { showing ->
        if (showing) source().onStart { emit(onResume) } else emptyFlow()
    }
}
