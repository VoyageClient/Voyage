/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.profile

import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/** Turns an IANA time-zone id (MSC4175) into a short abbreviation like "PST"/"PDT" or "GMT+2". */
@Singleton
class TimezoneFormatter @Inject constructor() {

    private val availableIds: Set<String> by lazy { TimeZone.getAvailableIDs().toHashSet() }

    /** null for a blank/unknown zone, so callers can hide the field (per MSC4175's "treat as unset"). */
    fun formatToShort(ianaId: String?): String? {
        if (ianaId.isNullOrBlank() || ianaId !in availableIds) return null
        val timeZone = TimeZone.getTimeZone(ianaId)
        // Pass the current DST state so America/Los_Angeles reads PST in winter, PDT in summer.
        val inDaylight = timeZone.inDaylightTime(Date())
        return timeZone.getDisplayName(inDaylight, TimeZone.SHORT, Locale.getDefault())
                .takeIf { it.isNotBlank() }
    }
}
