/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.read

/** Read-receipt constants, split from the (LiveData-bearing) [ReadService] so models can use them. */
object ReadKeys {
    /** Thread id representing the room's main (non-threaded) timeline. */
    const val THREAD_ID_MAIN = "main"
}
