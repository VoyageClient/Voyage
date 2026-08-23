/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.profile

/** Profile field keys, split from [ProfileService] so models can use them without the service interface. */
object ProfileKeys {
    const val DISPLAY_NAME = "displayname"
    const val AVATAR_URL = "avatar_url"

    // MSC4427 profile banner. Write both keys, read the stable one first.
    const val BANNER_URL = "m.banner_url"
    const val BANNER_URL_UNSTABLE = "chat.commet.profile_banner"

    // MSC4247 pronouns. Write both keys, read the stable one first.
    const val PRONOUNS = "m.pronouns"
    const val PRONOUNS_UNSTABLE = "io.fsky.nyx.pronouns"

    // MSC4175 time zone. Write both keys, read the stable one first.
    const val TIMEZONE = "m.tz"
    const val TIMEZONE_UNSTABLE = "us.cloke.msc4175.tz"

    // MSC4426 status. The Commet key holds a bare string rather than the MSC's {text, emoji} object.
    const val STATUS = "m.status"
    const val STATUS_UNSTABLE = "org.matrix.msc4426.status"
    const val STATUS_COMMET = "chat.commet.profile_status"

    // MSC4440 biography. The Commet key holds a flat {body} object rather than the MSC's m.text array.
    const val BIOGRAPHY = "m.biography"
    const val BIOGRAPHY_UNSTABLE = "gay.fomx.biography"
    const val BIOGRAPHY_COMMET = "chat.commet.profile_bio"

    // MSC4522 name color. Write both keys, read the stable one first.
    const val COLOR_PREFERENCE = "m.color_preference"
    const val COLOR_PREFERENCE_UNSTABLE = "eu.she-a.color"

    /**
     * The extended fields worth streaming over sync (MSC4429 / MSC4262). Display name and avatar are
     * left out: those already arrive with room member events.
     */
    val SYNCED_EXTENDED_FIELDS = listOf(
            BANNER_URL, BANNER_URL_UNSTABLE,
            PRONOUNS, PRONOUNS_UNSTABLE,
            TIMEZONE, TIMEZONE_UNSTABLE,
            STATUS, STATUS_UNSTABLE, STATUS_COMMET,
            BIOGRAPHY, BIOGRAPHY_UNSTABLE, BIOGRAPHY_COMMET,
            COLOR_PREFERENCE, COLOR_PREFERENCE_UNSTABLE,
    )
}
