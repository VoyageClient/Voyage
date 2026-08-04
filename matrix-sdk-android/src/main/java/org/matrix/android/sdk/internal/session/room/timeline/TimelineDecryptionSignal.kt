/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.matrix.android.sdk.internal.session.SessionScope
import javax.inject.Inject

/**
 * Session-wide "an event was decrypted" fan-out, keyed by room. A decryption result is written to the
 * `event` table, which the timeline's `timeline_event` flow doesn't observe — so without a signal a
 * decrypt done elsewhere (another timeline, [RoomSummaryEventDecryptor] for the room-list preview)
 * leaves any open timeline showing "Encrypted message" until it is reopened. All decryptors emit here;
 * timelines and other event-displaying screens (e.g. pinned messages) refresh on a matching room id.
 */
@SessionScope
internal class TimelineDecryptionSignal @Inject constructor() {

    private val _rooms = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val rooms: SharedFlow<String> = _rooms.asSharedFlow()

    fun onDecrypted(roomId: String) {
        _rooms.tryEmit(roomId)
    }
}
