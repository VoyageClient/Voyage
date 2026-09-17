/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.debug

import timber.log.Timber

/**
 * The `*DBG` diagnostic traces, which a release build must not pay for at all.
 *
 * [enabled] is off until the app turns it on, which it does only for a debug build. The message is a
 * lambda so that a release build does not build the string either — several of these sit on paths that
 * run per timeline bind or per media decode, where the concatenation alone is the cost that matters.
 */
object DebugLog {

    @Volatile
    @JvmStatic
    var enabled: Boolean = false

    inline fun i(message: () -> String) {
        if (enabled) Timber.i(message())
    }

    inline fun w(message: () -> String) {
        if (enabled) Timber.w(message())
    }

    inline fun w(throwable: Throwable, message: () -> String) {
        if (enabled) Timber.w(throwable, message())
    }

    inline fun e(message: () -> String) {
        if (enabled) Timber.e(message())
    }
}
