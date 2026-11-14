/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.pushrules

data class NotificationReadReceipt(
        val roomId: String,
        val threadId: String?,
        val eventId: String,
        val timestamp: Double,
)
