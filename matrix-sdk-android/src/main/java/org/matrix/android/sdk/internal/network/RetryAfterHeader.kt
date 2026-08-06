/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.network

import org.matrix.android.sdk.api.failure.MatrixError
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * MSC4041: rate limits are moving from the `retry_after_ms` body field to the standard
 * `Retry-After` header. The body field still wins where a server sends both.
 */
internal fun MatrixError.withRetryAfterHeader(header: String?): MatrixError {
    if (retryAfterMillis != null || header == null) return this
    return copy(retryAfterMillis = header.parseRetryAfterMillis() ?: return this)
}

private fun String.parseRetryAfterMillis(): Long? {
    val delaySeconds = trim().toLongOrNull()
    if (delaySeconds != null) return (delaySeconds * 1000L).coerceAtLeast(0L)
    val date = parseHttpDate() ?: return null
    return (date.time - System.currentTimeMillis()).coerceAtLeast(0L)
}

private fun String.parseHttpDate(): Date? {
    // RFC 1123 is the only form servers are meant to send; the obsolete formats are not worth carrying.
    return try {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("GMT") }
                .parse(trim())
    } catch (throwable: Throwable) {
        Timber.w(throwable, "Unparsable Retry-After header")
        null
    }
}
