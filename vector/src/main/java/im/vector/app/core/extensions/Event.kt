/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.extensions

import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.resources.DateProvider
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.session.events.model.Event
import org.threeten.bp.LocalDateTime

fun Event.localDateTime(): LocalDateTime {
    return DateProvider.toLocalDateTime(originServerTs)
}

/** Human-readable reason a send failed, prefixed with the HTTP status when the homeserver was the one to reject it. */
fun Event.sendFailureReason(errorFormatter: ErrorFormatter): String? {
    val httpCode = sendStateHttpCode()
    val reason = sendStateError()
            ?.let { errorFormatter.toHumanReadable(Failure.ServerError(it, httpCode ?: 0)) }
            ?: sendStateDetails
            ?: return null
    return httpCode?.let { "HTTP $it: $reason" } ?: reason
}
