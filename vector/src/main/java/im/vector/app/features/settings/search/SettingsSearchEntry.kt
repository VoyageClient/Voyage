/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.search

data class SettingsSearchEntry(
        val title: String,
        val summary: String?,
        val breadcrumb: List<String>,
        /** Fragment class names to push on top of the settings root, in order. */
        val screenPath: List<String>,
        /** The row to point at on the last screen of [screenPath], or null when the entry opens that screen. */
        val highlight: Highlight?,
) {
    data class Highlight(val key: String?, val title: String)
}
