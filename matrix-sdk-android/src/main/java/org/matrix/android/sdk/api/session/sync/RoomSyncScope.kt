/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.sync

/**
 * Which rooms keep receiving their messages while they are not open. Rooms left out still get their name,
 * avatar, membership and an unread marker, and catch up on their messages when opened.
 */
enum class RoomSyncScope(val value: String) {
    ALL("all"),
    DIRECT_MESSAGES("direct"),
    OPENED_ONLY("opened");

    companion object {
        fun fromValue(value: String?): RoomSyncScope? = values().firstOrNull { it.value == value }
    }
}
