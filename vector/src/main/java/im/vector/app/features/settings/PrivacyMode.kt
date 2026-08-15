/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

/** When a privacy measure applies to an upload, which a room may then override either way. */
enum class PrivacyMode(val value: String) {
    ALWAYS("always"),
    NEVER("never"),

    /** Where anyone can walk in and read the history, an upload gives away more than it should. */
    PUBLIC_ROOMS("public");

    companion object {
        fun fromValue(value: String?) = entries.firstOrNull { it.value == value } ?: NEVER
    }
}
