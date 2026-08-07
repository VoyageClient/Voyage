/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.voice

import im.vector.app.core.utils.PermissionChecker

/**
 * Instrumentation tests are granted their permissions up front, so always answer yes.
 */
class FakePermissionChecker : PermissionChecker {
    override fun checkPermission(vararg permissions: String): Boolean = true
}
