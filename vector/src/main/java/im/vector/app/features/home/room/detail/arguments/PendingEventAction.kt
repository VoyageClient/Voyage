/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.arguments

/**
 * An action picked outside the timeline (currently from search results) that only the room screen can
 * carry out. The room opens on the event and runs it, instead of the caller silently dropping it.
 */
enum class PendingEventAction {
    REPLY,
    REPLY_IN_THREAD,
    QUOTE,
    EDIT,
    REACT,
    FORWARD,
    SHARE,
    SAVE,
    REDACT,
    RESEND,
    PIN,
    UNPIN,
    END_POLL,
    RE_REQUEST_KEY,
    IGNORE_USER,
    REVEAL_REDACTED,
    HIDE_REDACTED,
}
