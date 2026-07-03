/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui

/**
 * Runtime mirror of the "Performance mode" user setting, read from hot render paths (BlurHash decode,
 * spoiler blur, image corner clipping) that can't inject VectorPreferences. When [enabled], the app
 * takes cheaper rendering routes and drops fancy graphical effects. Seeded from the persisted
 * preference in VectorApplication.onCreate and updated live when the toggle changes.
 */
object PerformanceMode {
    @Volatile
    var enabled: Boolean = false
}
