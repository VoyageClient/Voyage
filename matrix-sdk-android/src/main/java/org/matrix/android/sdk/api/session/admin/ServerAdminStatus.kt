/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.admin

/**
 * Whether the current user is an administrator of their homeserver.
 *
 * There is no spec'd way to ask this, so it is probed against Synapse's admin API. [UNKNOWN] covers
 * every case where the answer cannot be established — a homeserver with a different admin API
 * (tuwunel, and anything else that doesn't route `/_synapse`), or a network failure — and must not be
 * presented, or treated, as "no".
 */
enum class ServerAdminStatus {
    YES,
    NO,
    UNKNOWN;

    val isAdmin: Boolean get() = this == YES

    /**
     * Whether being an admin can't be ruled out. Use this to gate an *action* rather than [isAdmin]:
     * the probe only speaks Synapse's admin API, so on any other server the answer is [UNKNOWN] and
     * treating that as a refusal would withhold server-admin abilities from admins of those servers.
     */
    val mayBeAdmin: Boolean get() = this != NO

    companion object {
        fun fromValue(value: String?) = entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}
