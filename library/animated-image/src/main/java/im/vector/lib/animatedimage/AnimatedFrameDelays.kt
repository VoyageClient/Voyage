/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.animatedimage

/**
 * Shared by every reader, so the same animation runs at the same speed whichever format it arrived
 * in — a round trip through the editor turns one into another.
 *
 * Browsers clamp a zero or near-zero frame delay rather than racing through it; 20ms is the floor
 * they settle on, and the value the GIF and APNG specs' own "as fast as possible" maps to.
 */
internal const val MIN_FRAME_DELAY_MS = 20
