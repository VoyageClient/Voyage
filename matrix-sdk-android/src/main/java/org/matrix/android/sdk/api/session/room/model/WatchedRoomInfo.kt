/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.model

/** A previewable room the user chose to watch without joining (see /watch). */
data class WatchedRoomInfo(
        val roomId: String,
        val viaServers: List<String> = emptyList(),
        val name: String? = null,
        val avatarUrl: String? = null,
        val topic: String? = null,
        val alias: String? = null,
)
