/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.redaction

import org.matrix.android.sdk.api.session.events.model.Content

/** How a preserved copy of an event's content was obtained. */
enum class PreservationOrigin {
    /** Fetched from the server after the redaction, via MSC2815. */
    FETCHED,

    /** Copied as the event arrived, before any redaction. */
    CAPTURED
}

data class PreservedEventContent(
        val eventId: String,
        val roomId: String,
        val content: Content,
        /** The event's type before redaction, decrypted for E2EE rooms; null if it wasn't recorded. */
        val clearType: String?,
        val senderId: String?,
        val originServerTs: Long?,
        val origin: PreservationOrigin,
        val preservedAt: Long,
)

/**
 * Local store of message content that redaction would otherwise destroy.
 *
 * Backed by its own database file, so it survives clearing the session cache and the session
 * store's schema resets; it is removed with the account on sign-out.
 */
interface RedactedContentService {

    suspend fun getPreservedContent(eventId: String): PreservedEventContent?

    /** Everything preserved for a room, newest first. */
    suspend fun getPreservedContentInRoom(roomId: String): List<PreservedEventContent>

    suspend fun preserve(content: PreservedEventContent)

    suspend fun roomsWithPreservedContent(): List<String>

    /** Clear-cache: drops everything except rooms the user asked to keep. */
    suspend fun clearExcept(roomIds: Collection<String>)
}
