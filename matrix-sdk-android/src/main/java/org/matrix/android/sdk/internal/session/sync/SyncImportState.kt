/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync

import org.matrix.android.sdk.internal.session.SessionScope
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/** Protect response imports from sync-now cancellation, which would discard work and replay the delta. */
@SessionScope
internal class SyncImportState @Inject constructor() {

    private val depth = AtomicInteger(0)

    val isImporting: Boolean get() = depth.get() > 0

    /**
     * Whether the server still owes this connection rooms. A sliding-sync fill is several requests, and
     * between them the response that just landed is "done" — without this the sync thread would drop out
     * of catch-up (hiding the progress bar) while the room list is still being handed over.
     */
    @Volatile
    var catchUpPending: Boolean = false

    suspend fun <T> importing(block: suspend () -> T): T {
        depth.incrementAndGet()
        try {
            return block()
        } finally {
            depth.decrementAndGet()
        }
    }
}
