/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import android.content.res.Resources
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.scopes.ActivityScoped
import im.vector.app.features.settings.VectorPreferences
import javax.inject.Inject
import kotlin.math.roundToInt

@ActivityScoped
class TimelineMediaSizeProvider @Inject constructor(
        private val resources: Resources,
        private val vectorPreferences: VectorPreferences
) {

    var recyclerView: RecyclerView? = null
        set(value) {
            field?.removeOnLayoutChangeListener(layoutListener)
            field = value
            viewportWidth = 0
            viewportHeight = 0
            cachedSize = null
            value?.addOnLayoutChangeListener(layoutListener)
            // addOnLayoutChangeListener only reports future passes; if the view is re-attached already
            // laid out, seed from its current bounds so we don't fall back to display metrics forever.
            value?.takeIf { it.width > 0 && it.height > 0 }?.let { adoptBounds(it.width, it.height) }
        }

    // The viewport the caps are derived from. Written only on the main thread (layout listener /
    // setter); read on the background model-build thread via cachedSize, hence @Volatile.
    private var viewportWidth = 0
    private var viewportHeight = 0

    @Volatile
    private var cachedSize: Pair<Int, Int>? = null

    // Models are built on a background thread, so getMaxSize() must not read the live RecyclerView
    // bounds there: with adjustResize the list height shrinks while the keyboard is up (e.g. when
    // sending), and a transient/shrunk read would get baked into the size and stick. Instead the
    // cap is recomputed here, on the main thread, from each committed layout pass.
    private val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
        adoptBounds(right - left, bottom - top)
    }

    private fun adoptBounds(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width != viewportWidth) {
            // First layout, or a width change (rotation / split-screen resize → new configuration):
            // take the new bounds wholesale.
            viewportWidth = width
            viewportHeight = height
        } else if (height > viewportHeight) {
            // Same width, taller pass (e.g. keyboard dismissed): grow the cap. We keep the tallest
            // height seen for this width so the keyboard shrinking the list never shrinks media.
            viewportHeight = height
        } else {
            return
        }
        cachedSize = computeMaxSize(viewportWidth, viewportHeight)
    }

    fun getMaxSize(): Pair<Int, Int> {
        cachedSize?.let { return it }
        // Not laid out yet: fall back to the display size so media built during the initial pass gets
        // a sensible cap instead of 0. Don't cache it — the layout listener installs the real size.
        return computeMaxSize(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
    }

    private fun computeMaxSize(width: Int, height: Int): Pair<Int, Int> {
        val maxImageWidth: Int
        val maxImageHeight: Int
        // landscape / portrait
        if (width < height) {
            maxImageWidth = (width * 0.7f).roundToInt()
            maxImageHeight = (height * 0.5f).roundToInt()
        } else {
            maxImageWidth = (width * 0.7f).roundToInt()
            maxImageHeight = (height * 0.7f).roundToInt()
        }
        return if (vectorPreferences.useMessageBubblesLayout()) {
            Pair(maxImageWidth.coerceAtMost(bubbleContentMaxWidth(resources, width)), maxImageHeight)
        } else {
            Pair(maxImageWidth, maxImageHeight)
        }
    }
}

/**
 * The widest a bubble can show its content on this screen: everything between the screen edges and
 * the bubble's content — the avatar column, the send-state column, the bubble's own margins and its
 * inner padding — taken off the screen width. Content wider than this is clipped by the bubble.
 */
fun bubbleContentMaxWidth(resources: Resources, availableWidth: Int = resources.displayMetrics.widthPixels): Int {
    val density = resources.displayMetrics.density
    val chrome = ((AVATAR_COLUMN_DP + SEND_STATE_MARGINS_DP) * density).roundToInt() +
            resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.item_event_message_state_size) +
            2 * resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.sc_bubble_wrap_margin_horizontal) +
            resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.dual_bubble_one_side_without_avatar_margin) +
            resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.sc_bubble_inner_padding_long_side) +
            resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.sc_bubble_inner_padding_short_side)
    return (availableWidth - chrome)
            .coerceAtMost(resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.chat_bubble_fixed_size))
}

/** Avatar plus its start margin, as laid out in item_timeline_event_base. */
private const val AVATAR_COLUMN_DP = 44f + 8f
private const val SEND_STATE_MARGINS_DP = 8f + 8f
