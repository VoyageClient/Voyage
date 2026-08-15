/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.accountdata

/**
 * "Stealth Mode": when enabled, the fork's own `im.voyage.*` user account data (quick reactions,
 * mention frequency, admin-status cache, profile overrides, watched rooms) is kept on-device only and
 * never uploaded to the homeserver, so a server admin cannot fingerprint the client from its account data.
 *
 * Static because it is read on the SDK write path, which the app cannot inject into; the app owns the
 * toggle and pushes its value here on startup and whenever the setting changes.
 */
object StealthAccountData {

    private const val LOCAL_ONLY_PREFIX = "im.voyage."

    @Volatile
    var enabled: Boolean = false

    /** Whether [type] must stay local when stealth mode is on. */
    fun isLocalOnly(type: String): Boolean = enabled && type.startsWith(LOCAL_ONLY_PREFIX)
}
