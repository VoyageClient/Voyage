/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.redaction

/** UI-facing snapshot of the single active mass-redaction job (or the paused remains of one). */
data class MassRedactionState(
        val roomId: String,
        val targetUserId: String,
        val targetDisplayName: String,
        val completed: Int,
        val total: Int,
        val paused: Boolean,
)
