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
    const val BANNER_URL = "m.banner_url"
    const val BANNER_URL_UNSTABLE = "chat.commet.profile_banner"
}
