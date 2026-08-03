/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.profile

import org.matrix.android.sdk.api.session.profile.Pronoun
import javax.inject.Inject
import javax.inject.Singleton

/** Builds the "she/her • PST" line shown below a username, from MSC4247 pronouns + MSC4175 tz. */
@Singleton
class ProfileFieldsFormatter @Inject constructor(
        private val timezoneFormatter: TimezoneFormatter,
) {

    fun format(pronouns: List<Pronoun>?, timezone: String?): String? {
        val pronounText = pronouns
                ?.forViewerLanguage()
                ?.mapNotNull { it.summary.takeIf { s -> s.isNotBlank() } }
                ?.distinct()
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(", ")
        val timezoneText = timezoneFormatter.formatToShort(timezone)
        return listOfNotNull(pronounText, timezoneText)
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" • ")
    }
}
