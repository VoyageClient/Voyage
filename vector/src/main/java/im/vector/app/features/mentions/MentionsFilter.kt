/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.mentions

data class MentionsFilter(
        val includeRoomMentions: Boolean = true,
        val includeKeywords: Boolean = true,
        val excludeDms: Boolean = false,
)
