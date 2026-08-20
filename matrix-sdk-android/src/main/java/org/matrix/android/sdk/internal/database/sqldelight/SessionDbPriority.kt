/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sqldelight

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import org.matrix.android.sdk.internal.session.SessionScope
import javax.inject.Inject

/**
 * Keeps deferrable bulk work off the session database while the user is waiting on it.
 *
 * Every top-level transaction takes the driver's process-wide lock — Android's `beginTransaction`
 * takes SQLite's writer lock even for reads — so a background scan delays an interactive read
 * whichever dispatcher either one runs on, and moving it to another thread would not help.
 * Background callers park in [awaitTurn] until nothing interactive is in flight.
 */
@SessionScope
internal class SessionDbPriority @Inject constructor() {

    private val inFlight = MutableStateFlow(0)

    suspend fun <T> interactive(block: suspend () -> T): T {
        inFlight.update { it + 1 }
        return try {
            block()
        } finally {
            inFlight.update { it - 1 }
        }
    }

    suspend fun awaitTurn() {
        inFlight.first { it == 0 }
    }
}
