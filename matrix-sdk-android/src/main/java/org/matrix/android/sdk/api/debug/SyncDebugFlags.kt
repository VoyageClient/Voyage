/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.debug

/** Public for the app debug receiver; release flows must leave these switches at their defaults. */
object SyncDebugFlags {
    /** Allows background cold-sync measurements from adb. Resets on process start. */
    @Volatile
    @JvmStatic
    var keepSyncingInBackground: Boolean = false
}
