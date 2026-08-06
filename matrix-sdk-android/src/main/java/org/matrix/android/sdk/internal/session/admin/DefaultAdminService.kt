/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.admin

import org.matrix.android.sdk.api.session.admin.AdminService
import org.matrix.android.sdk.api.session.admin.ServerAdminStatus
import javax.inject.Inject

internal class DefaultAdminService @Inject constructor(
        private val getServerAdminStatusTask: GetServerAdminStatusTask
) : AdminService {

    override suspend fun probeServerAdminStatus(): ServerAdminStatus {
        return getServerAdminStatusTask.execute(Unit)
    }
}
