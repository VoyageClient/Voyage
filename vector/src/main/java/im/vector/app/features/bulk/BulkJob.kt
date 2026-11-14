/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.bulk

import com.squareup.moshi.JsonClass

/** One queued job, saved so a killed app brings it back paused. Fields not used by [kind] stay empty. */
@JsonClass(generateAdapter = true)
data class BulkJob(
        val id: String,
        val kind: BulkJobKind,
        /** The rooms forwarded to, in order, or the one room redacted or mass-redacted in. */
        val roomIds: List<String>,
        /** Messages to redact, in timeline order. */
        val eventIds: List<String> = emptyList(),
        val reason: String? = null,
        /** Messages to forward, in timeline order; each is sent to every room in [roomIds]. */
        val forwards: List<BulkForward> = emptyList(),
        val massRedaction: BulkMassRedaction? = null,
        val paused: Boolean = false,
)

enum class BulkJobKind { FORWARD, REDACT, MASS_REDACTION }

@JsonClass(generateAdapter = true)
data class BulkForward(
        val eventType: String,
        val content: Map<String, Any?>,
        /** An earlier entry this one replied to in its source room; the copy then replies to that copy. */
        val inReplyToIndex: Int? = null,
        /** One per room in [BulkJob.roomIds], so resending after a failure or a restart can't post twice. */
        val txnIds: List<String>,
)

@JsonClass(generateAdapter = true)
data class BulkMassRedaction(
        val userId: String,
        val displayName: String,
        val delayMs: Long,
        val fromTs: Long? = null,
        val toTs: Long? = null,
        val messagesOnly: Boolean = false,
)

/**
 * How far a job has got, saved separately from the job so a long forward doesn't rewrite every message's
 * content after each send.
 */
@JsonClass(generateAdapter = true)
data class BulkJobProgress(
        /** Units done: messages redacted, or (room, message) sends completed in room-major order. */
        val next: Int = 0,
        /** Sent copies by "roomIndex:forwardIndex", for linking in-batch replies. */
        val sentEventIds: Map<String, String> = emptyMap(),
        /** Redactions that failed every attempt; the job pauses on them for a retry. */
        val failedEventIds: List<String> = emptyList(),
)

/** What the banner shows: the running job, mass redaction included, and how many wait behind it. */
data class BulkActionProgress(
        val label: String,
        val completed: Int,
        val total: Int,
        val paused: Boolean,
        val queued: Int,
)
