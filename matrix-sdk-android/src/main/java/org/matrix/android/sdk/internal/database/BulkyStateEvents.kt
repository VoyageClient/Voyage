/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database

import org.matrix.android.sdk.api.session.events.model.EventType

/**
 * State types whose stored JSON can be huge while the timeline only ever shows a collapsed notice for
 * them. Past [MAX_INLINE_LENGTH] the timeline read withholds their content instead of parsing it; the
 * screens that need the real thing (room settings, devtools) read current state, not the timeline.
 *
 * The threshold matters: an ordinary ACL or power-levels change is small and keeps its full notice
 * detail. Only the pathological ones — a moderation bot rewriting a 14KB deny list a hundred times —
 * degrade to a title-only notice.
 */
internal object BulkyStateEvents {

    const val MAX_INLINE_LENGTH = 8 * 1024L

    val TYPES = listOf(
            EventType.STATE_ROOM_SERVER_ACL,
            EventType.STATE_ROOM_POWER_LEVELS,
            EventType.STATE_ROOM_IMAGE_PACK,
            EventType.STATE_ROOM_IMAGE_PACK_UNSTABLE,
    )
}
