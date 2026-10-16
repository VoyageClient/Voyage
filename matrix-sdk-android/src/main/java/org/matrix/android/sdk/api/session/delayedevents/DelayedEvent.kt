/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.delayedevents

import org.matrix.android.sdk.api.session.events.model.Content

/**
 * An MSC4140 event the homeserver holds for the current user and sends on their behalf at [sendTs].
 */
data class DelayedEvent(
        val delayId: String,
        val roomId: String,
        val type: String,
        val stateKey: String?,
        val content: Content,
        val delayMs: Long,
        val delayedSinceTs: Long,
) {
    /** On the server's clock, which can be off from ours by a few seconds. */
    val sendTs: Long get() = delayedSinceTs + delayMs
}

/**
 * A pending MSC4140 power-levels event that puts [userId] back to [level] (null meaning `users_default`)
 * at [sendTs].
 */
data class PowerLevelRestore(
        val delayId: String,
        val userId: String,
        val level: Int?,
        val sendTs: Long,
)
