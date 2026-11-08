/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

// In RecyclerView's package for its package-private prefetcher.
package androidx.recyclerview.widget

/**
 * Schedules the prefetch RecyclerView only does for its own touch scrolls and flings, so rows about to
 * come on screen are built between frames rather than on arrival. For a list moved by [RecyclerView.scrollBy]
 * every frame. Does nothing below API 21, where RecyclerView has no prefetcher.
 */
fun RecyclerView.prefetchForScroll(dx: Int, dy: Int) {
    mGapWorker?.postFromTraversal(this, dx, dy)
}
