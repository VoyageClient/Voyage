/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.debug

import timber.log.Timber

/** Diagnostic messages are evaluated only while logging is enabled. */
object DebugLog {

    @Volatile
    @JvmStatic
    var enabled: Boolean = false

    // Explicit tags avoid DebugTree walking the caller's stack for every diagnostic line.
    @PublishedApi
    internal fun tag(message: String): String {
        val end = message.indexOf(' ').let { if (it < 0) minOf(message.length, 23) else minOf(it, 23) }
        return message.substring(0, end).takeIf { it.endsWith("DBG") } ?: "Diagnostics"
    }

    inline fun i(message: () -> String) {
        if (enabled) message().let { Timber.tag(tag(it)).i(it) }
    }

    inline fun w(message: () -> String) {
        if (enabled) message().let { Timber.tag(tag(it)).w(it) }
    }

    inline fun w(throwable: Throwable, message: () -> String) {
        if (enabled) message().let { Timber.tag(tag(it)).w(throwable, it) }
    }

    inline fun e(message: () -> String) {
        if (enabled) message().let { Timber.tag(tag(it)).e(it) }
    }
}
