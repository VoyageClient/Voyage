/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.style

import android.content.Context
import android.graphics.Bitmap
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.features.themes.BubbleThemeUtils

/**
 * Rounds a thumbnail to the same radius as the bubble border around it, else (e.g. SC's 3dp border
 * vs a hardcoded 8dp image) the corners don't match and leave a gap. Falls back to 8dp outside
 * bubbles.
 *
 * Shared rather than computed at bind: a transformation is part of Glide's cache key, so warming an
 * image with a different one caches something the bind then cannot use.
 */
fun TimelineMessageLayout.mediaCornerTransformation(context: Context): Transformation<Bitmap> {
    return if (this is TimelineMessageLayout.Bubble) {
        cornersRadius.granularRoundedCorners()
    } else {
        RoundedCorners(mediaCornerRadiusPx(context))
    }
}

fun TimelineMessageLayout.mediaCornerRadiusPx(context: Context): Int {
    return (this as? TimelineMessageLayout.ScBubble)?.bubbleAppearance?.getBubbleRadiusPx(context)
            ?: DimensionConverter(context.resources).dpToPx(8)
}

/**
 * The same radius for media previews (reply header, reply composer, long-press sheet), which have no
 * message layout of their own but should round exactly as the timeline does — i.e. by the configured
 * bubble roundness.
 */
fun mediaPreviewCornerRadiusPx(context: Context): Int =
        BubbleThemeUtils(context).getBubbleAppearance().getBubbleRadiusPx(context)
