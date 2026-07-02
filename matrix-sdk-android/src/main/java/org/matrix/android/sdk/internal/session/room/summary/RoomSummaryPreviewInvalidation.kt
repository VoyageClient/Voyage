/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.summary

import org.matrix.android.sdk.internal.session.SessionScope
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject

/**
 * Decrypting a room's latest previewable event only writes the `event` table, so the room list —
 * which observes `room_summary` and memoizes mapped summaries by their (unchanged) row — kept showing
 * the encrypted preview until an unrelated summary write. Decryptors report through this hub so every
 * [RoomSummaryDataSource] instance can evict its cached mapping before the row is touched.
 */
@SessionScope
internal class RoomSummaryPreviewInvalidation @Inject constructor() {

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun register(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun onPreviewChanged(roomId: String) {
        listeners.forEach { it(roomId) }
    }
}
