/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.settings

/**
 * Where the previews of the links we send are generated on the device, by fetching the page itself,
 * rather than asked from the homeserver. A room may override the account-wide mode with [ALWAYS] or
 * [NEVER]; the other two are predicates over which rooms match.
 */
enum class LinkPreviewMode(val value: String) {
    ALWAYS("always"),
    NEVER("never"),
    ENCRYPTED_ROOMS("encrypted"),
    DIRECT_MESSAGES("direct");

    companion object {
        fun fromValue(value: String?) = entries.firstOrNull { it.value == value } ?: ALWAYS
    }
}
