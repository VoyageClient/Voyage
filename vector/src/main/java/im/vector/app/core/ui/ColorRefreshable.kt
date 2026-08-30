/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui

/**
 * A view that resolves colors once at bind rather than through the theme, and so has to be told when
 * a color setting changes. Views in a RecyclerView are re-bound instead and need not implement this.
 */
interface ColorRefreshable {
    fun refreshColors()
}
