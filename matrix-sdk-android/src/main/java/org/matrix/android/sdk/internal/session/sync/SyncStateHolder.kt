/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync

import kotlinx.coroutines.flow.MutableStateFlow
import org.matrix.android.sdk.api.session.sync.SyncState
import org.matrix.android.sdk.internal.session.SessionScope
import javax.inject.Inject

/**
 * The session's sync state, owned by the session rather than by the sync thread that writes it.
 *
 * A thread is replaced whenever sync is stopped and started again — switching account, recovering a
 * terminated thread — and a state flow living on the thread left every existing observer watching the
 * dead instance, so the progress bar never moved again for the rest of the session.
 */
@SessionScope
internal class SyncStateHolder @Inject constructor() {

    val state = MutableStateFlow<SyncState>(SyncState.Idle)
}
