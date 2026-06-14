/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

/**
 * In which rooms incoming photos/videos are shown directly; elsewhere they sit behind a
 * tap-to-reveal placeholder. [PRIVATE]/[DIRECT] mean "show only there, hide everywhere else".
 */
enum class MediaPreviewMode(val value: String) {
    ALWAYS_SHOW("always_show"),
    ALWAYS_HIDE("always_hide"),
    PRIVATE("private"),
    DIRECT("direct");

    companion object {
        fun fromValue(value: String?) = entries.firstOrNull { it.value == value } ?: ALWAYS_SHOW
    }
}
