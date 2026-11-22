/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.debug.OperationDiagnostics
import org.matrix.android.sdk.api.session.events.model.LocalEcho
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent

internal fun TimelineEvent.resolveReadMarkerEvent(snapshot: List<TimelineEvent>): TimelineEvent? {
    if (!LocalEcho.isLocalEchoId(eventId)) return this

    val stableId = timelineStableId()
    snapshot.firstOrNull { !LocalEcho.isLocalEchoId(it.eventId) && it.timelineStableId() == stableId }?.let {
        DebugLog.i { "READDBG stage=resolve-local outcome=remote event=${OperationDiagnostics.id(eventId)} resolved=${OperationDiagnostics.id(it.eventId)}" }
        return it
    }

    // A sent echo can still be waiting for sync. Only events preceding it are known to be read.
    val index = snapshot.indexOfFirst { it.eventId == eventId }
    val preceding = if (index < 0) null else snapshot.asSequence().drop(index + 1).firstOrNull { !LocalEcho.isLocalEchoId(it.eventId) }
    DebugLog.i { "READDBG stage=resolve-local outcome=${if (preceding == null) "unavailable" else "preceding"} event=${OperationDiagnostics.id(eventId)} resolved=${OperationDiagnostics.id(preceding?.eventId)}" }
    return preceding
}
