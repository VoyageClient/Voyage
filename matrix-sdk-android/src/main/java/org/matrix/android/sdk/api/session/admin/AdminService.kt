/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.admin

interface AdminService {

    /**
     * Ask the homeserver whether the current user is a server administrator. Never throws: anything
     * that is not a definitive answer comes back as [ServerAdminStatus.UNKNOWN].
     */
    suspend fun probeServerAdminStatus(): ServerAdminStatus
}
